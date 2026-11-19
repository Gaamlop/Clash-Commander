package com.yoyostudios.clashcompanion.detection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Local card recognition; smoothed display values never authorize a tap. */
object HandDetector {
    private const val TAG = "ClashCompanion"
    private const val SCAN_INTERVAL_MS = 200L
    private const val MATCH_BRIGHTNESS_THRESHOLD = 80
    private const val MATCH_EXIT_FRAMES = 10
    private const val MAX_HAND_AGE_MS = 2_000L
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scanMutex = Mutex()
    private val scanGeneration = AtomicLong()
    private val matchGeneration = AtomicLong()
    private var scanJob: Job? = null

    @Volatile
    private var recognition = HandRecognitionState()

    /** Display-only hand, including a short grace period for missed detections. */
    val currentHand: Map<Int, String> get() = recognition.displayHand

    /** Only cards actually recognized in the latest fresh frame of this capture session. */
    val currentVerifiedHand: Map<Int, String>
        get() = recognition.verifiedHand(
            SystemClock.elapsedRealtime(), ScreenCaptureService.currentCaptureSessionId(), MAX_HAND_AGE_MS
        )

    /** Changes across match transitions, capture renewal/loss, and scanner restart. */
    val matchSessionId: Long get() = recognition.matchSessionId

    val cardToSlot: Map<String, Int>
        get() = currentVerifiedHand.filterKeys { it in 0..3 }.entries.associate { (slot, card) -> card to slot }
    val nextCard: String? get() = currentVerifiedHand[4]
    val isCalibrated: Boolean get() = CardClassifier.isReady
    val templateCount: Int get() = if (CardClassifier.isReady) 8 else 0
    val calibratedCards: Set<String> get() = currentHand.values.toSet()

    @Volatile
    var diagnosticStatus: String = "Esperando primera captura"
        private set

    fun hasReliableHand(minCards: Int = 3): Boolean =
        minCards in 1..4 && currentVerifiedHand.filterKeys { it in 0..3 }.values.distinct().size >= minCards

    private fun clearObservation() {
        recognition = HandRecognitionState(matchSessionId = matchGeneration.incrementAndGet())
    }

    // This brightness heuristic is deliberately conservative. It does not prove
    // that card art or a previously unseen device layout is recognized correctly.
    private fun isInMatch(frame: Bitmap): Boolean {
        val rois = Coordinates.getCardSlotROIs(frame.width, frame.height)
        return listOf(rois[0], rois[2]).all { slotBrightness(frame, it) > MATCH_BRIGHTNESS_THRESHOLD }
    }

    private fun slotBrightness(frame: Bitmap, roi: Coordinates.CardSlotROI): Long {
        val fx = roi.x.coerceIn(0, frame.width - 1)
        val fy = roi.y.coerceIn(0, frame.height - 1)
        val fw = roi.w.coerceAtMost(frame.width - fx)
        val fh = roi.h.coerceAtMost(frame.height - fy)
        if (fw <= 0 || fh <= 0) return 0L
        val pixels = IntArray(fw * fh)
        frame.getPixels(pixels, 0, fw, fx, fy, fw, fh)
        var sum = 0L
        for (pixel in pixels) {
            sum += ((pixel shr 16) and 255) + ((pixel shr 8) and 255) + (pixel and 255)
        }
        return sum / (pixels.size * 3)
    }

    @Suppress("UNUSED_PARAMETER")
    fun startScanning(
        deckCards: List<String>,
        context: Context,
        onHandChanged: ((Map<Int, String>) -> Unit)? = null
    ) {
        if (scanJob?.isActive == true) return
        val generation = scanGeneration.incrementAndGet()
        clearObservation()
        diagnosticStatus = "Iniciando detector de cartas"
        scanJob = scope.launch {
            // Native model inference is not cancellable midway. A restarted scanner
            // waits for its predecessor before touching the shared model buffers.
            scanMutex.withLock {
                if (generation != scanGeneration.get()) return@withLock
                scan(deckCards, generation, onHandChanged)
            }
        }
    }

    private suspend fun scan(
        deckCards: List<String>,
        generation: Long,
        onHandChanged: ((Map<Int, String>) -> Unit)?
    ) {
        var matchDetected = false
        var consecutiveNonMatch = 0
        var nullFrames = 0
        var captureSession = -1L
        var previousFrameId = -1L
        var lastHand = emptyMap<Int, String>()
        var lastDetected = emptyMap<Int, String>()
        var misses = emptyMap<Int, Int>()
        while (currentCoroutineContext().isActive && generation == scanGeneration.get()) {
            val snapshot = ScreenCaptureService.copyLatestSnapshot(MAX_HAND_AGE_MS)
            if (snapshot == null) {
                if (nullFrames++ == 0) {
                    clearObservation()
                    matchDetected = false
                    consecutiveNonMatch = 0
                    captureSession = -1L
                    lastHand = emptyMap()
                    lastDetected = emptyMap()
                    misses = emptyMap()
                    onHandChanged?.invoke(emptyMap())
                }
                diagnosticStatus = "Captura sin imagen reciente; renueva el permiso de pantalla"
                delay(SCAN_INTERVAL_MS)
                continue
            }
            nullFrames = 0
            val frame = snapshot.bitmap
            try {
                if (captureSession != snapshot.captureSessionId) {
                    clearObservation()
                    captureSession = snapshot.captureSessionId
                    previousFrameId = -1L
                    matchDetected = false
                    consecutiveNonMatch = 0
                    lastHand = emptyMap()
                    lastDetected = emptyMap()
                    misses = emptyMap()
                }
                if (snapshot.frameId != previousFrameId) {
                    previousFrameId = snapshot.frameId
                    if (!isInMatch(frame)) {
                        if (matchDetected && consecutiveNonMatch == 0) {
                            // Invalidate in-flight decisions immediately, even though
                            // the display tolerates brief transitions/animations.
                            recognition = recognition.copy(
                                detectedHand = emptyMap(), matchSessionId = matchGeneration.incrementAndGet()
                            )
                        }
                        if (matchDetected) consecutiveNonMatch++
                        diagnosticStatus = "No veo la arena. Comparte PANTALLA COMPLETA"
                        if (consecutiveNonMatch >= MATCH_EXIT_FRAMES) {
                            clearObservation()
                            matchDetected = false
                            consecutiveNonMatch = 0
                            lastHand = emptyMap()
                            lastDetected = emptyMap()
                            misses = emptyMap()
                            onHandChanged?.invoke(emptyMap())
                        }
                    } else {
                        if (!matchDetected || consecutiveNonMatch > 0) {
                            clearObservation()
                            matchDetected = true
                        }
                        consecutiveNonMatch = 0
                        Coordinates.updateFromCapture(frame.width, frame.height)
                        val startedAt = SystemClock.elapsedRealtime()
                        val newScan = CardClassifier.classifyHand(frame, deckCards)
                        val merged = HandStateReducer.merge(lastHand, newScan, misses)
                        currentCoroutineContext().ensureActive()
                        if (generation != scanGeneration.get() ||
                            ScreenCaptureService.currentCaptureSessionId() != captureSession
                        ) continue
                        recognition = HandRecognitionState(
                            merged.hand, merged.detected, snapshot.frameId, captureSession,
                            snapshot.capturedAtElapsedRealtime, recognition.matchSessionId
                        )
                        misses = merged.misses
                        val count = merged.detected.keys.count { it in 0..3 }
                        diagnosticStatus = "Vision: $count/4 verificadas (${frame.width}x${frame.height})" +
                            if (count < 3) ". Pulsa Img para revisar captura" else ""
                        if (merged.hand != lastHand || merged.detected != lastDetected) {
                            onHandChanged?.invoke(merged.hand)
                            Log.i(TAG, "CARD-ML: $count verified cards (${SystemClock.elapsedRealtime() - startedAt}ms)")
                        }
                        lastHand = merged.hand
                        lastDetected = merged.detected
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recognition = recognition.copy(detectedHand = emptyMap())
                diagnosticStatus = "No se pudo verificar la mano; revisa captura"
                Log.w(TAG, "CARD-ML: Scan rejected (${e.javaClass.simpleName})")
            } finally {
                frame.recycle()
            }
            delay(SCAN_INTERVAL_MS)
        }
    }

    fun stopScanning() {
        scanGeneration.incrementAndGet()
        scanJob?.cancel()
        scanJob = null
        clearObservation()
        diagnosticStatus = "Escaner detenido"
    }

    val isScanning: Boolean get() = scanJob?.isActive == true

    fun destroy() {
        stopScanning()
        scope.cancel()
    }
}
