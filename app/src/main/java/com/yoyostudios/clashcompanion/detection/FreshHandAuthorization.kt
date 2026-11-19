package com.yoyostudios.clashcompanion.detection

/**
 * Only directly observed hand cards can authorize a decision.
 * A card carried over by temporal smoothing is useful for display,
 * but cannot be used as evidence for a fresh play.
 */
internal object FreshHandAuthorization {
    fun verifiedSlots(observed: Map<Int, String>, displayed: Map<Int, String>): Int =
        (0..3).mapNotNull { slot ->
            observed[slot]?.takeIf { it.isNotBlank() && displayed[slot] == it }
        }.distinct().size
}
