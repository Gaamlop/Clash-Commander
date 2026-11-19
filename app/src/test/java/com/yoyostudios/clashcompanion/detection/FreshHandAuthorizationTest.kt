package com.yoyostudios.clashcompanion.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshHandAuthorizationTest {
    @Test fun coverageCountsFreshSlotsNotSmoothedSlots() {
        val meter = FreshHandCoverage()
        meter.record(4)
        meter.record(2)
        val snapshot = meter.record(0)
        assertEquals(3, snapshot.frames)
        assertEquals(6, snapshot.recognizedSlots)
        assertEquals(50, snapshot.coveragePercent)
        assertEquals(1, snapshot.fullFrames)
        assertEquals(33, snapshot.fullHandPercent)
        assertEquals(1, snapshot.emptyFrames)
    }

    @Test fun coverageEmptySampleHasZeroRates() {
        val snapshot = FreshHandCoverage().snapshot()
        assertEquals(0, snapshot.frames)
        assertEquals(0, snapshot.coveragePercent)
        assertEquals(0, snapshot.fullHandPercent)
    }

    @Test fun coverageResetSeparatesSessions() {
        val meter = FreshHandCoverage()
        meter.record(4)
        meter.reset()
        val snapshot = meter.record(1)
        assertEquals(1, snapshot.frames)
        assertEquals(1, snapshot.recognizedSlots)
        assertEquals(25, snapshot.coveragePercent)
        assertEquals(0, snapshot.fullFrames)
    }

    @Test(expected = IllegalArgumentException::class)
    fun coverageRejectsImpossibleSlotCount() {
        FreshHandCoverage().record(5)
    }

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

    @Test fun placementLookupExcludesStaleDisplayCards() {
        val displayed = mapOf(0 to "Tesla", 1 to "Knight", 2 to "Skeletons", 3 to "Rocket")
        val observed = mapOf(0 to "Tesla", 2 to "Skeletons")
        val authorized = FreshHandAuthorization.verifiedCards(observed, displayed)
        assertEquals(mapOf(0 to "Tesla", 2 to "Skeletons"), authorized)
        assertEquals(false, authorized.containsValue("Knight"))
        assertEquals(false, authorized.containsValue("Rocket"))
    }

    @Test fun placementLookupRequiresSameSlotFromLatestFrame() {
        val displayed = mapOf(0 to "Tesla", 1 to "Knight", 2 to "Skeletons", 3 to "Rocket")
        val observed = mapOf(0 to "Knight", 1 to "Tesla", 2 to "Skeletons", 3 to "Rocket")
        val authorized = FreshHandAuthorization.verifiedCards(observed, displayed)
        assertEquals(mapOf(2 to "Skeletons", 3 to "Rocket"), authorized)
    }

    @Test fun rejectsChangedSlotAndBlankCard() {
        val observed = mapOf(0 to "Tesla", 1 to "", 2 to "Knight")
        val displayed = mapOf(0 to "Rocket", 1 to "", 2 to "Knight")
        assertEquals(1, FreshHandAuthorization.verifiedSlots(observed, displayed))
    }
}
