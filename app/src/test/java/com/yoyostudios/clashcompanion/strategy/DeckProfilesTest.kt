package com.yoyostudios.clashcompanion.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckProfilesTest {

    @Test
    fun iceBowMatchesRegardlessOfOrderAndPunctuation() {
        val shuffled = listOf(
            "Rocket", "the log", "ICE-WIZARD", "Tesla",
            "Skeletons", "Knight", "X Bow", "Tornado"
        )
        assertEquals("ice_bow", DeckProfiles.matchNames(shuffled)?.id)
    }

    @Test
    fun minerControlMatchesOnlyItsEightCards() {
        val eight = listOf(
            "Miner", "Poison", "Wall Breakers", "Bats",
            "Knight", "Spear Goblins", "Bomb Tower", "The Log"
        )
        assertEquals("miner_control", DeckProfiles.matchNames(eight)?.id)
        assertNull(DeckProfiles.matchNames(eight.dropLast(1)))
    }

    @Test
    fun hypnoVariantIsWallBreakerBaitWithoutLog() {
        val deck = listOf(
            "Cannon", "Dart Goblin", "Goblin Barrel", "Ice Spirit",
            "Princess", "Skeleton Army", "Valkyrie", "Wall Breakers"
        )
        val profile = DeckProfiles.matchNames(deck)
        assertNotNull(profile)
        assertEquals("hypno_wb_bait", profile?.id)
        assertFalse(profile!!.cardNames.contains("The Log"))
        assertTrue(DeckProfiles.promptFor(deck).contains("VERIFIED DECK PROFILE"))
    }

    @Test
    fun rejectsPartialDuplicateOrUnknownDecks() {
        val deck = DeckProfiles.iceBow.cardNames.toList()
        assertNull(DeckProfiles.matchNames(deck.take(7)))
        assertNull(DeckProfiles.matchNames(deck.dropLast(1) + "Knight"))
        assertNull(DeckProfiles.matchNames(deck.dropLast(1) + "Wizard"))
    }

    @Test
    fun profilesHaveEightDistinctCardsAndNeverGuessForUnknown() {
        assertEquals(3, DeckProfiles.all.size)
        assertTrue(DeckProfiles.all.all { it.cardNames.size == 8 })
        assertTrue(DeckProfiles.promptFor(listOf("Royal Ghost")).contains("NO VERIFIED"))
    }
}
