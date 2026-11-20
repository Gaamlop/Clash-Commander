package com.yoyostudios.clashcompanion.local

import org.junit.Assert.*
import org.junit.Test

class HypnoPolicyTest {
    private fun unit(name: String, enemy: Boolean = true, x: Float = .22f, y: Float = .52f) =
        LocalUnit(name, enemy, .92f, x, y, .045f, .05f)
    private fun battle(hand: List<String> = listOf("Cannon", "Goblin Barrel", "Dart Goblin", "Ice Spirit"),
                       elixir: Int? = 8, enemies: List<LocalUnit> = emptyList(), own: List<LocalUnit> = emptyList()) =
        LocalBattle(1, 1, 1, 1_000, hand.withIndex().associate { it.index to it.value }, elixir,
            LocalBoard(listOf(unit("king-tower", false, .5f, .69f),
                unit("queen-tower", true, .217f, .262f), unit("queen-tower", true, .79f, .262f)) + enemies + own, 180))
    private fun choose(b: LocalBattle) = HypnoPolicy.decide(b, 1_100)
    private fun play(b: LocalBattle): LocalAction.Play = (choose(b) as LocalAction.Play).also { assertTrue(HypnoPolicy.validates(it, b)) }

    @Test fun pullsHogToCenterBeforeAttackingAtFullElixir() {
        val action = play(battle(elixir = 10, enemies = listOf(unit("hog-rider"))))
        assertEquals("Cannon", action.card); assertEquals(.5f, action.x); assertTrue(action.defensive)
    }
    @Test fun choosesAirDefenseForBalloonAndMinionsInsteadOfGroundSwarm() {
        for (name in listOf("balloon", "skeleton-barrel", "minion", "inferno-dragon")) {
            val b = battle(listOf("Skeleton Army", "Cannon", "Dart Goblin", "Goblin Barrel"), enemies = listOf(unit(name)))
            assertEquals("Dart Goblin", play(b).card)
        }
    }
    @Test fun rollingBarrelsAndHealthBarsAreNotEnemiesToFeedASwarmInto() {
        for (name in listOf("barbarian-barrel", "goblin-barrel", "skeleton-king-bar", "skeleton-king-skill")) {
            val action = choose(battle(listOf("Skeleton Army", "Cannon"), enemies = listOf(unit(name))))
            assertTrue(action is LocalAction.Wait)
        }
    }
    @Test fun loneSpiritDoesNotBaitAThreeElixirDefense() {
        assertTrue(choose(battle(listOf("Skeleton Army", "Cannon"), enemies = listOf(unit("fire-spirit")))) is LocalAction.Wait)
    }
    @Test fun motherWitchDoesNotReceiveAFreeSkeletonArmy() {
        assertEquals("Dart Goblin", play(battle(listOf("Skeleton Army", "Dart Goblin"),
            enemies = listOf(unit("mother-witch")))).card)
    }
    @Test fun airDefenseUsesTheThreatLane() {
        val action = play(battle(enemies = listOf(unit("balloon", x = .8f))))
        assertEquals("Dart Goblin", action.card); assertTrue(action.x > .5f)
    }
    @Test fun princessAnswersAirWhenDartIsNotInHand() {
        assertEquals("Princess", play(battle(listOf("Princess", "Valkyrie", "Goblin Barrel", "Wall Breakers"),
            enemies = listOf(unit("baby-dragon")))).card)
    }
    @Test fun groundSwarmNeverAnswersAirWithoutAnActualAirTargetingCard() {
        val action = choose(battle(listOf("Skeleton Army", "Valkyrie", "Goblin Barrel", "Wall Breakers"),
            enemies = listOf(unit("minion"))))
        assertTrue(action is LocalAction.Wait)
    }
    @Test fun valkyrieCountersGroundSwarm() {
        assertEquals("Valkyrie", play(battle(listOf("Valkyrie", "Skeleton Army", "Cannon", "Goblin Barrel"),
            enemies = listOf(unit("goblin"), unit("goblin", x = .25f), unit("goblin", x = .19f)))).card)
    }
    @Test fun doesNotFeedSkeletonArmyIntoSplashSupport() {
        val b = battle(listOf("Skeleton Army", "Dart Goblin", "Goblin Barrel", "Wall Breakers"),
            enemies = listOf(unit("pekka"), unit("wizard", y = .47f)))
        assertEquals("Dart Goblin", play(b).card)
    }
    @Test fun surroundsSingleTargetMeleeWhenNoSplashIsPresent() {
        assertEquals("Skeleton Army", play(battle(listOf("Skeleton Army", "Princess", "Goblin Barrel", "Ice Spirit"),
            enemies = listOf(unit("mini-pekka")))).card)
    }
    @Test fun defendsWithOneElixirRatherThanTryingUnaffordableCards() {
        val b = battle(elixir = 1, enemies = listOf(unit("hog-rider")))
        assertEquals("Ice Spirit", play(b).card)
    }
    @Test fun doesNotAttackOrInventElixirWhenTheBarIsUnknown() {
        assertTrue(choose(battle(elixir = null)) is LocalAction.Wait)
    }
    @Test fun doesNotAttackAtFourElixir() { assertTrue(choose(battle(elixir = 4)) is LocalAction.Wait) }
    @Test fun barrelKeepsADefensiveReserve() {
        assertEquals("Goblin Barrel", play(battle(elixir = 7)).card)
        assertTrue(choose(battle(listOf("Goblin Barrel", "Cannon"), elixir = 6)) is LocalAction.Wait)
    }
    @Test fun usesWallBreakersWhenBarrelIsUnavailable() {
        assertEquals("Wall Breakers", play(battle(listOf("Wall Breakers", "Cannon", "Princess", "Valkyrie"), 6)).card)
    }
    @Test fun barrelRetargetsAVisibleSurvivingTowerInsteadOfAnEmptyDestroyedFootprint() {
        val original = battle(elixir = 9)
        val b = original.copy(board = original.board.copy(units = original.board.units.filterNot { it.enemy && it.x < .5f }))
        val action = play(b)
        assertEquals("Goblin Barrel", action.card); assertTrue(action.x > .5f)
        val noTargets = original.copy(board = LocalBoard(original.board.units.filter { !it.enemy }, 100))
        assertNotEquals("Goblin Barrel", (choose(noTargets) as? LocalAction.Play)?.card)
    }
    @Test fun partialVerifiedHandCanDefendWithoutGuessingUnknownValkyrie() {
        assertEquals("Dart Goblin", play(battle(listOf("Dart Goblin", "Goblin Barrel"),
            enemies = listOf(unit("balloon")))).card)
    }
    @Test fun doesNotDoublePlaceCannonWhenOneAlreadyExists() {
        val b = battle(enemies = listOf(unit("hog-rider")), own = listOf(unit("cannon", false, .5f, .53f)))
        assertNotEquals("Cannon", play(b).card)
    }
    @Test fun noCardPlaysOnMenuOrStaleOrFutureCapture() {
        val b = battle()
        assertTrue(choose(b.copy(board = LocalBoard(emptyList(), 1))) is LocalAction.Wait)
        assertTrue(HypnoPolicy.decide(b, 2_501) is LocalAction.Wait)
        assertTrue(HypnoPolicy.decide(b, 999) is LocalAction.Wait)
    }
    @Test fun ambiguousOrInsufficientSlotsNeverAuthorizeAPlay() {
        assertTrue(choose(battle(listOf("Goblin Barrel"))) is LocalAction.Wait)
        assertTrue(choose(battle(listOf("Goblin Barrel", "Goblin Barrel", "Ice Spirit"))) is LocalAction.Wait)
    }
    @Test fun everyAvailableHandAndElixirKeepsCardCostAndPlacementInvariant() {
        val cards = HypnoPolicy.costs.keys.toList()
        val threats = listOf(emptyList(), listOf(unit("hog-rider")), listOf(unit("balloon")),
            listOf(unit("valkyrie", x = .8f, y = .64f)), listOf(unit("pekka"), unit("baby-dragon")))
        for (i in cards.indices) for (j in i+1 until cards.size) for (e in 0..10) for (enemies in threats) {
            val b = battle(listOf(cards[i], cards[j]), e, enemies)
            (choose(b) as? LocalAction.Play)?.let { assertTrue("$b -> $it", HypnoPolicy.validates(it, b)) }
        }
    }
    @Test fun placementNeverHitsOwnTowerOrEnemyHalf() {
        for (x in listOf(.217f, .5f, .79f)) for (y in listOf(.445f, .625f, .692f, .9f)) {
            val p = HypnoPolicy.legalOwnTarget(x, y)
            assertTrue(p.second in .484f.. .735f)
            assertEquals(p, HypnoPolicy.legalOwnTarget(p.first, p.second))
        }
    }
}
