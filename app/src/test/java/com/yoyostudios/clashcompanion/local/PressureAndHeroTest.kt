package com.yoyostudios.clashcompanion.local

import org.junit.Assert.*
import org.junit.Test

class PressureAndHeroTest {
    private val tower = LocalUnit("king-tower", false, .95f, .5f, .69f, .06f, .08f)
    private val valk = LocalUnit("valkyrie-hero", false, .9f, .3f, .55f, .06f, .06f)
    private fun enemy(name: String, x: Float = .3f, y: Float = .52f) = LocalUnit(name, true, .9f, x, y, .04f, .04f)
    private fun b(e: Int = 8, enemies: List<LocalUnit> = emptyList(), allies: List<LocalUnit> = emptyList(),
                  button: HeroButton? = null) = LocalBattle(1, 1, 1, 1_000,
        mapOf(0 to "Goblin Barrel", 1 to "Wall Breakers", 2 to "Cannon", 3 to "Dart Goblin"), e,
        LocalBoard(listOf(tower, LocalUnit("queen-tower",true,.9f,.217f,.262f,.07f,.09f),
            LocalUnit("queen-tower",true,.9f,.79f,.262f,.07f,.09f)) + allies + enemies, 200, button))

    @Test fun followsConfirmedBarrelWithOppositeWallBreakersAndReserve() {
        val battle = b(5)
        val action = HypnoPolicy.decide(battle, 1_100, HypnoPolicy.Pressure("Goblin Barrel", true, 1_000)) as LocalAction.Play
        assertEquals("Wall Breakers", action.card); assertTrue(action.x > .5f)
        assertTrue(HypnoPolicy.validates(action, battle))
    }
    @Test fun incomingThreatAndLowElixirCancelDoubleLanePressure() {
        val pressure = HypnoPolicy.Pressure("Goblin Barrel", true, 1_000)
        val defense = HypnoPolicy.decide(b(5, listOf(enemy("hog-rider"))), 1_100, pressure) as LocalAction.Play
        assertEquals("Cannon", defense.card)
        assertTrue(HypnoPolicy.decide(b(4), 1_100, pressure) is LocalAction.Wait)
    }
    @Test fun doesNotExecuteAnOldSecondAttackAfterTheOpportunityExpires() {
        val battle = b(5).copy(capturedAtMs = 5_000)
        assertTrue(HypnoPolicy.decide(battle, 5_100, HypnoPolicy.Pressure("Goblin Barrel", true, 1_000)) is LocalAction.Wait)
    }
    @Test fun survivingArmyAndHeavyEnemyInvestmentEnableOppositeLanePunish() {
        val army = (0..4).map { LocalUnit("skeleton", false, .9f, .2f + it * .01f, .48f, .02f, .03f) }
        val first = HypnoPolicy.decide(b(6, allies = army), 1_100) as LocalAction.Play
        assertTrue(first.x > .5f)
        val second = HypnoPolicy.decide(b(5, enemies = listOf(enemy("golem", .2f, .24f))), 1_100) as LocalAction.Play
        assertEquals("Wall Breakers", second.card); assertTrue(second.x > .5f)
    }
    @Test fun heroUsesThreeElixirOnlyAgainstWorthwhileNearbyGroundGroup() {
        val battle = b(5, listOf(enemy("knight"), enemy("wizard", .36f, .52f)), listOf(valk), HeroButton(.91f, .805f))
        val action = HypnoPolicy.decide(battle, 1_100) as LocalAction.Ability
        assertTrue(HypnoPolicy.validates(action, battle))
        assertFalse(HypnoPolicy.decide(battle.copy(elixir = 4), 1_100) is LocalAction.Ability)
        assertFalse(HypnoPolicy.decide(battle.copy(board = battle.board.copy(heroButton = null)), 1_100) is LocalAction.Ability)
    }
    @Test fun heroDoesNotDashIntoAirOrWasteThreeElixirOnLoneSkeleton() {
        val button = HeroButton(.91f, .805f)
        assertFalse(HypnoPolicy.decide(b(8, listOf(enemy("skeleton")), listOf(valk), button), 1_100) is LocalAction.Ability)
        val air = HypnoPolicy.decide(b(8, listOf(enemy("balloon"), enemy("knight"), enemy("wizard")), listOf(valk), button), 1_100)
        assertTrue(air is LocalAction.Play); assertEquals("Dart Goblin", (air as LocalAction.Play).card)
    }
    @Test fun abilityRequiresTwoDifferentFramesAndConfirmsButtonLossPlusSpending() {
        val s = LocalAutopilotSession()
        val first = b(8, listOf(enemy("knight"), enemy("wizard", .36f, .52f)), listOf(valk), HeroButton(.91f, .805f))
        assertFalse(s.consider(first, 1_100) is LocalAction.Ability)
        assertTrue(s.consider(first, 1_150) is LocalAction.Wait)
        val second = first.copy(frameId = 2, capturedAtMs = 1_200)
        assertTrue(s.consider(second, 1_300) is LocalAction.Ability)
        s.abilityIssued(second, 1_400)
        val after = second.copy(frameId = 3, capturedAtMs = 1_600, elixir = 5,
            board = second.board.copy(heroButton = null))
        s.consider(after, 1_700)
        assertEquals(1, s.confirmedAbilities)
    }
    @Test fun blueGoldAndCostMarkerAreAllRequiredForAButton() {
        val n = 100
        fun image(marker: Boolean, gold: Boolean, blue: Boolean) = IntArray(n*n) { i ->
            val x = ((i % n).toFloat()/n-.5f)*2; val y = ((i/n).toFloat()/n-.5f)*2
            val d = x*x+y*y
            when {
                marker && x < -.5 && y < -.5 -> 0xffdb20c0.toInt()
                gold && d in .55f..1.05f -> 0xffffc930.toInt()
                blue && d < .55f -> 0xff25baff.toInt()
                else -> 0xff303030.toInt()
            }
        }
        assertTrue(HeroButtonEvidence.score(image(true,true,true),n,n)>0)
        for (v in listOf(image(false,true,true),image(true,false,true),image(true,true,false)))
            assertEquals(0f,HeroButtonEvidence.score(v,n,n))
    }
}
