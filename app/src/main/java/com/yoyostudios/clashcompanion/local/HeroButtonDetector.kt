package com.yoyostudios.clashcompanion.local

import android.graphics.Bitmap

object HeroButtonDetector {
    fun read(frame: Bitmap): HeroButton? {
        var best = .0f; var found: HeroButton? = null
        // Supercell allows the button on either bottom corner. Search that region,
        // and require visual evidence in the same frame rather than an assumed setting.
        for (fraction in listOf(.065f, .08f)) {
        val radius = (frame.width * fraction).toInt()
        val size = radius * 2
        val pixels = IntArray(size * size)
        for (nx in listOf(.09f, .12f, .88f, .91f)) for (ny in listOf(.785f, .805f, .825f)) {
            val left = (nx * frame.width).toInt() - radius
            val top = (ny * frame.height).toInt() - radius
            if (left < 0 || top < 0 || left + size > frame.width || top + size > frame.height) continue
            frame.getPixels(pixels, 0, size, left, top, size, size)
            val score = HeroButtonEvidence.score(pixels, size, size)
            if (score > best) { best = score; found = HeroButton(nx, ny) }
        }
        }
        return found
    }
}
