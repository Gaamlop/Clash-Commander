package com.yoyostudios.clashcompanion.detection

import org.junit.Assert.*
import org.junit.Test

class HandObservationStoreTest {
    private val full = HandRecognitionState(
        mapOf(0 to "Cannon", 1 to "Princess"), mapOf(0 to "Cannon", 1 to "Princess"),
        frameId = 8, captureSessionId = 1, capturedAtElapsedRealtime = 100, matchSessionId = 3
    )

    @Test fun pendingInferenceDoesNotHideCompletedFreshHandOrRefreshItsAge() {
        val store = HandObservationStore()
        store.replace(full)
        val pending = store.begin() // classifier has started but has not returned
        assertEquals(full.detectedHand, store.current.verifiedHand(200, 1, 2_000))
        assertTrue(store.current.verifiedHand(2_101, 1, 2_000).isEmpty())
        assertTrue(store.complete(pending, full.copy(frameId = 9, capturedAtElapsedRealtime = 2_101)))
        assertEquals(full.detectedHand, store.current.verifiedHand(2_102, 1, 2_000))
    }

    @Test fun completedMissRemovesAuthorizationEvenWhenDisplayIsSmoothed() {
        val store = HandObservationStore()
        store.replace(full)
        val merged = HandStateReducer.merge(full.displayHand, emptyMap(), emptyMap())
        assertTrue(store.complete(store.begin(), full.copy(displayHand = merged.hand, detectedHand = merged.detected)))
        assertEquals(full.displayHand, store.current.displayHand)
        assertTrue(store.current.verifiedHand(200, 1, 2_000).isEmpty())
    }

    @Test fun stopOrCaptureLossDuringInferenceCannotResurrectOldHand() {
        val store = HandObservationStore()
        store.replace(full)
        val pending = store.begin()
        store.replace(HandRecognitionState(matchSessionId = 4))
        assertFalse(store.complete(pending, full.copy(frameId = 9)))
        assertTrue(store.current.detectedHand.isEmpty())
        assertEquals(4L, store.current.matchSessionId)
    }

    @Test fun outOfOrderInferenceCannotOverwriteNewerCompletion() {
        val store = HandObservationStore()
        val old = store.begin()
        val newer = store.begin()
        assertTrue(store.complete(newer, full.copy(frameId = 10)))
        assertFalse(store.complete(old, full))
        assertEquals(10L, store.current.frameId)
    }
    @Test fun postTapFenceRejectsFramesCapturedBeforeCompletionEvenWhenInferenceStartsLater() {
        val store = HandObservationStore()
        store.replace(full)
        val alreadyRunning = store.begin()
        store.invalidateAfterGesture(200)
        assertFalse(store.complete(alreadyRunning, full.copy(capturedAtElapsedRealtime = 250)))
        assertTrue(store.current.detectedHand.isEmpty())
        assertFalse(store.complete(store.begin(), full.copy(frameId = 9, capturedAtElapsedRealtime = 199)))
        assertFalse(store.complete(store.begin(), full.copy(frameId = 10, capturedAtElapsedRealtime = 200)))
        assertTrue(store.complete(store.begin(), full.copy(frameId = 11, capturedAtElapsedRealtime = 201)))
        assertEquals(full.detectedHand, store.current.detectedHand)
    }
}
