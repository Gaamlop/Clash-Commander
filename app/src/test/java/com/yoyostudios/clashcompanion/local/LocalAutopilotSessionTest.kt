package com.yoyostudios.clashcompanion.local

import org.junit.Assert.*
import org.junit.Test

class LocalAutopilotSessionTest {
    private val tower = LocalUnit("king-tower", false, .9f, .5f, .69f, .08f, .08f)
    private fun b(frame: Long, at: Long, elixir: Int = 8, hand: List<String> = listOf("Goblin Barrel", "Cannon", "Dart Goblin", "Ice Spirit"),
                  enemy: String? = null) = LocalBattle(frame, 1, 1, at, hand.withIndex().associate { it.index to it.value }, elixir,
        LocalBoard(listOfNotNull(tower, LocalUnit("queen-tower", true, .9f, .217f, .262f, .07f, .09f),
            enemy?.let { LocalUnit(it, true, .9f, .22f, .54f, .04f, .05f) }), 160))

    @Test fun threePlaysObserveRotateSpendDefendAndContinueWithoutAnyNetworkOrManualCapture() {
        val session = LocalAutopilotSession()
        val first = b(1, 1_000)
        val barrel = session.consider(first, 1_100) as LocalAction.Play
        assertEquals("Goblin Barrel", barrel.card)
        session.issued(barrel, first, 1_300)
        val afterBarrel = b(2, 1_800, 5, listOf("Valkyrie", "Cannon", "Dart Goblin", "Ice Spirit"))
        assertTrue(session.consider(afterBarrel, 1_900) is LocalAction.Wait)
        assertEquals(1, session.confirmed)
        val defenseFrame = afterBarrel.copy(frameId = 3, capturedAtMs = 2_700,
            board = LocalBoard(listOf(tower, LocalUnit("hog-rider", true, .9f, .22f, .54f, .04f, .05f)), 160))
        val cannon = session.consider(defenseFrame, 2_800) as LocalAction.Play
        assertEquals("Cannon", cannon.card)
        session.issued(cannon, defenseFrame, 3_000)
        val afterCannon = b(4, 3_500, 2, listOf("Valkyrie", "Princess", "Dart Goblin", "Ice Spirit"))
        session.consider(afterCannon, 3_600)
        assertEquals(2, session.confirmed)
        val airFrame = b(5, 5_000, 3, listOf("Valkyrie", "Princess", "Dart Goblin", "Ice Spirit"), "balloon")
        val dart = session.consider(airFrame, 5_100) as LocalAction.Play
        assertEquals("Dart Goblin", dart.card)
        session.issued(dart, airFrame, 5_300)
        session.consider(b(6, 5_800, 0, listOf("Valkyrie", "Princess", "Wall Breakers", "Ice Spirit")), 5_900)
        assertEquals(3, session.confirmed)
        assertEquals(0, session.unconfirmed)
    }

    @Test fun tapCompletionAloneAndMissingSlotDoNotProveAPlay() {
        val s = LocalAutopilotSession(); val before = b(1, 1_000)
        val action = s.consider(before, 1_100) as LocalAction.Play
        s.issued(action, before, 1_300)
        assertTrue(s.consider(b(2, 1_500, 5).copy(hand = before.hand - 0), 1_600) is LocalAction.Wait)
        assertEquals(0, s.confirmed)
        assertNotNull(s.pending)
        s.consider(b(3, 5_000), 5_100)
        assertEquals(1, s.unconfirmed); assertNull(s.pending)
    }

    @Test fun aLaterFrameCapturedBeforeTheGestureCannotConfirmAnything() {
        val s = LocalAutopilotSession(); val before = b(1, 1_000)
        val action = s.consider(before, 1_100) as LocalAction.Play
        s.issued(action, before, 1_800)
        s.consider(b(2, 1_500, 5, listOf("Valkyrie", "Cannon", "Dart Goblin", "Ice Spirit")), 1_900)
        assertEquals(0, s.confirmed)
    }

    @Test fun sessionRenewalAndManualTakeoverInvalidatePendingPlay() {
        val s = LocalAutopilotSession(); val before = b(1, 1_000)
        s.issued(s.consider(before, 1_100) as LocalAction.Play, before, 1_300)
        s.consider(b(2, 1_500).copy(captureSession = 2, matchSession = 3), 1_600)
        assertNull(s.pending); assertEquals(0, s.confirmed)
        s.manualOverride(2_000)
        assertTrue(s.consider(b(3, 2_100).copy(captureSession = 2, matchSession = 3), 2_200) is LocalAction.Wait)
    }
}
