package com.yoyostudios.clashcompanion.command

/** Conservative constraints, not a claim of optimal play or verified screen perception. */
object ArchetypePolicy {
    enum class Profile { ICE_BOW, MINER_CONTROL, HYPNO_LOGBAIT, GENERAL }
    data class Board(val immediateThreat: Boolean? = null, val threatLane: String? = null,
                     val enemyTowerOccupied: Boolean? = null)
    val hypnoCards: Set<String> get() = com.yoyostudios.clashcompanion.strategy.DeckProfiles.hypnoLogBait.cardNames

    fun profile(deck: Set<String>): Profile = when (com.yoyostudios.clashcompanion.strategy.DeckProfiles.matchNames(deck)?.id) {
        "ice_bow" -> Profile.ICE_BOW
        "miner_control" -> Profile.MINER_CONTROL
        "hypno_wb_bait" -> Profile.HYPNO_LOGBAIT
        else -> Profile.GENERAL
    }

    fun guidance(deck: Set<String>): String = com.yoyostudios.clashcompanion.strategy.DeckProfiles.promptFor(deck)

    /** null means allowed by these constraints, not certified to be a good move. */
    fun rejection(deck: Set<String>, card: String, zone: String, elixir: Int?, board: Board): String? {
        val profile = profile(deck)
        if (profile == Profile.GENERAL) return null
        if (board.immediateThreat == null) return "Estado de amenazas desconocido"
        val enemyTower = zone in setOf("left tower", "right tower")
        val offensive = enemyTower || (card == "X-Bow" && zone in setOf("left_bridge", "right_bridge")) ||
            (card == "Wall Breakers" && zone in setOf("left_bridge", "right_bridge"))
        if (board.immediateThreat && offensive) return "Defender antes de comprometer el ataque"
        if (board.immediateThreat && board.threatLane == null) return "Carril de amenaza desconocido"
        if (board.immediateThreat && board.threatLane != null &&
            ((board.threatLane == "left" && zone.contains("right")) ||
             (board.threatLane == "right" && zone.contains("left")))) return "Defensa en el carril contrario"
        if (profile == Profile.ICE_BOW && (card == "X-Bow" && offensive || card == "Rocket" && enemyTower) &&
            (elixir == null || elixir < 9)) return "Reserva insuficiente para Ice Bow"
        if (profile == Profile.MINER_CONTROL && card == "Miner" && enemyTower &&
            (elixir == null || elixir < 6)) return "Miner sin reserva defensiva"
        if (card == "Poison" && enemyTower && board.enemyTowerOccupied != true)
            return "Poison sin defensores observados"
        if (profile == Profile.HYPNO_LOGBAIT && offensive && (elixir == null || elixir < 6))
            return "Bait sin reserva defensiva"
        return null
    }
}
