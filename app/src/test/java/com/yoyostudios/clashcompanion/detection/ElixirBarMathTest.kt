package com.yoyostudios.clashcompanion.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElixirBarMathTest {
    @Test fun fullPurpleBarRepresentsTen() {
        assertEquals(10, ElixirBarMath.estimate(BooleanArray(40) { true }))
    }

    @Test fun partialBarCountsConservatively() {
        assertEquals(6, ElixirBarMath.estimate(BooleanArray(40) { it < 24 }))
        assertEquals(3, ElixirBarMath.estimate(BooleanArray(40) { it < 12 }))
    }

    @Test fun noBarIsUnknownRatherThanFabricatedZero() {
        assertNull(ElixirBarMath.estimate(BooleanArray(40)))
        assertNull(ElixirBarMath.estimate(BooleanArray(40) { it == 20 }))
    }

    @Test fun nonContiguousPurpleCannotImpersonateElixirBar() {
        assertNull(ElixirBarMath.estimate(BooleanArray(40) { it % 2 == 0 }))
    }

    @Test fun oneMissingPixelWithinFillIsTolerated() {
        assertEquals(5, ElixirBarMath.estimate(BooleanArray(40) { it < 21 && it != 9 }))
    }

    @Test fun almostFullBarNeedsOnlyTwoMissingSamples() {
        assertEquals(10, ElixirBarMath.estimate(BooleanArray(40) { it < 38 }))
    }

    @Test fun invalidGeometryIsUnknown() {
        assertNull(ElixirBarMath.estimate(BooleanArray(10) { true }))
    }
}
