package com.yoyostudios.clashcompanion.local

import kotlin.math.abs

/** Bridge a single missed detection, without keeping enemies across capture/match changes. */
class ThreatMemory {
    private data class Seen(val unit: LocalUnit, val at: Long)
    private var identity: Pair<Long, Long>? = null
    private var seen = emptyList<Seen>()
    fun clear() { identity = null; seen = emptyList() }
    fun observe(battle: LocalBattle): LocalBattle {
        val key = battle.captureSession to battle.matchSession
        if (key != identity) { clear(); identity = key }
        val enemies = battle.board.units.filter { it.enemy && !it.tower }
        val missed = seen.filter { previous ->
            battle.capturedAtMs - previous.at in 0..900 &&
                enemies.none { it.base == previous.unit.base && abs(it.x - previous.unit.x) < .12f && abs(it.y - previous.unit.y) < .14f }
        }
        seen = enemies.map { Seen(it, battle.capturedAtMs) } + missed
        return battle.copy(board = battle.board.copy(units = battle.board.units + missed.map { it.unit }))
    }
}
