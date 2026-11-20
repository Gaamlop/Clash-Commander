package com.yoyostudios.clashcompanion.local

import kotlin.math.abs

/** Deterministic, offline policy for exactly the owner's eight cards. Perception remains uncertain. */
object HypnoPolicy {
    data class Pressure(val card: String, val left: Boolean, val confirmedAtMs: Long)
    val costs = mapOf("Skeleton Army" to 3, "Valkyrie" to 4, "Goblin Barrel" to 3, "Wall Breakers" to 2,
        "Dart Goblin" to 3, "Cannon" to 3, "Princess" to 3, "Ice Spirit" to 1)
    private val air = setOf("balloon", "skeleton-barrel", "lava-hound", "lava-pup", "bat", "minion", "mega-minion",
        "baby-dragon", "inferno-dragon", "electro-dragon", "skeleton-dragon", "phoenix-big", "phoenix-small", "flying-machine")
    private val splash = setOf("valkyrie", "wizard", "witch", "executioner", "bomber", "baby-dragon", "dark-prince",
        "mega-knight", "bowler", "firecracker", "royal-ghost", "electro-giant", "mother-witch", "sparky", "electro-dragon", "fire-spirit", "electro-spirit")
    private val swarms = setOf("skeleton", "goblin", "spear-goblin", "barbarian", "guard", "royal-recruit")
    private val buildingTargeters = setOf("hog-rider", "hog", "giant", "golem", "royal-giant", "balloon",
        "battle-ram", "ram-rider", "goblin-giant", "electro-giant", "elixir-golem-big", "wall-breaker", "royal-hog")
    private val effects = setOf("the-log", "arrows", "fireball", "zap", "poison", "tornado", "rocket", "rage",
        "lightning", "earthquake", "freeze", "bomb", "dirt", "selected", "text", "goblin-barrel", "barbarian-barrel",
        "giant-snowball", "royal-delivery", "clone", "mirror", "vines", "void")
    private val buildings = setOf("cannon", "tesla", "inferno-tower", "bomb-tower", "goblin-hut", "furnace",
        "tombstone", "barbarian-hut", "goblin-cage", "elixir-collector")

    fun decide(battle: LocalBattle, nowMs: Long, pressure: Pressure? = null): LocalAction {
        if (nowMs < battle.capturedAtMs || nowMs - battle.capturedAtMs > 1_500) return LocalAction.Wait("Fotograma caducado")
        if (!battle.board.sceneVerified) return LocalAction.Wait("Arena sin torres propias verificadas")
        val e = battle.elixir?.takeIf { it in 0..10 } ?: return LocalAction.Wait("Barra de elixir desconocida")
        val hand = battle.hand.filter { (slot, name) -> slot in 0..3 && name in costs }
        if (hand.values.distinct().size != hand.size) return LocalAction.Wait("Slots ambiguos")
        if (hand.values.distinct().size < 2) return LocalAction.Wait("Esperando al menos dos cartas reconocidas")
        fun available(card: String) = card in hand.values && costs.getValue(card) <= e
        fun targetTower(left: Boolean) = battle.board.units.filter { it.enemy && it.tower && it.base != "king-tower" &&
            it.confidence >= .65f && it.y in .12f.. .36f && (it.x < .5f) == left }.maxByOrNull { it.confidence }
        fun visibleTower(left: Boolean) = targetTower(left) != null
        fun play(card: String, x: Float, y: Float, defend: Boolean, why: String): LocalAction.Play {
            val target = if (card == "Goblin Barrel") {
                val tower = checkNotNull(targetTower(x < .5f))
                tower.x to (tower.y + tower.height * .15f)
            } else legalOwnTarget(x, y)
            return LocalAction.Play(card, hand.entries.first { it.value == card }.key, target.first, target.second, defend, why)
        }
        val threats = battle.board.units.filter { it.enemy && !it.tower && it.base !in effects && it.base !in buildings &&
            !it.base.endsWith("-bar") && !it.base.endsWith("-skill") && !it.base.endsWith("-symbol") &&
            (it.feet >= .375f || it.base in setOf("x-bow", "mortar")) }
        if (threats.isNotEmpty() && threats.size <= 2 && threats.all { it.base in setOf("skeleton", "bat", "ice-spirit", "fire-spirit", "electro-spirit", "heal-spirit") })
            return LocalAction.Wait("La torre cubre unidades menores; conservar defensa")
        val threat = threats.maxByOrNull { it.feet + if (it.base in buildingTargeters) .035f else 0f }
        if (threat != null && threat.base in buildingTargeters && threat.base !in air && threat.feet >= .46f && available("Cannon") &&
            battle.board.units.none { !it.enemy && it.base == "cannon" })
            return play("Cannon", .5f, .535f, true, "Atraer condicion de victoria antes de gastar habilidad")
        heroAbility(battle, e)?.let { return it }
        if (threat != null) {
            val laneX = if (threat.x < .5f) .22f else .79f
            val rangedX = if (threat.x < .5f) .35f else .65f
            val flyers = threats.filter { it.base in air }
            val allies = battle.board.units.filter { !it.enemy && !it.tower }
            val airThreat = flyers.maxByOrNull { it.feet }
            // Never spend a ground-only swarm to answer air, including mixed pushes.
            if (airThreat != null) {
                val airX = if (airThreat.x < .5f) .35f else .65f
                val covered = allies.any { it.base in setOf("dart-goblin", "princess") && abs(it.x - airThreat.x) < .25f }
                if (!covered && available("Dart Goblin")) return play("Dart Goblin", airX, .68f, true, "Defensa antiaerea a distancia")
                if (!covered && available("Princess")) return play("Princess", airX, .71f, true, "Princesa contra amenaza aerea")
                if (airThreat.base == "balloon" && available("Cannon") &&
                    allies.none { it.base == "cannon" }) return play("Cannon", .5f, .535f, true, "Atraer globo al centro")
                if (airThreat.feet > .50f && available("Ice Spirit")) return play("Ice Spirit", airX, .60f, true, "Congelar amenaza aerea")
                if (threat.base in air || !covered) return LocalAction.Wait("Sin respuesta antiaerea asequible")
            }
            if (threat.base in buildingTargeters && available("Cannon") && allies.none { it.base == "cannon" })
                return play("Cannon", .5f, .535f, true, "Edificio central para atraer condicion de victoria")
            val nearby = threats.filter { abs(it.x - threat.x) < .16f && abs(it.feet - threat.feet) < .16f }
            if ((nearby.count { it.base in swarms } >= 2 || threat.base in setOf("witch", "skeleton-army")) && available("Valkyrie"))
                return play("Valkyrie", laneX, threat.feet + .035f, true, "Valquiria contra grupo terrestre")
            val dangerousSplash = nearby.any { it.base in splash }
            if (!dangerousSplash && available("Skeleton Army") && threat.base !in air)
                return play("Skeleton Army", threat.x, threat.feet + .035f, true, "Rodear amenaza terrestre sin dano de area")
            if (available("Valkyrie") && threat.base !in air && threat.base != "pekka")
                return play("Valkyrie", laneX, threat.feet + .035f, true, "Defender cuerpo a cuerpo")
            if (available("Dart Goblin")) return play("Dart Goblin", rangedX, .68f, true, "Defensa a distancia sin regalar enjambre")
            if (available("Princess")) return play("Princess", rangedX, .71f, true, "Defensa de apoyo a distancia")
            if (available("Cannon") && threat.base !in air && allies.none { it.base == "cannon" })
                return play("Cannon", .5f, .535f, true, "Distraer y defender al centro")
            if (available("Ice Spirit")) return play("Ice Spirit", rangedX, .59f, true, "Congelar mientras vuelve una defensa")
            return LocalAction.Wait("Guardar elixir: no hay defensa valida en mano")
        }

        val support = battle.board.units.firstOrNull { !it.enemy && !it.tower && it.feet < .46f &&
            it.base in setOf("valkyrie", "dart-goblin", "princess") }
        val preferredLeft = support?.let { it.x < .5f } ?: true
        val attackLeft = if (visibleTower(preferredLeft)) preferredLeft else !preferredLeft
        val attackX = if (attackLeft) .217f else .79f
        val survivingArmy = battle.board.units.filter { !it.enemy && it.base in setOf("skeleton", "skeleton-evolution", "skeleton-king") && it.feet < .55f }
        if (survivingArmy.size >= 4) {
            val opposite = if (survivingArmy.map { it.x }.average() < .5) .79f else .217f
            if (e >= 6 && available("Goblin Barrel") && visibleTower(opposite < .5f)) return play("Goblin Barrel", opposite, .262f, false,
                "Contrapresion a doble linea con ejercito superviviente")
            if (e >= 5 && available("Wall Breakers")) return play("Wall Breakers", opposite, .484f, false,
                "Ejercito presiona un carril; rompemuros fuerzan el otro")
        }
        // A second lane is a conditional opportunity, never an unverified tap sequence.
        if (pressure != null && nowMs - pressure.confirmedAtMs in 0..3_500 && e >= 5) {
            val otherX = if (pressure.left) .79f else .217f
            if (pressure.card == "Goblin Barrel" && available("Wall Breakers"))
                return play("Wall Breakers", otherX, .484f, false, "Doble linea: rompemuros opuestos al barril confirmado")
            if (pressure.card == "Wall Breakers" && e >= 6 && available("Goblin Barrel") && visibleTower(otherX < .5f))
                return play("Goblin Barrel", otherX, .262f, false, "Doble linea: barril opuesto con reserva defensiva")
        }
        val expensiveBack = battle.board.units.firstOrNull { it.enemy && it.feet < .32f &&
            it.base in setOf("golem", "giant", "lava-hound", "pekka", "electro-giant", "goblin-giant") }
        if (expensiveBack != null && e >= 5 && available("Wall Breakers"))
            return play("Wall Breakers", if (expensiveBack.x < .5f) .79f else .217f, .484f, false,
                "Castigar inversion pesada por el carril contrario")
        if (e >= 7 && available("Goblin Barrel") && visibleTower(attackLeft)) return play("Goblin Barrel", attackX, .262f, false, "Barril sobre torre visible con reserva defensiva")
        if (e >= (if (support != null) 5 else 6) && available("Wall Breakers"))
            return play("Wall Breakers", attackX, .484f, false, "Presion de rompemuros con reserva")
        if (e >= 9 && available("Princess") && battle.board.units.none { !it.enemy && it.base == "princess" })
            return play("Princess", .11f, .70f, false, "Preparar princesa sin apilar tropas fragiles")
        if (e >= 9 && available("Ice Spirit")) return play("Ice Spirit", .30f, .70f, false, "Ciclo barato para evitar perder elixir")
        return LocalAction.Wait("Recuperar elixir y conservar respuestas")
    }

    private fun heroAbility(battle: LocalBattle, elixir: Int): LocalAction.Ability? {
        val button = battle.board.heroButton ?: return null
        if (elixir < 5) return null // three to activate, at least two retained
        val own = battle.board.units.filter { !it.enemy && !it.tower }
        if (battle.board.units.any { it.enemy && it.base in air && it.feet > .40f } &&
            own.none { it.base in setOf("dart-goblin", "princess") }) return null
        val value = mapOf("skeleton" to .15f, "goblin" to .5f, "spear-goblin" to .5f,
            "barbarian" to 1f, "archer" to 1.5f, "guard" to 1f)
        for (valkyrie in own.filter { it.base == "valkyrie" }) {
            val targets = battle.board.units.filter { it.enemy && !it.tower && it.base !in air &&
                it.base !in effects && it.base !in buildings &&
                kotlin.math.hypot((it.x - valkyrie.x) / .054f, (it.feet - valkyrie.feet) / .018f) <= 5.5f }
            val total = targets.sumOf { (value[it.base] ?: 3f).toDouble() }
            if (targets.size >= 2 && total >= 4.0)
                return LocalAction.Ability(button, "Torbellino de heroina sobre grupo terrestre; reserva dos")
        }
        return null
    }

    fun validates(action: LocalAction.Ability, battle: LocalBattle): Boolean =
        battle.board.heroButton == action.button && (battle.elixir ?: 0) >= 3 &&
            action.button.x in .05f.. .95f && action.button.y in .77f.. .84f &&
            battle.board.units.any { !it.enemy && it.base == "valkyrie" }

    fun validates(action: LocalAction.Play, battle: LocalBattle): Boolean =
        battle.hand[action.slot] == action.card && action.slot in 0..3 &&
            (costs[action.card]?.let { cost -> battle.elixir?.let { it in cost..10 } } == true) &&
            action.x.isFinite() && action.y.isFinite() && action.x in .07f.. .93f &&
            if (action.card == "Goblin Barrel") action.y in .12f.. .39f && (action.x < .4f || action.x > .6f) &&
                battle.board.units.any { it.enemy && it.tower && it.base != "king-tower" && it.confidence >= .65f &&
                    kotlin.math.abs(it.x - action.x) < .03f && kotlin.math.abs(it.y + it.height*.15f - action.y) < .03f }
            else action.y in .484f.. .735f && legalOwnTarget(action.x, action.y) == (action.x to action.y)

    /** No troop targets a tower footprint, the river or the enemy half. */
    fun legalOwnTarget(x: Float, y: Float): Pair<Float, Float> {
        var px = x.coerceIn(.07f, .93f)
        val py = y.coerceIn(.484f, .735f)
        for ((tx, ty) in listOf(.217f to .625f, .79f to .625f, .5f to .692f)) {
            if (abs(px - tx) < .065f && abs(py - ty) < .045f) px = if (px < .5f) tx + .085f else tx - .085f
        }
        return px to py
    }
}
