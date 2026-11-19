package com.yoyostudios.clashcompanion.strategy

/**
 * Three deliberately scoped competitive-deck study profiles.
 *
 * These profiles guide reasoning; they do not make the vision model, placement
 * coordinates, elixir readings or the Gemini model automatically reliable.
 * Actual cards are always read from the user's shared deck link.
 *
 * Evolution and hero modifiers do not change the base card identity in the
 * Clash Royale share links handled by DeckManager.
 */
object DeckProfiles {

    data class Profile(
        val id: String,
        val displayName: String,
        val cardNames: Set<String>,
        val plan: String,
        val source: String
    ) {
        init {
            require(cardNames.size == 8) { "A deck profile must have exactly eight unique cards" }
        }
    }

    val iceBow = Profile(
        id = "ice_bow",
        displayName = "Ice Bow Control",
        cardNames = setOf(
            "X-Bow", "Tesla", "Ice Wizard", "Knight",
            "Skeletons", "Rocket", "Tornado", "The Log"
        ),
        source = "https://deckroyale.gg/deck/x-bow%2Ctesla%2Cice_wizard%2Cknight%2Cskeletons%2Crocket%2Ctornado%2Cthe_log",
        plan = """
            Archetype: slow siege and control, not bridge-spam.
            Win condition: X-Bow locks, secondary Rocket chip only in advantageous late-game situations.
            Preserve Tesla and low-cost cycle cards to defend the opponent's win condition.
            Use Ice Wizard and Tornado for crowd control only when a real threat justifies it.
            Do NOT place X-Bow solely because it is in hand. Prefer defense and positive elixir trades
            until the opponent's X-Bow counter is unavailable or a clear advantage is observed.
            Don't waste Rocket on an unverified tower or without sufficient elixir.
        """.trimIndent()
    )

    val minerControl = Profile(
        id = "miner_control",
        displayName = "Miner Poison Wall Breakers",
        cardNames = setOf(
            "Bats", "Knight", "Wall Breakers", "Spear Goblins",
            "Bomb Tower", "The Log", "Miner", "Poison"
        ),
        source = "https://trackerroyale.com/es/archetype/miner-control",
        plan = """
            Archetype: defend efficiently, then deal small repeated damage.
            Primary win condition: Miner tanking and Wall Breakers pressure; Poison is a value spell.
            Never send a blind Miner to the same location every time; vary safe placements.
            Preserve Bomb Tower for enemy ground win conditions. Save cheap cards for defense.
            Only pair Miner with Poison when the area and elixir trade justify it.
            Do NOT spend every card in a single push or play Wall Breakers into a known active counter.
        """.trimIndent()
    )

    val hypnoLogBait = Profile(
        id = "hypno_wb_bait",
        displayName = "Hypno WB Log Bait 2.8",
        cardNames = setOf(
            "Cannon", "Dart Goblin", "Goblin Barrel", "Ice Spirit",
            "Princess", "Skeleton Army", "Valkyrie", "Wall Breakers"
        ),
        source = "https://royaleapi.com/decks/stats/cannon%2Cdart-goblin%2Cgoblin-barrel-ev1%2Cice-spirit%2Cprincess%2Cskeleton-army-ev1%2Cvalkyrie-hero%2Cwall-breakers/players/ratings",
        plan = """
            Archetype: low-elixir Wall Breakers / Goblin Barrel bait, with strong defensive cycling.
            Track which small spell the opponent has used before committing Goblin Barrel
            or Skeleton Army. Do not assume that their spell is out of cycle without evidence.
            Cannon defends enemy building-targeting attackers; Valkyrie handles clustered ground threats.
            Princess and Dart Goblin are valuable ranged pressure; don't expose both needlessly.
            Use Wall Breakers to create opposite-lane pressure only after defending adequately.
            This profile does NOT include The Log: don't invent it just because the archetype
            is called Log Bait. Every move must be chosen from the actual verified hand.
        """.trimIndent()
    )

    val all: List<Profile> = listOf(iceBow, minerControl, hypnoLogBait)

    private fun key(cardName: String): String =
        cardName.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Exact eight-card membership, order-independent. Never fuzzy-match a deck. */
    fun matchNames(cards: Collection<String>): Profile? {
        if (cards.size != 8) return null
        val normalized = cards.map(::key)
        if (normalized.toSet().size != 8) return null
        val actual = normalized.toSet()
        return all.firstOrNull { profile ->
            profile.cardNames.map(::key).toSet() == actual
        }
    }

    fun promptFor(cards: Collection<String>): String {
        val profile = matchNames(cards) ?: return """
            NO VERIFIED THREE-DECK PROFILE.
            Do not assume a deck archetype from a few shared cards.
            Only suggest safe, observed decisions from the current eight-card deck.
        """.trimIndent()

        return """
            VERIFIED DECK PROFILE: ${profile.displayName} (${profile.id})
            ACTUAL EIGHT CARDS: ${profile.cardNames.joinToString(", ")}
            ARCHETYPE-SPECIFIC TACTICS:
            ${profile.plan}
            MANDATORY: Only use cards identified in the current hand. You may WAIT when
            the field, elixir, or card availability is uncertain. Never fabricate tower HP,
            opponent's cycle, or elixir. Avoid attacks when urgent defense is needed.
        """.trimIndent()
    }
}
