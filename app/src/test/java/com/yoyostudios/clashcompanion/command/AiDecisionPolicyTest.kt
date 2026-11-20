package com.yoyostudios.clashcompanion.command

import org.junit.Assert.*
import org.junit.Test

class AiDecisionPolicyTest {
    private val context = AiDecisionContext(7, 1_100, 12, 1_000, 2, 3, "deck-a")

    private fun valid(
        revision: Long = 7, now: Long = 2_000, session: Long? = 2,
        frame: Long? = 13, match: Long = 3, deck: String = "deck-a"
    ) = AiDecisionPolicy.isCurrent(context, revision, now, session, frame, match, deck)

    @Test fun acceptsFreshObservationInCurrentSession() { assertTrue(valid()) }

    @Test fun rejectsCancelledOrSupersededRequest() { assertFalse(valid(revision = 8)) }

    @Test fun rejectsCaptureStoppedRestartedOrRewound() {
        assertFalse(valid(session = null))
        assertFalse(valid(session = 4))
        assertFalse(valid(frame = 11))
    }

    @Test fun rejectsChangedMatchAndDeck() {
        assertFalse(valid(match = 4))
        assertFalse(valid(deck = "deck-b"))
    }

    @Test fun expiresObservationEvenWhenRequestStartedLater() {
        assertFalse(valid(now = 7_001))
        assertFalse(valid(now = 900))
    }

    @Test fun parsesPlayAndExplicitWait() {
        assertEquals(TacticalDecision.Play("Giant", "left_bridge", "push"),
            AiDecisionPolicy.parse("""{"action":"play","card":"Giant","zone":"left_bridge","reasoning":"push"}"""))
        assertEquals(TacticalDecision.Wait(""), AiDecisionPolicy.parse("""{"action":"wait"}"""))
    }

    @Test fun rejectsUnknownActionsAndMalformedFields() {
        for (json in listOf(
            """{"action":"attack","card":"Giant","zone":"left_bridge"}""",
            """{"action":"play","card":4,"zone":"left_bridge"}""",
            """{"action":"play","card":"Giant"}""",
            """{"action":"play","card":" ","zone":"center"}""", "[]"
        )) {
            assertThrows(RuntimeException::class.java) { AiDecisionPolicy.parse(json) }
        }
    }

    @Test fun validatesVisualElixirWithoutTruncatingOrCoercingValues() {
        for (amount in listOf("0", "4", "10", "null")) {
            val decision = AiDecisionPolicy.parse(
                """{"action":"play","card":"Giant","zone":"left_bridge","observed_elixir":$amount}"""
            ) as TacticalDecision.Play
            assertEquals(amount.toIntOrNull(), decision.observedElixir)
        }
        for (amount in listOf("-1", "11", "4.5", "\"4\"", "true")) {
            assertThrows(RuntimeException::class.java) {
                AiDecisionPolicy.parse(
                    """{"action":"play","card":"Giant","zone":"left_bridge","observed_elixir":$amount}"""
                )
            }
        }
    }

    @Test fun autoRequiresLegibleElixirAndRejectsAnUnaffordableCard() {
        assertTrue(AiDecisionPolicy.canAfford(4, 4, requireObservation = true))
        assertFalse(AiDecisionPolicy.canAfford(5, 4, requireObservation = true))
        assertFalse(AiDecisionPolicy.canAfford(4, null, requireObservation = true))
        assertFalse(AiDecisionPolicy.canAfford(null, 10, requireObservation = true))
        assertTrue(AiDecisionPolicy.canAfford(4, null, requireObservation = false))
        assertFalse(AiDecisionPolicy.canAfford(5, 4, requireObservation = false))
        assertFalse(AiDecisionPolicy.canAfford(null, 11, requireObservation = false))
        assertFalse(AiDecisionPolicy.canAfford(-1, null, requireObservation = false))
    }

    @Test fun permitsEnemyTowerOnlyForCardsDeployableThere() {
        assertTrue(AiDecisionPolicy.isLegalPlacement("Graveyard", "Spell", "left tower"))
        assertTrue(AiDecisionPolicy.isLegalPlacement("Miner", "Troop", "right tower"))
        assertTrue(AiDecisionPolicy.isLegalPlacement("Goblin Drill", "Building", "right tower"))
        assertFalse(AiDecisionPolicy.isLegalPlacement("Knight", "Troop", "left tower"))
        assertFalse(AiDecisionPolicy.isLegalPlacement("Tesla", "Building", "right tower"))
        assertTrue(AiDecisionPolicy.isLegalPlacement("Tesla", "Building", "center"))
        assertNull(AiDecisionPolicy.normalizeZone("my king"))
        assertEquals("left tower", AiDecisionPolicy.normalizeZone("left_tower"))
    }
}
