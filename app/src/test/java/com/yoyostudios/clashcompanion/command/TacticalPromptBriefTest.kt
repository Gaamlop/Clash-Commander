package com.yoyostudios.clashcompanion.command

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TacticalPromptBriefTest {
    @Test fun largePlaybookKeepsOnlySmallHandRelevantContext() {
        val raw = """{"archetype":"bait","win_conditions":[{"card":"Goblin Barrel","strategy":"pressure"}],"defense_table":{"x":"${"large".repeat(20000)}"},"card_placement_defaults":{"Goblin Barrel":"left tower","Valkyrie":"center","Rocket":"right tower"},"never_do":["do not overcommit"]}"""
        val result = TacticalPromptBrief.fromPlaybook(raw, setOf("Goblin Barrel","Valkyrie"))
        assertTrue(result.length < 1800)
        val brief = JsonParser.parseString(result).asJsonObject
        assertFalse(brief.has("defense_table"))
        assertTrue(brief.getAsJsonObject("hand_placements").has("Valkyrie"))
        assertFalse(brief.getAsJsonObject("hand_placements").has("Rocket"))
        assertTrue(result.contains("do not overcommit"))
    }
    @Test fun malformedOrAbsentPlaybookDoesNotStopLiveLoop() {
        assertEquals("No cached strategy", TacticalPromptBrief.fromPlaybook(null, emptySet()))
        assertEquals("No valid cached strategy", TacticalPromptBrief.fromPlaybook("broken", emptySet()))
    }
    @Test fun longFieldsStayBoundedAndProduceValidJson() {
        val long = "x".repeat(10000)
        val raw = """{"archetype":"$long","win_conditions":["$long","$long","$long"],"never_do":["$long","$long","$long","$long"]}"""
        val result=TacticalPromptBrief.fromPlaybook(raw, emptySet())
        assertTrue(result.length < 1400)
        assertTrue(JsonParser.parseString(result).isJsonObject)
    }
}
