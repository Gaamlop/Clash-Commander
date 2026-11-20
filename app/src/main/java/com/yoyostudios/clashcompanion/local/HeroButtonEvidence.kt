package com.yoyostudios.clashcompanion.local

/** Standard blue/gold hero control plus magenta cost marker. Never a fixed blind tap. */
object HeroButtonEvidence {
    fun score(pixels: IntArray, width: Int, height: Int): Float {
        if (width < 24 || height < 24 || pixels.size != width * height) return 0f
        var inner = 0; var ring = 0; var blue = 0; var gold = 0; var magenta = 0
        for (y in 0 until height) for (x in 0 until width) {
            val px = (x.toFloat() / width - .5f) * 2; val py = (y.toFloat() / height - .5f) * 2
            val d = px * px + py * py
            val p = pixels[y * width + x]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (d < .55f) { inner++; if (b > 145 && g > 100 && b > r + 35) blue++ }
            if (d in .55f..1.05f) { ring++; if (r > 165 && g > 115 && b < 135 && r > b + 45) gold++ }
            if (px < -.3f && py < -.3f && r > 140 && b > 120 && g < 115 && r > g + 50) magenta++
        }
        val blueRate = blue.toFloat() / inner.coerceAtLeast(1)
        val goldRate = gold.toFloat() / ring.coerceAtLeast(1)
        val costRate = magenta.toFloat() / pixels.size
        return if (blueRate >= .10f && goldRate >= .12f && costRate >= .008f) blueRate + goldRate else 0f
    }
}
