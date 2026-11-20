package com.yoyostudios.clashcompanion.local

/** Single outstanding play; no repeated taps until a later observation confirms or times out. */
class LocalAutopilotSession {
    data class Pending(val action: LocalAction.Play, val before: LocalBattle, val issuedAtMs: Long)
    var pending: Pending? = null; private set
    var confirmed = 0; private set
    var unconfirmed = 0; private set
    private var match: Pair<Long, Long>? = null
    private var nextPlayAt = 0L
    private var lastFrame = -1L
    private val threats = ThreatMemory()
    private var pressure: HypnoPolicy.Pressure? = null
    private var pendingAbility: Pair<LocalBattle, Long>? = null
    private var lastButton: HeroButton? = null
    private var lastButtonFrame = -1L
    private var buttonFrames = 0
    private var buttonUsed = false
    var confirmedAbilities = 0; private set

    fun reset() {
        pending = null; pendingAbility = null; match = null; nextPlayAt = 0; lastFrame = -1
        confirmed = 0; unconfirmed = 0; confirmedAbilities = 0; threats.clear(); pressure = null
        lastButton = null; lastButtonFrame = -1; buttonFrames = 0; buttonUsed = false
    }

    fun consider(battle: LocalBattle, nowMs: Long): LocalAction {
        val identity = battle.captureSession to battle.matchSession
        if (match != identity) { reset(); match = identity }
        if (battle.frameId != lastButtonFrame) {
            lastButtonFrame = battle.frameId
            val button = battle.board.heroButton
            buttonFrames = if (button != null && lastButton != null &&
                kotlin.math.abs(button.x - lastButton!!.x) < .04f && kotlin.math.abs(button.y - lastButton!!.y) < .03f)
                buttonFrames + 1 else if (button != null) 1 else 0
            if (button == null && pendingAbility == null) buttonUsed = false
            lastButton = button
        }
        pendingAbility?.let { (before, issuedAt) ->
            val spent = before.elixir?.let { start -> battle.elixir?.let { it <= start - 2 } } == true
            if (battle.frameId > before.frameId && battle.capturedAtMs > issuedAt &&
                battle.board.heroButton == null && spent) {
                confirmedAbilities++; pendingAbility = null; nextPlayAt = nowMs + 350
            } else {
                if (nowMs - issuedAt < 3_500) return LocalAction.Wait("Comprobando habilidad y gasto de tres elixir")
                pendingAbility = null; unconfirmed++; nextPlayAt = nowMs + 1_000
                return LocalAction.Wait("Habilidad sin confirmar; conservar control visual")
            }
        }
        pending?.let { p ->
            if (battle.frameId > p.before.frameId && battle.capturedAtMs > p.issuedAtMs) {
                val changed = battle.hand[p.action.slot]?.let { it != p.action.card } == true
                val spent = p.before.elixir?.let { before -> battle.elixir?.let { after ->
                    after <= before - HypnoPolicy.costs.getValue(p.action.card) + 1
                } } == true
                // Different card in the same slot proves rotation; a missing/animated slot alone does not.
                if (changed && spent) {
                    confirmed++; pending = null
                    if (!p.action.defensive && p.action.card in setOf("Goblin Barrel", "Wall Breakers"))
                        pressure = HypnoPolicy.Pressure(p.action.card, p.action.x < .5f, nowMs)
                    nextPlayAt = nowMs + if (p.action.defensive) 700 else 300
                }
            }
            if (pending != null) {
                if (nowMs - p.issuedAtMs < 3_500) return LocalAction.Wait("Comprobando rotacion de carta y gasto de elixir")
                unconfirmed++; pending = null; nextPlayAt = nowMs + 1_500
                return LocalAction.Wait("Jugada sin confirmar; observar antes de reintentar")
            }
        }
        if (nowMs < nextPlayAt) return LocalAction.Wait("Esperando resultado de la defensa")
        if (battle.frameId == lastFrame) return LocalAction.Wait("Esperando una observacion nueva")
        lastFrame = battle.frameId
        val observed = threats.observe(battle)
        val ready = if (buttonFrames >= 2 && !buttonUsed) observed
            else observed.copy(board = observed.board.copy(heroButton = null))
        return HypnoPolicy.decide(ready, nowMs, pressure)
    }

    fun issued(action: LocalAction.Play, before: LocalBattle, completedAtMs: Long) {
        check(pending == null)
        pending = Pending(action, before, completedAtMs)
    }

    fun abilityIssued(before: LocalBattle, completedAtMs: Long) {
        check(pending == null && pendingAbility == null)
        buttonUsed = true
        pendingAbility = before to completedAtMs
    }

    fun gestureFailed(nowMs: Long) { pending = null; pendingAbility = null; unconfirmed++; nextPlayAt = nowMs + 1_000 }
    fun manualOverride(nowMs: Long) { pending = null; pendingAbility = null; pressure = null; nextPlayAt = nowMs + 2_000 }
}
