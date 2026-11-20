package com.yoyostudios.clashcompanion.strategy

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Versioned, deck-bound strategy documents; independent of Android storage and network. */
object PlaybookPolicy {
    fun deckKey(ids: List<Long>): String = ids.sorted().joinToString(",")

    fun validate(playbook: String, cardNames: Set<String>): JsonObject {
        require(cardNames.size == 8) { "La estrategia requiere un mazo completo" }
        val root = JsonParser.parseString(playbook)
        require(root.isJsonObject) { "Estrategia JSON invalida" }
        val json = root.asJsonObject
        fun meaningful(value: JsonElement?): Boolean = value != null && !value.isJsonNull &&
            ((value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) ||
                (value.isJsonObject && value.asJsonObject.size() > 0))
        require(meaningful(json.get("archetype"))) { "Falta el arquetipo" }
        val wins = json.get("win_conditions")
        require(wins != null && wins.isJsonArray && wins.asJsonArray.size() > 0) { "Faltan condiciones de victoria" }
        for (win in wins.asJsonArray) {
            require(win.isJsonObject) { "Condicion de victoria invalida" }
            val card = win.asJsonObject.get("card")
            require(card != null && card.isJsonPrimitive && card.asJsonPrimitive.isString &&
                card.asString in cardNames) { "Carta de estrategia ajena al mazo" }
            require(meaningful(win.asJsonObject.get("strategy"))) { "Falta plan de victoria" }
        }
        val defense = json.get("defense_table")
        require(defense != null && defense.isJsonObject) { "Falta tabla defensiva" }
        for ((_, counter) in defense.asJsonObject.entrySet()) {
            require(counter.isJsonObject) { "Respuesta defensiva invalida" }
            val card = counter.asJsonObject.get("counter_card")
            require(card != null && card.isJsonPrimitive && card.asJsonPrimitive.isString &&
                card.asString in cardNames) { "Contraataque ajeno al mazo" }
        }
        for (field in listOf("offense_plays", "synergies", "never_do")) {
            require(json.get(field)?.isJsonArray == true) { "Seccion invalida: $field" }
        }
        require(json.getAsJsonArray("offense_plays").size() > 0 &&
            json.getAsJsonArray("never_do").size() > 0) { "Estrategia incompleta" }
        val defaults = json.get("card_placement_defaults")
        require(defaults != null && defaults.isJsonObject &&
            defaults.asJsonObject.keySet() == cardNames &&
            defaults.asJsonObject.entrySet().all { meaningful(it.value) }) { "Faltan posiciones del mazo" }
        require(meaningful(json.get("elixir_management"))) { "Falta gestion de elixir" }
        return json
    }

    fun encode(deckKey: String, playbook: String, cardNames: Set<String>): String = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("deck_key", deckKey)
        add("playbook", validate(playbook, cardNames))
    }.toString()

    fun decode(document: String, expectedDeck: String, cardNames: Set<String>): String? = try {
        val envelope = JsonParser.parseString(document).asJsonObject
        val version = envelope.get("schema_version")
        val key = envelope.get("deck_key")
        if (version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isNumber ||
            version.asBigDecimal.intValueExact() != 1 || key == null ||
            !key.isJsonPrimitive || !key.asJsonPrimitive.isString || key.asString != expectedDeck) null
        else envelope.get("playbook")?.toString()?.let { validate(it, cardNames).toString() }
    } catch (_: Exception) { null }

    fun requestIsCurrent(epoch: Long, currentEpoch: Long, deck: String, currentDeck: String): Boolean =
        epoch == currentEpoch && deck == currentDeck
}
