package com.yoyostudios.clashcompanion.command

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Bounded context for live decisions; the full analysis stays in the strategy cache. */
object TacticalPromptBrief {
    fun fromPlaybook(raw: String?, hand: Set<String>): String {
        if (raw.isNullOrBlank()) return "No cached strategy"
        return try {
            val source = JsonParser.parseString(raw).asJsonObject
            val brief = JsonObject()
            source.get("archetype")?.let { brief.addProperty("archetype", it.toString().take(160)) }
            source.get("win_conditions")?.takeIf { it.isJsonArray }?.let { wins ->
                brief.add("win_conditions", JsonArray().apply {
                    wins.asJsonArray.take(2).forEach { add(it.toString().take(180)) }
                })
            }
            source.get("card_placement_defaults")?.takeIf { it.isJsonObject }?.let { defaults ->
                brief.add("hand_placements", JsonObject().apply {
                    hand.sorted().take(4).forEach { name ->
                        defaults.asJsonObject.get(name)?.let { addProperty(name, it.toString().take(120)) }
                    }
                })
            }
            source.get("never_do")?.takeIf { it.isJsonArray }?.let { rules ->
                brief.add("avoid", JsonArray().apply {
                    rules.asJsonArray.take(3).forEach { add(it.toString().take(100)) }
                })
            }
            brief.toString()
        } catch (_: Exception) { "No valid cached strategy" }
    }
}
