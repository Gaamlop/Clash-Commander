package com.yoyostudios.clashcompanion.detection

import org.junit.Assert.*
import org.junit.Test

class HandRecognitionStateTest {
    @Test fun movingCardReplacesStaleSlotWithoutMutatingInputs() {
        val previous = linkedMapOf(0 to "A", 1 to "B", 2 to "C", 3 to "D", 4 to "E")
        val latest = mapOf(1 to "A", 2 to "C", 3 to "D", 4 to "E")
        val merged = HandStateReducer.merge(previous, latest, emptyMap())
        assertEquals(latest, merged.hand)
        assertEquals(latest, merged.detected)
        assertEquals("A", previous[0])
        assertEquals("B", previous[1])
        assertEquals(5, previous.size)
    }

    @Test fun newlyDetectedSlotsWinAcrossSwapsAndNextCardTransitions() {
        val result = HandStateReducer.merge(
            mapOf(0 to "A", 1 to "B", 4 to "E"),
            mapOf(0 to "E", 1 to "A", 4 to "B"), emptyMap()
        )
        assertEquals(mapOf(0 to "E", 1 to "A", 4 to "B"), result.hand)
        assertEquals(result.hand.values.size, result.hand.values.distinct().size)
    }

    @Test fun displayGraceDoesNotAuthorizeMissingCards() {
        val merged = HandStateReducer.merge(mapOf(0 to "A", 1 to "B", 2 to "C"), mapOf(0 to "A"), emptyMap())
        val state = HandRecognitionState(merged.hand, merged.detected, 10L, 4L, 100L)
        assertEquals(3, state.displayHand.size)
        assertEquals(mapOf(0 to "A"), state.verifiedHand(200L, 4L, 2_000L))
    }

    @Test fun duplicatesAreAmbiguousRatherThanAssignedToArbitrarySlot() {
        val merged = HandStateReducer.merge(emptyMap(), mapOf(0 to "A", 1 to "A", 3 to "D"), emptyMap())
        assertEquals(mapOf(3 to "D"), merged.detected)
        assertEquals(merged.detected, merged.hand)
    }

    @Test fun missingDisplayCardsDecayAndNeverRemainVerified() {
        var merged = HandStateReducer.merge(mapOf(0 to "A"), emptyMap(), emptyMap())
        repeat(4) { merged = HandStateReducer.merge(merged.hand, emptyMap(), merged.misses) }
        assertEquals(mapOf(0 to "A"), merged.hand)
        assertTrue(merged.detected.isEmpty())
        merged = HandStateReducer.merge(merged.hand, emptyMap(), merged.misses)
        assertTrue(merged.hand.isEmpty())
    }

    @Test fun renewedCaptureOrExpiredOriginalFrameInvalidatesObservation() {
        val state = HandRecognitionState(mapOf(0 to "A"), mapOf(0 to "A"), 10L, 4L, 100L)
        assertEquals(mapOf(0 to "A"), state.verifiedHand(200L, 4L, 2_000L))
        assertTrue(state.verifiedHand(200L, 5L, 2_000L).isEmpty())
        assertTrue(state.verifiedHand(200L, null, 2_000L).isEmpty())
        assertTrue(state.verifiedHand(2_101L, 4L, 2_000L).isEmpty())
    }

    @Test fun localElixirExpiresWithItsFrameAndNeverSurvivesUnverifiedHand() {
        val state = HandRecognitionState(detectedHand = mapOf(0 to "Cannon"),
            frameId = 10, captureSessionId = 4, capturedAtElapsedRealtime = 100, observedElixir = 8)
        assertEquals(8, state.verifiedElixir(200, 4, 2_000))
        assertNull(state.verifiedElixir(2_101, 4, 2_000))
        assertNull(state.verifiedElixir(200, 5, 2_000))
        assertNull(state.copy(detectedHand = emptyMap()).verifiedElixir(200, 4, 2_000))
        assertNull(state.copy(observedElixir = 11).verifiedElixir(200, 4, 2_000))
    }
}
