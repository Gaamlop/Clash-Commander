package com.yoyostudios.clashcompanion.command

import org.junit.Assert.*
import org.junit.Test

class ArchetypePolicyTest {
    private val iceBow = setOf("X-Bow", "Tesla", "Ice Wizard", "Rocket", "Knight", "Skeletons", "Tornado", "The Log")
    private val miner = com.yoyostudios.clashcompanion.strategy.DeckProfiles.minerControl.cardNames
    private val clear = ArchetypePolicy.Board(false)
    @Test fun hypnoRequiresExactEightCards() {
        assertEquals(ArchetypePolicy.Profile.HYPNO_LOGBAIT, ArchetypePolicy.profile(ArchetypePolicy.hypnoCards))
        assertEquals(ArchetypePolicy.Profile.GENERAL, ArchetypePolicy.profile(ArchetypePolicy.hypnoCards - "Cannon" + "Tesla"))
    }
    @Test fun iceBowWaitsForDefensiveReserve() {
        assertNotNull(ArchetypePolicy.rejection(iceBow, "X-Bow", "left_bridge", 8, clear))
        assertNull(ArchetypePolicy.rejection(iceBow, "X-Bow", "left_bridge", 9, clear))
    }
    @Test fun knownThreatOverridesAffordableAttack() {
        assertNotNull(ArchetypePolicy.rejection(iceBow, "X-Bow", "left_bridge", 10, ArchetypePolicy.Board(true,"left")))
        assertNull(ArchetypePolicy.rejection(iceBow, "Tesla", "center", 4, ArchetypePolicy.Board(true,"left")))
    }
    @Test fun defenseMustUseObservedLaneOrCenter() {
        assertNotNull(ArchetypePolicy.rejection(miner, "Knight", "behind_right", 7, ArchetypePolicy.Board(true,"left")))
        assertNull(ArchetypePolicy.rejection(miner, "Knight", "behind_left", 7, ArchetypePolicy.Board(true,"left")))
    }
    @Test fun unknownBoardNeverAuthorizesSpecializedAutoPlay() {
        assertNotNull(ArchetypePolicy.rejection(miner,"Miner","left tower",10,ArchetypePolicy.Board()))
        assertNotNull(ArchetypePolicy.rejection(iceBow,"Tesla","center",10,ArchetypePolicy.Board(true)))
    }
    @Test fun minerPreservesThreeElixirAndPoisonRequiresTargets() {
        assertNotNull(ArchetypePolicy.rejection(miner,"Miner","left tower",5,clear))
        assertNull(ArchetypePolicy.rejection(miner,"Miner","left tower",6,clear))
        assertNotNull(ArchetypePolicy.rejection(miner,"Poison","left tower",10,clear))
        assertNull(ArchetypePolicy.rejection(miner,"Poison","left tower",7,ArchetypePolicy.Board(false,null,true)))
    }
    @Test fun hypnoDefendsBeforeBaitingAndPreservesReserve() {
        val deck = ArchetypePolicy.hypnoCards
        assertNotNull(ArchetypePolicy.rejection(deck,"Goblin Barrel","left tower",10,ArchetypePolicy.Board(true,"right")))
        assertNotNull(ArchetypePolicy.rejection(deck,"Wall Breakers","left_bridge",5,clear))
        assertNull(ArchetypePolicy.rejection(deck,"Goblin Barrel","right tower",6,clear))
    }
    @Test fun boardParserRejectsGuessedTypesAndInvalidLanes() {
        for (extra in listOf("\"immediate_threat\":\"false\"", "\"threat_lane\":\"middle\"", "\"enemy_tower_occupied\":1")) {
            assertThrows(RuntimeException::class.java) { AiDecisionPolicy.parse("{\"action\":\"play\",\"card\":\"Miner\",\"zone\":\"left tower\",$extra}") }
        }
        val play = AiDecisionPolicy.parse("""{"action":"play","card":"Miner","zone":"left tower","immediate_threat":false,"threat_lane":null} """) as TacticalDecision.Play
        assertEquals(false, play.board.immediateThreat)
    }
}
