package com.yoyostudios.clashcompanion.detection

import com.yoyostudios.clashcompanion.capture.CaptureFreshness

/** An observation and its smoothed display are deliberately separate. */
data class HandRecognitionState(
    val displayHand: Map<Int, String> = emptyMap(),
    val detectedHand: Map<Int, String> = emptyMap(),
    val frameId: Long = -1L,
    val captureSessionId: Long = -1L,
    val capturedAtElapsedRealtime: Long = -1L,
    val matchSessionId: Long = 0L,
    val observedElixir: Int? = null
) {
    fun verifiedElixir(nowElapsedRealtime: Long, activeCaptureSessionId: Long?, maxAgeMs: Long): Int? =
        observedElixir?.takeIf { it in 0..10 && verifiedHand(nowElapsedRealtime, activeCaptureSessionId, maxAgeMs).isNotEmpty() }

    fun verifiedHand(nowElapsedRealtime: Long, activeCaptureSessionId: Long?, maxAgeMs: Long): Map<Int, String> =
        if (captureSessionId == activeCaptureSessionId &&
            CaptureFreshness.isFresh(capturedAtElapsedRealtime, nowElapsedRealtime, maxAgeMs)
        ) detectedHand else emptyMap()
}

data class HandMergeResult(val hand: Map<Int, String>, val misses: Map<Int, Int>, val detected: Map<Int, String>)

/** Pure display smoothing. Only [HandMergeResult.detected] may authorize a tap. */
object HandStateReducer {
    fun merge(
        previous: Map<Int, String>,
        latest: Map<Int, String>,
        previousMisses: Map<Int, Int>,
        graceScans: Int = 5
    ): HandMergeResult {
        require(graceScans >= 0)
        val candidates = latest.filter { (slot, card) -> slot in 0..4 && card.isNotBlank() }
        // Ambiguous duplicate observations are unknown, rather than a guessed slot.
        val repeated = candidates.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val detected = candidates.filterValues { it !in repeated }.toSortedMap().toMap()
        val misses = (0..4).associateWith { slot ->
            if (slot in detected) 0 else ((previousMisses[slot] ?: 0) + 1).coerceAtMost(graceScans + 1)
        }
        val merged = detected.toMutableMap()
        for (slot in 0..4) {
            val old = previous[slot] ?: continue
            if (slot !in merged && misses.getValue(slot) <= graceScans && old !in merged.values) {
                merged[slot] = old
            }
        }
        return HandMergeResult(merged.toSortedMap().toMap(), misses, detected)
    }
}
