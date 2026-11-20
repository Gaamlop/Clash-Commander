package com.yoyostudios.clashcompanion.command

import com.google.gson.JsonParser

/** Immutable identity of the observations used for one tactical request. */
data class AiDecisionContext(
    val revision: Long,
    val requestedAtElapsedRealtime: Long,
    val frameId: Long,
    val capturedAtElapsedRealtime: Long,
    val captureSessionId: Long,
    val matchSessionId: Long,
    val deckKey: String
)

sealed class TacticalDecision {
    data class Wait(val reasoning: String) : TacticalDecision()
    data class Play(
        val card: String, val zone: String, val reasoning: String,
        val observedElixir: Int? = null,
        val board: ArchetypePolicy.Board = ArchetypePolicy.Board()
    ) : TacticalDecision()
}

/** Pure policy so cancellation, stale frames, and malformed actions can be tested on the JVM. */
object AiDecisionPolicy {
    const val MAX_AGE_MS = 6_000L
    private val zones = setOf(
        "left_bridge", "right_bridge", "center", "behind_left", "behind_right",
        "back_center", "front_left", "front_right", "left tower", "right tower"
    )

    fun normalizeZone(input: String): String? {
        val normalized = input.trim().lowercase().replace(' ', '_')
        val zone = if (normalized in setOf("left_tower", "right_tower")) {
            normalized.replace('_', ' ')
        } else normalized
        return zone.takeIf { it in zones }
    }

    fun isLegalPlacement(card: String, type: String, zone: String): Boolean =
        zone in zones && (zone !in setOf("left tower", "right tower") ||
            type.equals("Spell", ignoreCase = true) || card in setOf("Miner", "Goblin Drill"))

    fun isCurrent(
        decision: AiDecisionContext,
        revision: Long,
        nowElapsedRealtime: Long,
        captureSessionId: Long?,
        latestFrameId: Long?,
        matchSessionId: Long,
        deckKey: String
    ): Boolean = decision.revision == revision &&
        decision.captureSessionId == captureSessionId &&
        latestFrameId != null && latestFrameId >= decision.frameId &&
        decision.matchSessionId == matchSessionId && decision.deckKey == deckKey &&
        nowElapsedRealtime - decision.requestedAtElapsedRealtime in 0..MAX_AGE_MS &&
        nowElapsedRealtime - decision.capturedAtElapsedRealtime in 0..MAX_AGE_MS

    fun parse(json: String): TacticalDecision {
        val element = JsonParser.parseString(json)
        require(element.isJsonObject) { "La decision debe ser un objeto JSON" }
        val value = element.asJsonObject
        fun text(name: String, required: Boolean = true): String {
            val field = value.get(name)
            if (field == null && !required) return ""
            require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString) {
                "Campo de decision invalido: $name"
            }
            return field.asString.trim().also {
                require(!required || it.isNotBlank()) { "Campo de decision vacio: $name" }
            }
        }
        val reasoning = text("reasoning", required = false).take(160)
        val elixirField = value.get("observed_elixir")
        val observedElixir = if (elixirField == null || elixirField.isJsonNull) null else {
            require(elixirField.isJsonPrimitive && elixirField.asJsonPrimitive.isNumber) {
                "Elixir observado debe ser un entero o null"
            }
            val amount = elixirField.asBigDecimal.intValueExact()
            require(amount in 0..10) { "Elixir observado fuera de rango" }
            amount
        }
        fun optionalBoolean(name: String): Boolean? {
            val field = value.get(name) ?: return null
            if (field.isJsonNull) return null
            require(field.isJsonPrimitive && field.asJsonPrimitive.isBoolean) { "Estado booleano invalido" }
            return field.asBoolean
        }
        val lane = value.get("threat_lane")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString in setOf("left", "right"))
            it.asString
        }
        val board = ArchetypePolicy.Board(optionalBoolean("immediate_threat"), lane, optionalBoolean("enemy_tower_occupied"))
        return when (text("action")) {
            "wait" -> TacticalDecision.Wait(reasoning)
            "play" -> TacticalDecision.Play(text("card"), text("zone"), reasoning, observedElixir, board)
            else -> throw IllegalArgumentException("Accion de IA desconocida")
        }
    }

    /** This only checks a visual inference supplied by the model, not a calibrated sensor. */
    fun canAfford(cardCost: Int?, observedElixir: Int?, requireObservation: Boolean): Boolean {
        if (observedElixir != null && observedElixir !in 0..10) return false
        if (cardCost != null && cardCost !in 0..10) return false
        if (observedElixir == null || cardCost == null) return !requireObservation
        return cardCost <= observedElixir
    }
}
