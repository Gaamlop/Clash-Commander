package com.yoyostudios.clashcompanion.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAuthorizationTest {
    @Test fun noFrameNeverAuthorizes() {
        assertFalse(CaptureAuthorization().isFresh(5_000L))
    }
    @Test fun lossRevokesImmediately() {
        val gate = CaptureAuthorization()
        gate.authorizeFrame(10_000L)
        assertTrue(gate.isFresh(10_100L))
        gate.revoke()
        assertFalse(gate.isFresh(10_101L))
    }
    @Test fun expiresAtBoundary() {
        val gate = CaptureAuthorization()
        gate.authorizeFrame(10_000L)
        assertTrue(gate.isFresh(11_999L))
        assertFalse(gate.isFresh(12_000L))
    }
    @Test fun clockRollbackRejectedAndNewFrameRestores() {
        val gate = CaptureAuthorization()
        gate.authorizeFrame(10_000L)
        assertFalse(gate.isFresh(9_999L))
        gate.revoke()
        gate.authorizeFrame(20_000L)
        assertTrue(gate.isFresh(20_001L))
    }
}
