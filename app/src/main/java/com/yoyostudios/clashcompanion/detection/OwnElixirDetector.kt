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
            val x = (frame.width * (0.36 + fraction * (0.94 - 0.36))).toInt()
                .coerceIn(0, frame.width - 1)
            isFilledPixel(frame.getPixel(x, y1)) && isFilledPixel(frame.getPixel(x, y2))
        }
        return ElixirBarMath.estimate(filled)
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
