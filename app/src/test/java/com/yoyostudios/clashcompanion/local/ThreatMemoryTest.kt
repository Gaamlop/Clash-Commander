package com.yoyostudios.clashcompanion.local

import org.junit.Assert.*
import org.junit.Test

class ThreatMemoryTest {
    private val enemy = LocalUnit("hog-rider", true, .9f, .22f, .52f, .05f, .05f)
    private fun frame(id: Long, at: Long, units: List<LocalUnit>) = LocalBattle(id, 1, 1, at,
        mapOf(0 to "Cannon", 1 to "Goblin Barrel"), 8, LocalBoard(units, 100))
    @Test fun oneMissDoesNotCreateAnOpeningToAttackButMemoryExpires() {
        val memory = ThreatMemory()
        memory.observe(frame(1, 1_000, listOf(enemy)))
        assertEquals(listOf(enemy), memory.observe(frame(2, 1_500, emptyList())).board.units)
        assertTrue(memory.observe(frame(3, 1_901, emptyList())).board.units.isEmpty())
    }
    @Test fun newCaptureCannotCarryAThreatFromThePreviousMatch() {
        val memory = ThreatMemory()
        memory.observe(frame(1, 1_000, listOf(enemy)))
        assertTrue(memory.observe(frame(2, 1_100, emptyList()).copy(captureSession = 2)).board.units.isEmpty())
    }
    @Test fun movingUnitReplacesItsPreviousPosition() {
        val memory = ThreatMemory()
        memory.observe(frame(1, 1_000, listOf(enemy)))
        val moved = enemy.copy(y = .59f)
        assertEquals(listOf(moved), memory.observe(frame(2, 1_200, listOf(moved))).board.units)
    }
}
