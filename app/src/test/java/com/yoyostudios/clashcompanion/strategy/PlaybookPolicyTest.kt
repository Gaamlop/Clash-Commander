package com.yoyostudios.clashcompanion.strategy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PlaybookPolicyTest {
    private val names = (0..7).map { "Card $it" }.toSet()
    private fun playbook(): String {
        val value = JsonParser.parseString("""{
          "archetype":"Control",
          "win_conditions":[{"card":"Card 0","strategy":"Defend then counterpush"}],
          "defense_table":{"Threat":{"counter_card":"Card 1"}},
          "offense_plays":[{"sequence":"Counterpush after defending"}],
          "synergies":[],"never_do":["Do not overcommit"],
          "elixir_management":"Wait when available elixir is unknown"
        }""").asJsonObject
        value.add("card_placement_defaults", JsonObject().apply {
            names.forEach { addProperty(it, "Choose from legal zones using the arena") }
        })
        return value.toString()
    }

    @Test fun deckIdentityIgnoresOrderingButNotReplacement() {
        assertEquals(PlaybookPolicy.deckKey(listOf(1, 2, 3)), PlaybookPolicy.deckKey(listOf(3, 1, 2)))
        assertNotEquals(PlaybookPolicy.deckKey(listOf(1, 2, 3)), PlaybookPolicy.deckKey(listOf(1, 2, 4)))
    }

    @Test fun restoresOnlyVersionedValidatedCacheForSameDeck() {
        val encoded = PlaybookPolicy.encode("deck-a", playbook(), names)
        assertNotNull(PlaybookPolicy.decode(encoded, "deck-a", names))
        assertNull(PlaybookPolicy.decode(encoded, "deck-b", names))
        assertNull(PlaybookPolicy.decode(playbook(), "deck-a", names))
        assertNull(PlaybookPolicy.decode("broken", "deck-a", names))
    }

    @Test fun rejectsIncompleteOrForeignCardStrategy() {
        assertThrows(RuntimeException::class.java) { PlaybookPolicy.validate("{}", names) }
        assertThrows(RuntimeException::class.java) {
            PlaybookPolicy.validate(playbook().replace("Card 0", "Foreign card"), names)
        }
        val incomplete = JsonParser.parseString(playbook()).asJsonObject
        incomplete.getAsJsonObject("card_placement_defaults").remove("Card 7")
        assertThrows(RuntimeException::class.java) { PlaybookPolicy.validate(incomplete.toString(), names) }
    }

    @Test fun doesNotAcceptAnOldResponseAfterDeckReturnsToSameIds() {
        assertTrue(PlaybookPolicy.requestIsCurrent(5, 5, "deck-a", "deck-a"))
        assertFalse(PlaybookPolicy.requestIsCurrent(5, 7, "deck-a", "deck-a"))
        assertFalse(PlaybookPolicy.requestIsCurrent(5, 5, "deck-a", "deck-b"))
    }
}
