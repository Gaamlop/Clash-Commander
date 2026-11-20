package com.yoyostudios.clashcompanion.detection

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Reads ONLY the player's purple elixir bar in a full-screen portrait capture.
 *
 * Calibrated against the owner's 1080x2400 replay (full-bar frames at 12, 45,
 * 75 and 108 seconds). Partial-bar estimates are EXPERIMENTAL until a separate
 * labeled replay supplies ground truth for 1..9. Return null, never invent an
 * elixir count, when the bar colors/geometry are uncertain.
 */
internal object OwnElixirDetector {
    private const val SAMPLES = 40

    fun read(frame: Bitmap): Int? {
        if (frame.isRecycled || frame.width < 480 || frame.height < 1000 ||
            frame.height <= frame.width) return null

        // Anchored to the bottom of the captured screen, not Android displayMetrics.
        val y1 = (frame.height * (2356.0 / 2400)).toInt().coerceIn(0, frame.height - 1)
        val y2 = (frame.height * (2365.0 / 2400)).toInt().coerceIn(0, frame.height - 1)
        val filled = BooleanArray(SAMPLES) { index ->
            val fraction = (index + 0.5) / SAMPLES
            // Skip the numeral/droplet at the bar's left edge, but compensate
            // for that omitted segment instead of treating this as the whole bar.
            val x = (frame.width * (0.29 + fraction * (0.96 - 0.29))).toInt()
                .coerceIn(0, frame.width - 1)
            isFilledPixel(frame.getPixel(x, y1)) && isFilledPixel(frame.getPixel(x, y2))
        }
        return ElixirBarMath.estimateSegment(filled)
    }

    private fun isFilledPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 255
        val g = (pixel shr 8) and 255
        val b = pixel and 255
        return r > 125 && b > 125 && r > g + 55 && b > g + 55 && abs(r - b) < 90
    }
}

/** Pure Kotlin logic, independently unit testable without Android image APIs. */
internal object ElixirBarMath {
    /** Conservative lower bound on the complete bar, excluding its overlaid number. */
    fun estimateSegment(filled: BooleanArray): Int? {
        if (filled.size < 20 || !filled[0] || filled.count { it } < 2) return null
        val last = filled.indexOfLast { it }
        val holes = (0..last).count { !filled[it] }
        if (holes > 2) return null
        val end = .29 + (filled.count { it } - .5) / filled.size * (.96 - .29)
        // At most nine: the obscured/unsampled pixels cannot prove exactly ten.
        return (((end - .222) / (.965 - .222)) * 10).toInt().coerceIn(0, 9)
    }

    fun estimate(filled: BooleanArray): Int? {
        if (filled.size < 20) return null
        val filledCount = filled.count { it }
        // An empty/obscured/non-game frame cannot prove zero elixir.
        if (filledCount < 4 || !filled[0] || !filled[1]) return null

        val lastFilled = filled.indexOfLast { it }
        // Reject discontinuous fills: a stray magenta UI element is not elixir.
        val holes = (0..lastFilled).count { !filled[it] }
        if (holes > 2) return null

        // A full 10 requires the ENTIRE sampled bar to be filled.
        if (filledCount == filled.size) return 10

        // Conservative floor: never round up to afford a card prematurely.
        return (filledCount * 10 / filled.size).coerceIn(0, 9)
    }
}
