package com.yoyostudios.clashcompanion.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshHandAuthorizationTest {
    @Test fun rejectsSmoothedCardsMissingFromCurrentFrame() {
        val display = mapOf(0 to "Tesla", 1 to "Knight", 2 to "Skeletons", 3 to "Rocket")
        val observed = mapOf(0 to "Tesla", 2 to "Skeletons")
        assertEquals(2, FreshHandAuthorization.verifiedSlots(observed, display))
    }

    @Test fun acceptsFourDistinctFreshCards() {
        val hand = mapOf(0 to "Tesla", 1 to "Knight", 2 to "Skeletons", 3 to "Rocket")
        assertEquals(4, FreshHandAuthorization.verifiedSlots(hand, hand))
    }

    @Test fun rejectsDuplicateAndNonHandSlots() {
        val observed = mapOf(0 to "Tesla", 1 to "Tesla", 2 to "Knight", 4 to "Rocket")
        assertEquals(2, FreshHandAuthorization.verifiedSlots(observed, observed))
    }

    @Test fun rejectsChangedSlotAndBlankCard() {
        val observed = mapOf(0 to "Tesla", 1 to "", 2 to "Knight")
        val displayed = mapOf(0 to "Rocket", 1 to "", 2 to "Knight")
        assertEquals(1, FreshHandAuthorization.verifiedSlots(observed, displayed))
    }
}
