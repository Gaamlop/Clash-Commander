package com.yoyostudios.clashcompanion.deck

enum class BattleMode(val label: String, val tacticalContext: String) {
    CLASSIC("Clásico", "Classic Challenge; prioritize consistent low-risk trades across varied opponents."),
    GRAND("Gran desafío", "Grand Challenge; conserve resources, track opponent responses and avoid speculative commitments."),
    RANKED("Ranked", "Ranked; actual card/tower levels are unknown, so do not assume equal-level interactions.")
}

data class DeckPreset(
    val id: String,
    val name: String,
    val cardIds: List<Long>,
    val evolutions: List<String>,
    val sourceSlug: String,
    val rankedMatches: Int,
    val rankedWinRate: String,
    val plan: String
) {
    val sourceUrl: String get() = "https://royaleapi.com/decks/stats/$sourceSlug"
    val matchesLabel: String get() = "$rankedMatches partidas de jugadores · $rankedWinRate % victorias"
}

/** Exact observed compositions; source statistics never describe this bot's performance. */
object DeckPresets {
    const val CHECKED_ON = "19/11/2026"
    const val WINDOW = "Ranked · últimos 7 días"
    val all = listOf(
        DeckPreset(
            "giant-skeleton-ram", "Esqueleto gigante · Ariete",
            listOf(28000001, 26000046, 26000036, 26000042, 27000010, 26000020, 28000016, 26000035).map { it.toLong() },
            listOf("Ariete de batalla", "Leñador"),
            "arrows,bandit,battle-ram-ev1,electro-wizard,furnace,giant-skeleton,heal-spirit,lumberjack-ev1",
            5508, "58,5",
            "Defend with Giant Skeleton and Electro Wizard, then counterpush with surviving units and Battle Ram. " +
                "Use Bandit to pressure the opposite lane only when resources are observed; do not blindly stack a push."
        ),
        DeckPreset(
            "mortar-cart-bait", "Mortero · Carro de cañón",
            listOf(28000015, 26000054, 28000000, 26000041, 26000023, 27000002, 26000053, 26000056).map { it.toLong() },
            listOf("Mortero", "Barril de esqueletos"),
            "barbarian-barrel,cannon-cart,fireball,goblin-gang,ice-wizard,mortar-ev1,rascals,skeleton-barrel-ev1",
            972, "55,5",
            "Mortar is the win condition and can also pull threats defensively. Protect it with Cannon Cart or Rascals. " +
                "Skeleton Barrel and Goblin Gang bait small spells. Do not spend all defensive answers together."
        ),
        DeckPreset(
            "graveyard-hut", "Cementerio · Choza",
            listOf(28000015, 27000010, 27000001, 28000010, 26000023, 26000000, 28000009, 26000010).map { it.toLong() },
            listOf("Horno", "Caballero"),
            "barbarian-barrel,furnace-ev1,goblin-hut,graveyard,ice-wizard,knight-ev1,poison,skeletons",
            442, "51,8",
            "Defend with Knight, Ice Wizard and buildings; counterpush with Graveyard only while a surviving unit tanks. " +
                "Poison supports Graveyard when it also hits defenders. Graveyard and Poison target the ENEMY tower, " +
                "not the bridge. Prefer waiting to an unsupported Graveyard."
        )
    )

    fun matching(cardIds: Collection<Long>): DeckPreset? = all.firstOrNull {
        cardIds.size == 8 && it.cardIds.toSet() == cardIds.toSet()
    }
}
