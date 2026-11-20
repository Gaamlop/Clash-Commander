package com.yoyostudios.clashcompanion.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureFreshnessTest {
    @Test fun frozenFrameExpiresDespiteRepeatedReads() {
        val capturedAt = 500L
        assertTrue(CaptureFreshness.isFresh(capturedAt, 500L, 2_000L))
        assertTrue(CaptureFreshness.isFresh(capturedAt, 2_500L, 2_000L))
        assertFalse(CaptureFreshness.isFresh(capturedAt, 2_501L, 2_000L))
        assertFalse(CaptureFreshness.isFresh(capturedAt, 9_000L, 2_000L))
    }

    @Test fun invalidOrFutureTimestampsCannotBeFresh() {
        assertFalse(CaptureFreshness.isFresh(-1L, 100L, 2_000L))
        assertFalse(CaptureFreshness.isFresh(101L, 100L, 2_000L))
        assertFalse(CaptureFreshness.isFresh(100L, 100L, -1L))
        assertTrue(CaptureFreshness.isFresh(0L, 0L, 0L))
    }
}
