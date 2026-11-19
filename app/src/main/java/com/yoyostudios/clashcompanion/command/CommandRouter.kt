package com.yoyostudios.clashcompanion.command

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.yoyostudios.clashcompanion.accessibility.ClashCompanionAccessibilityService
import com.yoyostudios.clashcompanion.api.AiConnection
import com.yoyostudios.clashcompanion.api.GeminiClient
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.command.CommandParser.CommandTier
import com.yoyostudios.clashcompanion.deck.DeckManager
import com.yoyostudios.clashcompanion.detection.ArenaDetector
import com.yoyostudios.clashcompanion.detection.HandDetector
import com.yoyostudios.clashcompanion.overlay.OverlayManager
import com.yoyostudios.clashcompanion.strategy.OpusCoach
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.*

/**
 * Routes parsed commands to execution across five tiers:
 *
 *  - FAST path: card slot tap + zone tap via AccessibilityService (~170ms)
 *  - QUEUE path: buffered commands that auto-play when card appears in hand
 *  - SMART path: Gemini Flash real-time tactical decisions using Opus playbook
 *  - TARGETING path: spell placement on Roboflow-detected enemy troop
 *  - CONDITIONAL path: voice rules triggered by Roboflow arena detection
 *
 * Also supports AUTOPILOT mode: AI plays cards automatically every ~4 seconds.
 */
object CommandRouter {

    private const val TAG = "ClashCompanion"

    private const val VISION_ARENA_TARGET_W = 540
    private const val VISION_ARENA_JPEG_QUALITY = 60

    /** Set by OverlayManager when overlay is shown */
    var overlay: OverlayManager? = null

    /** Current deck card names — set by DeckManager, used by CommandParser */
    var deckCards = listOf(
        "Knight", "Archers", "Minions", "Arrows",
        "Fireball", "Giant", "Musketeer", "Mini P.E.K.K.A"
    )
        set(value) {
            field = value.toList()
            cancelPendingDecision()
            cancelPendingGesture()
            resetLastPlayed()
        }

    /** Prevents rapid-fire double plays during tap animation */
    private var isBusy = false
    private var isGestureInFlight = false
    private var gestureRevision = 0L
    private var decisionRevision = 0L
    private var activeDecisionJob: Job? = null
    private var lastObservedMatchSession = -1L

    // ── Last Played Tracking (for "follow up" lane coherence) ──────────
    private var lastPlayedCard: String? = null
    private var lastPlayedZone: String? = null
    private var lastPlayedTimeMs: Long = 0L

    // Coroutine scope for Smart Path and Autopilot async calls
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Autopilot ─────────────────────────────────────────────────────────

    /** Whether autopilot mode is active (AI plays cards automatically) */
    var isAutopilot = false
        private set

    private var autopilotJob: Job? = null

    /** Autopilot decision interval in milliseconds */
    private const val AUTOPILOT_INTERVAL_MS = 4000L

    /** Commands that toggle autopilot ON */
    private val AUTOPILOT_ON_COMMANDS = setOf(
        "autopilot", "play for me", "auto play", "auto", "autoplay",
        "take over", "ai play", "ai mode"
    )

    /** Commands that toggle autopilot OFF (exact match) */
    private val AUTOPILOT_OFF_COMMANDS = setOf(
        "stop", "manual", "stop autopilot", "i got it", "stop auto",
        "my turn", "cancel autopilot",
        // STT misrecognitions from live testing
        "top autopilot", "autopilot stop", "autopillot stop",
        "top auto", "stop pilot", "top autopillot",
        "cancel auto", "cancel", "enough", "top"
    )

    /** Keywords for contains-based stop detection (only when autopilot is already active) */
    private val AUTOPILOT_OFF_KEYWORDS = setOf("stop", "manual", "cancel", "my turn", "i got it", "enough")

    // ── Queue Path (M7) ────────────────────────────────────────────────

    /** A buffered command waiting for a card to appear in hand */
    data class QueuedCommand(
        val card: String,
        val zone: String,
        val timestamp: Long = System.currentTimeMillis(),
        /** Tracks last play attempt for elixir retry. 0 = never attempted. */
        var lastAttemptMs: Long = 0L
    )

    /** FIFO queue of pending commands. Accessed only on main thread. */
    private val commandQueue = mutableListOf<QueuedCommand>()

    /** Queue entries expire after 2 minutes to prevent stale surprise plays */
    private const val QUEUE_TIMEOUT_MS = 120_000L

    /** Cooldown between retry attempts when card is in hand but elixir was low */
    private const val QUEUE_RETRY_COOLDOWN_MS = 1500L

    /** Grace period before removing a "played" entry — card may just be dimmed (low elixir) */
    private const val QUEUE_PLAYED_GRACE_MS = 5000L

    // ── Conditional Rules ──────────────────────────────────────────────

    /** A voice rule that triggers when Roboflow detects an enemy card */
    data class ConditionalRule(
        val triggerCard: String,     // Enemy card to watch for (e.g., "Hog Rider")
        val responseCard: String,   // Your card to play (e.g., "Skeleton Army")
        val responseZone: String,   // Where to play it (e.g., "center")
        val timestamp: Long = System.currentTimeMillis(),
        var consecutiveFrames: Int = 0  // Debounce counter
    )

    /** Active conditional rules. Accessed only on main thread. */
    private val activeRules = mutableListOf<ConditionalRule>()

    // ── Main Entry Point ──────────────────────────────────────────────

    /**
     * Main entry point. Called by SpeechService.onTranscript via OverlayManager.
     * Parses the transcript and routes to the appropriate execution tier.
     *
     * Supports "then"-chaining: "knight left then musketeer right" plays
     * knight immediately and queues musketeer for auto-play when it appears.
     *
     * @param transcript raw STT output text
     * @param sttLatencyMs time taken by STT inference in milliseconds
     */
    fun handleTranscript(transcript: String, sttLatencyMs: Long) {
        val cleaned = CommandParser.cleanTranscript(transcript)

        // ── Autopilot toggle (check before parsing & before isBusy gate) ──
        // ON commands toggle: if already active, turn OFF (so "autopilot" works as on/off)
        if (cleaned in AUTOPILOT_ON_COMMANDS) {
            if (isAutopilot) stopAutopilot() else startAutopilot()
            return
        }
        if (cleaned in AUTOPILOT_OFF_COMMANDS) {
            stopAutopilot()
            return
        }
        // Contains-based fallback: when autopilot is active, catch STT misrecognitions
        if (isAutopilot && AUTOPILOT_OFF_KEYWORDS.any { cleaned.contains(it) }) {
            stopAutopilot()
            return
        }

        // A human instruction supersedes an AI request, while the auto loop can resume later.
        cancelPendingDecision()
        if (isGestureInFlight) {
            Log.d(TAG, "CMD: Busy, skipping '$transcript'")
            overlay?.updateStatus("Wait...")
            return
        }

        val parseStart = System.currentTimeMillis()

        // Split on "then"/"than" for chained commands:
        // "knight left then musketeer right" -> play knight, queue musketeer
        val normalized = cleaned
            .replace(" and then ", " then ")
            .replace(" than ", " then ")
        val thenParts = normalized.split(" then ")

        // Parse and route the primary (first) segment
        val firstSegment = thenParts[0].trim()
        if (firstSegment.isNotEmpty()) {
            val cmd = CommandParser.parse(firstSegment, deckCards)
            val parseMs = System.currentTimeMillis() - parseStart

            Log.i(TAG, "CMD: tier=${cmd.tier} card='${cmd.card}' zone=${cmd.zone} " +
                    "conf=${String.format("%.2f", cmd.confidence)} " +
                    "parse=${parseMs}ms stt=${sttLatencyMs}ms raw='$transcript'")

            // Route by tier
            routeCommand(cmd, parseMs, sttLatencyMs, transcript)
        } else if (thenParts.size <= 1) {
            // Empty transcript with no "then" part — ignore
            Log.d(TAG, "CMD: Empty transcript, ignoring")
        }

        // Queue any "then"-chained segments
        for (i in 1 until thenParts.size) {
            val thenSegment = thenParts[i].trim()
            if (thenSegment.isEmpty()) continue
            queueThenSegment(thenSegment)
        }
    }

    /**
     * Route a parsed command to the correct execution tier.
     */
    private fun routeCommand(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long,
        rawTranscript: String
    ) {
        when (cmd.tier) {
            CommandTier.IGNORE -> {
                Log.d(TAG, "CMD: Ignored '$rawTranscript'")
            }

            CommandTier.FAST -> {
                executeFastPath(cmd, parseMs, sttLatencyMs)
            }

            CommandTier.QUEUE -> {
                executeQueuePath(cmd, parseMs, sttLatencyMs)
            }

            CommandTier.TARGETING -> {
                executeTargetingPath(cmd, parseMs, sttLatencyMs)
            }

            CommandTier.CONDITIONAL -> {
                executeConditionalPath(cmd)
            }

            CommandTier.SMART -> {
                executeSmartPath(cmd, parseMs, sttLatencyMs, rawTranscript)
            }
        }
    }

    /**
     * Queue a "then"-chained segment. Always adds to queue (never immediate).
     * The user said "X then Y" — Y should wait, even if Y is in hand right now.
     */
    private fun queueThenSegment(segment: String) {
        val cmd = CommandParser.parse(segment, deckCards)
        if (cmd.card.isEmpty()) {
            Log.w(TAG, "CMD: Then-chain couldn't parse: '$segment'")
            return
        }
        val zone = cmd.zone ?: CommandParser.getDefaultZone(cmd.card)
        if (zone == null) {
            overlay?.updateStatus("Where? Say '${cmd.card} left/right/center'")
            return
        }
        commandQueue.add(QueuedCommand(cmd.card, zone))
        val msg = "THEN: ${cmd.card} -> $zone (queued)"
        overlay?.updateStatus(msg)
        Log.i(TAG, "CMD: $msg (${commandQueue.size} in queue)")
    }

    // ── Smart Path Execution (M8) ─────────────────────────────────────

    /**
     * Execute the Smart Path: send context to Gemini Flash for tactical AI decision.
     *
     * Assembles: deck + Opus playbook + current hand + arena state + user command.
     * Gemini returns: {"action":"play","card":"...","zone":"...","reasoning":"..."}.
     * Validates card in hand, executes via Fast Path, shows reasoning on overlay.
     */
    private fun executeSmartPath(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long,
        rawTranscript: String
    ) {
        if (cmd.card.isNotEmpty() && cmd.zone == null) {
            overlay?.updateStatus("Where? Say '${cmd.card} left/right/center'")
            return
        }
        launchDecision(rawTranscript, sttLatencyMs, autonomous = false)
    }

    /** Invalidates responses independently of the periodic auto loop. Main thread only. */
    private fun cancelPendingDecision() {
        decisionRevision++
        activeDecisionJob?.cancel()
        activeDecisionJob = null
        isBusy = isGestureInFlight
    }

    private fun resetLastPlayed() {
        lastPlayedCard = null
        lastPlayedZone = null
        lastPlayedTimeMs = 0L
    }

    private fun cancelPendingGesture() {
        gestureRevision++
        ClashCompanionAccessibilityService.instance?.cancelPendingGestures()
        isGestureInFlight = false
        isBusy = activeDecisionJob?.isActive == true
        overlay?.setPassthrough(false)
    }

    private fun deckKey(): String = DeckManager.currentDeck.map { it.id }.sorted().joinToString(",")

    private fun decisionIsCurrent(context: AiDecisionContext): Boolean {
        val latest = ScreenCaptureService.getCaptureMetadata()
        return AiDecisionPolicy.isCurrent(
            context, decisionRevision, SystemClock.elapsedRealtime(),
            ScreenCaptureService.currentCaptureSessionId(), latest?.frameId,
            HandDetector.matchSessionId, deckKey()
        ) && HandDetector.hasReliableHand(2) && !isGestureInFlight
    }

    private fun launchDecision(
        userCommand: String,
        sttLatencyMs: Long,
        autonomous: Boolean
    ): Job? {
        if (!AiConnection.isConfigured()) {
            overlay?.updateStatus("Configura Gemini en Clash Commander")
            return null
        }
        if (DeckManager.currentDeck.size != 8) {
            overlay?.updateStatus("IA: Comparte el mazo actual de 8 cartas desde el juego")
            return null
        }
        if (isGestureInFlight || !HandDetector.hasReliableHand(2)) {
            overlay?.updateStatus("IA: Esperando una mano verificada de al menos 2 cartas")
            return null
        }
        cancelPendingDecision()
        val snapshot = ScreenCaptureService.copyLatestSnapshot() ?: run {
            overlay?.updateStatus("IA: Captura no disponible; no tocar")
            return null
        }
        val metadata = snapshot.metadata
        val matchSession = HandDetector.matchSessionId
        if (lastObservedMatchSession != matchSession) {
            resetLastPlayed()
            lastObservedMatchSession = matchSession
        }
        val context = AiDecisionContext(
            decisionRevision, SystemClock.elapsedRealtime(), metadata.frameId,
            metadata.capturedAtElapsedRealtime, metadata.captureSessionId,
            matchSession, deckKey()
        )
        val systemPrompt = buildSmartPrompt()
        val label = if (autonomous) "AUTO" else "SMART"
        isBusy = true
        overlay?.updateStatus("$label: Analizando partida...")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(AiDecisionPolicy.MAX_AGE_MS) {
                    val images = withContext(Dispatchers.Default) {
                        val arena = captureArenaImageBase64(snapshot.bitmap)
                            ?: throw IllegalStateException("No hay imagen de arena valida")
                        val hud = captureHudImageBase64(snapshot.bitmap)
                            ?: throw IllegalStateException("No hay imagen del HUD valida")
                        listOf(arena, hud)
                    }
                    GeminiClient.chatWithImages(
                        systemInstruction = systemPrompt,
                        userText = if (autonomous) {
                            "AUTOPILOT: Choose a safe action from the verified hand and arena image. " +
                                "Defend immediate threats, then consider attacking or counterpushing. " +
                                "Read observed_elixir ONLY if legible in image 2 (own bottom HUD); " +
                                "otherwise return wait with observed_elixir null. " +
                                "Return wait when evidence is unclear or no safe play is available."
                        } else userCommand,
                        images = images,
                        maxTokens = 256,
                        jsonMode = true
                    )
                }
                ensureActive()
                if (!decisionIsCurrent(context)) {
                    overlay?.updateStatus("$label: Situacion cambiada; decision descartada")
                    return@launch
                }
                when (val decision = AiDecisionPolicy.parse(GeminiClient.cleanJsonResponse(result))) {
                    is TacticalDecision.Wait -> {
                        overlay?.updateStatus("$label: Esperar | ${decision.reasoning}")
                    }
                    is TacticalDecision.Play -> {
                        val card = resolveCardName(decision.card)
                        val zone = resolveZoneName(decision.zone)
                        if (card == null || zone == null || !HandDetector.cardToSlot.containsKey(card)) {
                            overlay?.updateStatus("$label: Carta o zona no verificadas; no tocar")
                            return@launch
                        }
                        val cardInfo = DeckManager.currentDeck.firstOrNull { it.name == card }
                        if (cardInfo == null || !AiDecisionPolicy.isLegalPlacement(card, cardInfo.type, zone)) {
                            overlay?.updateStatus("$label: Posicion no permitida para esa carta; esperar")
                            return@launch
                        }
                        val cost = cardInfo.elixir.takeIf { it in 0..10 && card != "Mirror" }
                        if (!AiDecisionPolicy.canAfford(cost, decision.observedElixir, autonomous)) {
                            overlay?.updateStatus("$label: Elixir insuficiente o no legible; esperar")
                            return@launch
                        }
                        if (autonomous) {
                            val rejection = ArchetypePolicy.rejection(deckCards.toSet(), card, zone,
                                decision.observedElixir, decision.board)
                            if (rejection != null) {
                                overlay?.updateStatus("AUTO: Esperar | $rejection")
                                return@launch
                            }
                        }
                        // AI decisions are never silently queued for a different board state.
                        overlay?.updateStatus("$label: $card -> $zone | ${decision.reasoning}")
                        executeFastPath(
                            CommandParser.ParsedCommand(
                                tier = CommandTier.FAST, card = card, zone = zone,
                                confidence = 1.0f,
                                rawTranscript = "${if (autonomous) "AUTOPILOT" else "SMART"}: ${decision.reasoning}"
                            ),
                            parseMs = SystemClock.elapsedRealtime() - context.requestedAtElapsedRealtime,
                            sttLatencyMs = sttLatencyMs
                        )
                    }
                }
            } catch (error: TimeoutCancellationException) {
                if (context.revision == decisionRevision) {
                    overlay?.updateStatus("$label: Respuesta demasiado lenta; jugada cancelada")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (context.revision == decisionRevision) {
                    overlay?.updateStatus("$label: No se pudo obtener una decision segura")
                    Log.w(TAG, "$label: Decision failed (${error.javaClass.simpleName})")
                }
            } finally {
                if (context.revision == decisionRevision) {
                    activeDecisionJob = null
                    isBusy = isGestureInFlight
                }
            }
        }
        job.invokeOnCompletion { snapshot.bitmap.recycle() }
        activeDecisionJob = job
        job.start()
        return job
    }

    /**
     * Build the Gemini system prompt with deck, playbook, hand state, arena, and last played context.
     */
    private fun buildSmartPrompt(): String {
        val deckJson = DeckManager.getDeckJson()

        val playbook = OpusCoach.cachedPlaybook
        val playbookSection = if (!playbook.isNullOrBlank()) {
            "STRATEGIC PLAYBOOK (from deep analysis):\n$playbook"
        } else {
            "STRATEGIC PLAYBOOK: Not available. Use general Clash Royale knowledge."
        }

        val hand = HandDetector.currentVerifiedHand
        val handCards = (0..3).mapNotNull { hand[it] }.joinToString(", ")
        val handSection = if (handCards.isNotBlank()) {
            "CURRENT HAND (visually identified; affordability UNKNOWN): $handCards"
        } else {
            "CURRENT HAND: Unknown (hand detection not ready)"
        }

        // Arena state from Roboflow (null-safe — may not be active)
        val arenaSection = buildArenaSection()

        // Last played context for "follow up" lane coherence
        val lastPlayedSection = if (lastPlayedCard != null && lastPlayedTimeMs > 0) {
            val agoSec = (System.currentTimeMillis() - lastPlayedTimeMs) / 1000
            "\nLAST PLAY ATTEMPT (both taps completed; deployment unconfirmed): $lastPlayedCard at $lastPlayedZone (${agoSec}s ago)"
        } else ""

        return """You are a real-time Clash Royale tactical AI. Speed is critical — be decisive.

DECK: $deckJson
${DeckManager.tacticalContext()}
$playbookSection
$handSection
$arenaSection$lastPlayedSection
${ArchetypePolicy.guidance(deckCards.toSet())}
ELIXIR SENSOR: Not available. Card costs are metadata, not available elixir.
IMAGE 1: cropped arena. IMAGE 2: bottom HUD from the SAME captured frame.
Read your available elixir ONLY from the own purple elixir gauge/digit in IMAGE 2.
Do not confuse purple cost gems on individual cards with available elixir.
Set observed_elixir to an integer 0..10 ONLY if that gauge is clearly legible; otherwise null.
This reading is a visual inference, not a verified sensor. Never invent an amount.

Report immediate_threat as true/false ONLY when observable, otherwise null.
Report threat_lane as left/right only for an observed immediate threat, otherwise null.
Report enemy_tower_occupied as true ONLY when visible defenders occupy the proposed spell target.
These are visual inferences, never facts supplied by a calibrated sensor.
VALID ZONES: left_bridge, right_bridge, center, behind_left, behind_right, back_center, front_left, front_right, left tower, right tower

If an ARENA screenshot image is provided, use it as ground truth for threats and lane selection.
Orientation: YOUR side is the BOTTOM of the screen. ENEMY side is the TOP.

Rules:
- ONLY suggest cards in CURRENT HAND.
- When user says "defend" or "defense": prioritize a defensive play ONLY when the arena image supports it; otherwise wait. Use ARENA STATE and/or the arena screenshot to find the most urgent enemy threat (closest to your towers) and choose the correct lane (left/right). If the threat is already on your side or about to cross, place closer (front_left/front_right/center). If the threat is further away, you can place behind_left/behind_right for a stronger counterpush. Buildings (if in deck) prefer center to pull. Troops MAY also be placed at center when needed to pull (e.g., Hog Rider).
- When user says "push left" or "push right": choose an offensive action in that lane using the actual deck win condition, support, and archetype plan. Most troops enter from your side; Graveyard and suitable offensive spells may target the enemy tower. Wait if there is no safe attack.
- When user says "follow up": support LAST PLAYED card in the SAME LANE. Use synergies from this deck playbook; do not assume any default cards. If last played was at left, play support at left. Never cross lanes for follow-up.
- When user says "suggest" or gives a vague command: pick the best offensive play from current hand.
- For ambiguous commands: safest positive-elixir-trade play.
- Buildings go in "center" or "behind_left"/"behind_right".
- "left tower" and "right tower" are ENEMY princess towers. Use them only for cards legally deployable there (such as Graveyard or suitable spells), never for ordinary troops or buildings. Do not waste area spells on empty ground or your own towers.
- Return "wait" whenever the image is unclear, the hand is uncertain, or no safe play is justified. Never force a play.
- If observed_elixir is known, never choose a card whose cost exceeds it. If it is null, prefer wait; AUTOPILOT must wait. A manual defense request can suggest a conservative play without claiming affordability.
- This applies equally to defense, attack, and AUTOPILOT. A user command never overrides uncertain visual evidence.

Respond ONLY with JSON: {"action":"play","card":"<exact card name>","zone":"<zone_key>","observed_elixir":<integer 0..10 or null>,"immediate_threat":<boolean or null>,"threat_lane":<"left" or "right" or null>,"enemy_tower_occupied":<boolean or null>,"reasoning":"<15 words max>"}
Or when waiting is safer: {"action":"wait","observed_elixir":null,"reasoning":"<15 words max>"}"""
    }

    /** Encode the owned snapshot used by this exact decision, with a resolution-scaled ROI. */
    private fun captureArenaImageBase64(frame: Bitmap): String? {
        val roi = Coordinates.getArenaROI(frame.width, frame.height) ?: return null
        return encodeImageCrop(frame, roi, VISION_ARENA_TARGET_W, VISION_ARENA_JPEG_QUALITY)
    }

    /** Preserve HUD digits at higher quality without sending an unrelated second frame. */
    private fun captureHudImageBase64(frame: Bitmap): String? {
        val top = (frame.height * 0.82f).toInt()
        val roi = Coordinates.CardSlotROI(0, top, frame.width, frame.height - top)
        return encodeImageCrop(frame, roi, targetWidthLimit = 720, quality = 85)
    }

    private fun encodeImageCrop(
        frame: Bitmap, roi: Coordinates.CardSlotROI, targetWidthLimit: Int, quality: Int
    ): String? {
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val cropped = Bitmap.createBitmap(frame, roi.x, roi.y, roi.w, roi.h)
            crop = cropped
            val targetWidth = minOf(targetWidthLimit, cropped.width)
            val targetHeight = (cropped.height.toFloat() * targetWidth / cropped.width).toInt().coerceAtLeast(1)
            val encodedBitmap = if (cropped.width != targetWidth) {
                Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
            } else cropped
            scaled = encodedBitmap
            GeminiClient.bitmapToBase64(encodedBitmap, quality = quality)
        } catch (error: Exception) {
            Log.w(TAG, "CMD: Arena image encoding failed (${error.javaClass.simpleName})")
            null
        } finally {
            scaled?.takeIf { it !== frame && it !== crop }?.recycle()
            crop?.takeIf { it !== frame }?.recycle()
        }
    }

    /**
     * Build arena state section from ArenaDetector detections.
     * Returns empty string if no detections available.
     */
    private fun buildArenaSection(): String {
        val detections = ArenaDetector.currentDetections
        if (detections.isEmpty()) return ""

        val items = detections.joinToString(", ") { d ->
            "${d.className} at (${d.centerX}, ${d.centerY}) conf=${String.format("%.0f", d.confidence * 100)}%"
        }
        return "ARENA STATE (enemy troops detected): $items"
    }

    /**
     * Resolve a card name from Gemini response to exact deck card name.
     * Case is harmless; fuzzy matching could turn a hallucinated name into another card.
     */
    private fun resolveCardName(geminiCard: String): String? {
        return deckCards.find { it.equals(geminiCard.trim(), ignoreCase = true) }
    }

    /**
     * Resolve a zone name from Gemini response to a valid ZONE_MAP key.
     */
    private fun resolveZoneName(geminiZone: String): String? {
        return AiDecisionPolicy.normalizeZone(geminiZone)?.takeIf { Coordinates.ZONE_MAP.containsKey(it) }
    }

    // ── Autopilot ─────────────────────────────────────────────────────

    /**
     * Start autopilot mode: AI plays cards automatically every ~4 seconds.
     * Voice commands still work during autopilot (Fast Path overrides).
     */
    fun startAutopilot() {
        if (com.yoyostudios.clashcompanion.strategy.DeckProfiles.matchNames(deckCards) == null) {
            overlay?.updateStatus("AUTO: Importa Ice Bow, Miner Control o el Logbait de Hypno exacto")
            return
        }
        if (!AiConnection.isConfigured()) {
            overlay?.updateStatus("Autopilot: configura Gemini en Clash Commander")
            return
        }
        if (isAutopilot) return
        cancelPendingDecision()
        isAutopilot = true
        overlay?.updateStatus("AUTOPILOT: Activo; esperando una partida verificable")
        overlay?.setAutopilotActive(true)
        autopilotJob = scope.launch {
            while (isActive) {
                delay(AUTOPILOT_INTERVAL_MS)
                if (isBusy || isGestureInFlight) continue
                // Cancelling a child decision for a human command leaves this loop available.
                launchDecision("autopilot", sttLatencyMs = 0, autonomous = true)?.join()
            }
        }
    }

    /** Stops every pending AI decision, including Smart when Auto is already inactive. */
    fun stopAutopilot() {
        autopilotJob?.cancel()
        autopilotJob = null
        isAutopilot = false
        cancelPendingDecision()
        cancelPendingGesture()
        clearQueue()
        clearRules()
        isBusy = false
        overlay?.setAutopilotActive(false)
        overlay?.updateStatus("IA detenida — control manual")
    }

    // ── Targeting Path Execution ──────────────────────────────────────

    /**
     * Execute the Targeting Path: place a spell on a Roboflow-detected enemy troop.
     * e.g. "fireball the hog rider" -> find hog rider on arena -> place fireball there.
     */
    private fun executeTargetingPath(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long
    ) {
        if (cmd.card.isEmpty() || cmd.targetCard == null) {
            overlay?.updateStatus("TARGET: couldn't parse")
            return
        }

        // Look up target in ArenaDetector cache
        val detections = ArenaDetector.currentDetections
        val targetName = cmd.targetCard.lowercase()

        // Fuzzy match detection class name against target card name
        val match = detections.firstOrNull { d ->
            val cls = d.className.lowercase().replace("-", " ").replace("_", " ")
            cls.contains(targetName) || targetName.contains(cls) ||
                    CommandParser.levenshteinSimilarity(cls, targetName) > 0.6f
        }

        if (match == null) {
            val msg = "TARGET: ${cmd.targetCard} not detected on arena"
            overlay?.updateStatus(msg)
            Log.i(TAG, "CMD: $msg (${detections.size} detections available)")
            return
        }

        val targetX = match.centerX.toFloat()
        val targetY = match.centerY.toFloat()
        overlay?.updateStatus("TARGET: ${cmd.card} -> ${cmd.targetCard}")
        executeFastPath(
            cmd.copy(rawTranscript = "TARGET: ${cmd.rawTranscript}"),
            parseMs, sttLatencyMs, rawTargetX = targetX, rawTargetY = targetY
        )
    }

    // ── Conditional Rules Execution ───────────────────────────────────

    /**
     * Execute the Conditional Path: store a rule that triggers when Roboflow
     * detects the specified enemy card.
     * e.g. "whenever hog rider play skeleton army center"
     */
    private fun executeConditionalPath(cmd: CommandParser.ParsedCommand) {
        if (cmd.triggerCard == null || cmd.card.isEmpty()) {
            overlay?.updateStatus("RULE: couldn't parse rule")
            Log.w(TAG, "CMD: Conditional parse incomplete: trigger=${cmd.triggerCard} card=${cmd.card}")
            return
        }

        val zone = cmd.zone ?: CommandParser.getDefaultZone(cmd.card) ?: "center"

        val rule = ConditionalRule(
            triggerCard = cmd.triggerCard,
            responseCard = cmd.card,
            responseZone = zone
        )
        activeRules.add(rule)

        val msg = "RULE SET: if ${cmd.triggerCard} -> ${cmd.card} $zone"
        overlay?.updateStatus(msg)
        Log.i(TAG, "CMD: $msg (${activeRules.size} active rules)")
    }

    /**
     * Check active conditional rules against Roboflow arena detections.
     * Called from OverlayManager when ArenaDetector reports new detections.
     *
     * Debounce: requires 2 consecutive detections before triggering.
     * Fire-once: removes rule after trigger to prevent spam.
     */
    fun checkRules(detections: List<ArenaDetector.Detection>) {
        if (isBusy || activeRules.isEmpty()) return

        // Prune expired rules (>5 minutes old)
        val now = System.currentTimeMillis()
        activeRules.removeAll { now - it.timestamp > 300_000L }

        val triggeredRules = mutableListOf<ConditionalRule>()

        for (rule in activeRules) {
            val triggerName = rule.triggerCard.lowercase()

            // Check if any detection matches the trigger card
            val detected = detections.any { d ->
                val cls = d.className.lowercase().replace("-", " ").replace("_", " ")
                cls.contains(triggerName) || triggerName.contains(cls) ||
                        CommandParser.levenshteinSimilarity(cls, triggerName) > 0.6f
            }

            if (detected) {
                rule.consecutiveFrames++
                if (rule.consecutiveFrames >= 2) {
                    // Debounce passed — check if response card is in hand
                    if (HandDetector.cardToSlot.containsKey(rule.responseCard)) {
                        triggeredRules.add(rule)
                    } else {
                        overlay?.updateStatus("RULE: ${rule.triggerCard} seen but ${rule.responseCard} not in hand")
                    }
                }
            } else {
                rule.consecutiveFrames = 0
            }
        }

        // Execute first triggered rule (one at a time)
        val rule = triggeredRules.firstOrNull() ?: return
        activeRules.remove(rule) // Fire-once

        val msg = "RULE FIRED: ${rule.triggerCard} detected -> ${rule.responseCard} ${rule.responseZone}!"
        overlay?.updateStatus(msg)
        Log.i(TAG, "CMD: $msg")

        executeFastPath(
            CommandParser.ParsedCommand(
                tier = CommandTier.FAST,
                card = rule.responseCard,
                zone = rule.responseZone,
                confidence = 1.0f,
                rawTranscript = "RULE: if ${rule.triggerCard} -> ${rule.responseCard}"
            ),
            parseMs = 0,
            sttLatencyMs = 0
        )
    }

    /**
     * Get display string of active rules for the overlay.
     */
    fun getRulesDisplay(): String {
        if (activeRules.isEmpty()) return ""
        return activeRules.joinToString("\n") { r ->
            "  if ${r.triggerCard} -> ${r.responseCard} ${r.responseZone}"
        }
    }

    /**
     * Clear all active rules.
     */
    fun clearRules() {
        val count = activeRules.size
        activeRules.clear()
        if (count > 0) Log.i(TAG, "CMD: Cleared $count rules")
    }

    // ── Queue Path Execution ─────────────────────────────────────────

    /**
     * Handle a QUEUE-tier command: "queue balloon bridge" / "next hog left" / "then hog left"
     */
    private fun executeQueuePath(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long
    ) {
        if (cmd.card.isEmpty()) {
            overlay?.updateStatus("Queue: couldn't parse card")
            Log.w(TAG, "CMD: Queue parse failed for '${cmd.rawTranscript}'")
            return
        }

        if (cmd.zone == null) {
            val msg = "Where? Say '${cmd.card} left/right/center'"
            overlay?.updateStatus(msg)
            Log.i(TAG, "CMD: Queue needs zone — $msg")
            return
        }

        commandQueue.add(QueuedCommand(cmd.card, cmd.zone))
        Log.i(TAG, "CMD: QUEUED: ${cmd.card} -> ${cmd.zone} (${commandQueue.size} in queue)")

        if (!isBusy && HandDetector.cardToSlot.containsKey(cmd.card)) {
            checkQueue()
        } else {
            overlay?.updateStatus("QUEUED: ${cmd.card} -> ${cmd.zone} (waiting...)")
        }
    }

    /**
     * Check if any queued card has appeared in the player's hand.
     * Called from OverlayManager's onHandChanged callback on the main thread.
     */
    fun checkQueue() {
        if (isBusy || commandQueue.isEmpty()) return
        if (!HandDetector.hasReliableHand(2)) return

        val now = System.currentTimeMillis()

        // Prune expired entries (>2 minutes old)
        commandQueue.removeAll { now - it.timestamp > QUEUE_TIMEOUT_MS }
        if (commandQueue.isEmpty()) return

        val handSlots = HandDetector.cardToSlot

        // Remove entries where card has been absent for >5s after attempt
        commandQueue.removeAll { q ->
            q.lastAttemptMs > 0
                    && !handSlots.containsKey(q.card)
                    && now - q.lastAttemptMs > QUEUE_PLAYED_GRACE_MS
        }
        if (commandQueue.isEmpty()) return

        for (queued in commandQueue) {
            if (!handSlots.containsKey(queued.card)) continue
            if (queued.lastAttemptMs > 0 && now - queued.lastAttemptMs < QUEUE_RETRY_COOLDOWN_MS) continue

            val isRetry = queued.lastAttemptMs > 0
            queued.lastAttemptMs = now
            val waitMs = now - queued.timestamp

            Log.i(TAG, "CMD: Queue ${if (isRetry) "retry" else "triggered"} — " +
                    "${queued.card} in hand (waited ${waitMs}ms)")

            val msg = "QUEUE: ${queued.card} -> ${queued.zone}" +
                    if (isRetry) " (retry)" else ""
            overlay?.updateStatus(msg)

            executeFastPath(
                CommandParser.ParsedCommand(
                    tier = CommandTier.FAST,
                    card = queued.card,
                    zone = queued.zone,
                    confidence = 1.0f,
                    rawTranscript = "QUEUE auto-play"
                ),
                parseMs = 0,
                sttLatencyMs = waitMs
            )
            return
        }
    }

    fun clearQueue() {
        val count = commandQueue.size
        commandQueue.clear()
        if (count > 0) {
            overlay?.updateStatus("Queue cleared ($count removed)")
            Log.i(TAG, "CMD: Queue cleared ($count entries)")
        }
    }

    fun getQueueDisplay(): String {
        if (commandQueue.isEmpty()) return ""
        return commandQueue.joinToString("\n") { q ->
            val ago = (System.currentTimeMillis() - q.timestamp) / 1000
            "  ${q.card} -> ${q.zone} (${ago}s)"
        }
    }

    // ── Fast Path Execution ───────────────────────────────────────────

    /**
     * Execute the Fast Path: tap card slot, then tap zone.
     * Also used by Smart Path, Queue Path, Targeting, and Rules as final executor.
     *
     * Accepts optional raw pixel coordinates for targeting (overrides zone lookup).
     */
    private fun executeFastPath(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long,
        rawTargetX: Float? = null,
        rawTargetY: Float? = null
    ) {
        if (isGestureInFlight) return
        val card = cmd.card
        val zone = cmd.zone

        // Confidence gate
        val threshold = CommandParser.getConfidenceThreshold(card)
        if (cmd.confidence < threshold) {
            val pct = (cmd.confidence * 100).toInt()
            val msg = "Low confidence: $card? ($pct% < ${(threshold * 100).toInt()}%)"
            overlay?.updateStatus(msg)
            Log.w(TAG, "CMD: $msg")
            isBusy = false
            return
        }

        // Do not tap stale card slots while recording an unrelated app.
        if (!HandDetector.hasReliableHand(1)) {
            overlay?.updateStatus("No veo la mano. Pulsa Img para guardar diagnostico")
            isBusy = false
            return
        }

        // Check card is in hand via classifier
        val slotIndex = HandDetector.cardToSlot[card]
        if (slotIndex == null) {
            val handCards = HandDetector.currentHand
                .filterKeys { it < 4 }
                .entries.sortedBy { it.key }
                .joinToString { it.value }
            val msg = "Not in hand: $card (hand: $handCards)"
            overlay?.updateStatus(msg)
            Log.w(TAG, "CMD: $msg")
            isBusy = false
            return
        }

        if (slotIndex < 0 || slotIndex >= Coordinates.CARD_SLOTS.size) {
            val msg = "ERROR: Invalid slot index $slotIndex for $card"
            overlay?.updateStatus(msg)
            Log.e(TAG, "CMD: $msg")
            isBusy = false
            return
        }

        val (slotX, slotY) = Coordinates.CARD_SLOTS[slotIndex]

        // Zone coordinates — use raw target if provided, otherwise look up zone map
        val zoneX: Float
        val zoneY: Float
        if (rawTargetX != null && rawTargetY != null) {
            zoneX = rawTargetX
            zoneY = rawTargetY
        } else {
            if (zone == null) {
                val msg = "ERROR: No zone for $card"
                overlay?.updateStatus(msg)
                Log.e(TAG, "CMD: $msg")
                isBusy = false
                return
            }
            val zoneCoords = Coordinates.ZONE_MAP[zone]
            if (zoneCoords == null) {
                val msg = "ERROR: Unknown zone '$zone'"
                overlay?.updateStatus(msg)
                Log.e(TAG, "CMD: $msg")
                isBusy = false
                return
            }
            zoneX = zoneCoords.first
            zoneY = zoneCoords.second
        }

        // Execute!
        val svc = ClashCompanionAccessibilityService.instance
        if (svc == null) {
            val msg = "ERROR: Accessibility service not connected"
            overlay?.updateStatus(msg)
            Log.e(TAG, "CMD: $msg")
            isBusy = false
            return
        }

        isBusy = true
        isGestureInFlight = true
        val thisGesture = ++gestureRevision
        val gestureMatch = HandDetector.matchSessionId
        val gestureCapture = ScreenCaptureService.currentCaptureSessionId()

        val totalMs = parseMs + sttLatencyMs
        val displayZone = zone ?: "(${zoneX.toInt()},${zoneY.toInt()})"
        if (!cmd.rawTranscript.startsWith("SMART:") &&
            !cmd.rawTranscript.startsWith("AUTOPILOT:") &&
            !cmd.rawTranscript.startsWith("QUEUE") &&
            !cmd.rawTranscript.startsWith("RULE:")) {
            val msg = "FAST: $card -> $displayZone | ${totalMs}ms"
            overlay?.updateStatus(msg)
        }
        Log.i(TAG, "CMD: PLAY $card slot=$slotIndex ($slotX,$slotY) -> ($zoneX,$zoneY) ${totalMs}ms")

        // Make overlay transparent to touches so dispatchGesture doesn't hit overlay
        overlay?.setPassthrough(true)
        svc.playCard(slotX, slotY, zoneX, zoneY) { completed ->
            if (thisGesture != gestureRevision) return@playCard
            if (completed && gestureMatch == HandDetector.matchSessionId &&
                gestureCapture == ScreenCaptureService.currentCaptureSessionId()) {
                lastPlayedCard = card
                lastPlayedZone = zone
                lastPlayedTimeMs = System.currentTimeMillis()
            } else if (!completed) {
                overlay?.updateStatus("Gesto cancelado; jugada no confirmada")
            }
            overlay?.setPassthrough(false)
            isGestureInFlight = false
            isBusy = activeDecisionJob?.isActive == true
        }
    }

    /**
     * Clean up resources. Call when app is being destroyed.
     */
    fun destroy() {
        stopAutopilot()
        scope.cancel()
    }
}
