package com.yoyostudios.clashcompanion.local

import org.junit.Assert.*
import org.junit.Test

class CaptureSceneGateTest {
    @Test fun neuralArenaKeepsScanningWhenCardsAreGreyForLowElixir() {
        assertTrue(CaptureSceneGate.inMatch(true, true, false))
    }
    @Test fun brightMenuCannotOverrideNeuralSceneRejection() {
        assertFalse(CaptureSceneGate.inMatch(true, false, true))
    }
    @Test fun optionalModelFailureDoesNotRemoveLegacyVoiceHandScanning() {
        assertTrue(CaptureSceneGate.inMatch(true, null, true))
        assertTrue(CaptureSceneGate.inMatch(false, false, true))
        assertFalse(CaptureSceneGate.inMatch(false, true, false))
    }
}
