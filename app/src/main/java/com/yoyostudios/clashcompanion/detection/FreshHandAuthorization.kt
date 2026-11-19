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

/**
 * Local, label-free coverage measurement for in-match frames.
 *
 * Coverage is NOT recognition accuracy: false positives and card identity
 * correctness require independently annotated ground-truth screenshots.
 * No screenshots, card names or player identifiers are retained.
 */
internal class FreshHandCoverage {
    private var frames: Int = 0
    private var recognizedSlots: Int = 0
    private var fullFrames: Int = 0
    private var emptyFrames: Int = 0

    data class Snapshot(
        val frames: Int,
        val recognizedSlots: Int,
        val fullFrames: Int,
        val emptyFrames: Int
    ) {
        val coveragePercent: Int
            get() = if (frames == 0) 0 else (100L * recognizedSlots / (frames * 4L)).toInt()
        val fullHandPercent: Int
            get() = if (frames == 0) 0 else (100L * fullFrames / frames).toInt()
    }

    fun record(freshSlots: Int): Snapshot {
        require(freshSlots in 0..4) { "Only the four hand slots count" }
        frames++
        recognizedSlots += freshSlots
        if (freshSlots == 4) fullFrames++
        if (freshSlots == 0) emptyFrames++
        return snapshot()
    }

    fun snapshot(): Snapshot = Snapshot(frames, recognizedSlots, fullFrames, emptyFrames)

    fun reset() {
        frames = 0
        recognizedSlots = 0
        fullFrames = 0
        emptyFrames = 0
    }
}
