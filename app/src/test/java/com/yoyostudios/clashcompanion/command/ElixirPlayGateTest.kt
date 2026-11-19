package com.yoyostudios.clashcompanion.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElixirPlayGateTest {
    @Test fun observedInsufficientElixirBlocks() {
        assertTrue(ElixirPlayGate.blocks(2, 3))
        assertTrue(ElixirPlayGate.blocks(0, 1))
    }

    @Test fun enoughElixirAllowsPlay() {
        assertFalse(ElixirPlayGate.blocks(3, 3))
        assertFalse(ElixirPlayGate.blocks(10, 6))
    }

    @Test fun unknownObservationIsNotInvented() {
        assertFalse(ElixirPlayGate.blocks(null, 6))
        assertFalse(ElixirPlayGate.blocks(6, null))
        assertFalse(ElixirPlayGate.blocks(11, 6))
    }
}
