package com.yoyostudios.clashcompanion.command

/** Pure rule: only an observed insufficient bar may block a placement. */
internal object ElixirPlayGate {
    fun blocks(available: Int?, cost: Int?): Boolean =
        available != null && available in 0..10 &&
            cost != null && cost > available
}
