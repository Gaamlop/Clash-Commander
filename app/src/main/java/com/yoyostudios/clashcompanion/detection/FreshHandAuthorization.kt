package com.yoyostudios.clashcompanion.detection

/**
 * Cards seen in the current frame and occupying the same slot in the displayed
 * hand. Smoothed or stale display values must never authorize a tap.
 */
internal object FreshHandAuthorization {
    fun verifiedCards(
        observed: Map<Int, String>,
        displayed: Map<Int, String>
    ): Map<Int, String> {
        val seen = mutableSetOf<String>()
        return (0..3).mapNotNull { slot ->
            observed[slot]?.takeIf { card ->
                card.isNotBlank() && displayed[slot] == card && seen.add(card)
            }?.let { slot to it }
        }.toMap()
    }

    fun verifiedSlots(observed: Map<Int, String>, displayed: Map<Int, String>): Int =
        verifiedCards(observed, displayed).size
}
