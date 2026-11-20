package com.yoyostudios.clashcompanion.local

import com.yoyostudios.clashcompanion.detection.ElixirBarMath
import org.junit.Assert.*
import org.junit.Test

class ElixirSegmentTest {
    @Test fun excludedNumeralSegmentIsAccountedForWithoutOverstatingElixir() {
        for (tenths in 10..100) {
            val real = tenths / 10.0
            val filledUntil = .222 + real / 10 * (.965 - .222)
            val samples = BooleanArray(40) { i -> .29 + (i + .5) / 40 * (.96 - .29) <= filledUntil }
            val estimate = ElixirBarMath.estimateSegment(samples)
            if (estimate != null) {
                assertTrue("$real -> $estimate", estimate <= real)
                assertTrue("$real -> $estimate", real - estimate < 1.3)
            }
            if (real >= 2) assertNotNull(estimate)
        }
    }
    @Test fun obscuredOrDiscontinuousBarRemainsUnknown() {
        assertNull(ElixirBarMath.estimateSegment(BooleanArray(40)))
        assertNull(ElixirBarMath.estimateSegment(BooleanArray(40) { it % 3 == 0 }))
        assertNull(ElixirBarMath.estimateSegment(BooleanArray(40) { it > 5 }))
    }
}
