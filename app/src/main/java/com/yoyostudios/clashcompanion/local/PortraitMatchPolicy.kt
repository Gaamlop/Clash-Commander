package com.yoyostudios.clashcompanion.local

import kotlin.math.sqrt

/** Brightness-normalized card interior, excluding the cost badge and most frame decoration. */
object PortraitMatchPolicy {
    const val COLS = 24
    const val ROWS = 28
    data class Reference(val name: String, val gray: ByteArray)

    fun describe(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val x0 = (width * .12).toInt(); val x1 = (width * .88).toInt()
        val y0 = (height * .12).toInt(); val y1 = (height * .86).toInt()
        return ByteArray(COLS * ROWS) { index ->
            val col = index % COLS; val row = index / COLS
            val left = x0 + col * (x1 - x0) / COLS
            val right = (x0 + (col + 1) * (x1 - x0) / COLS).coerceAtLeast(left + 1).coerceAtMost(width)
            val top = y0 + row * (y1 - y0) / ROWS
            val bottom = (y0 + (row + 1) * (y1 - y0) / ROWS).coerceAtLeast(top + 1).coerceAtMost(height)
            var sum = 0L; var count = 0
            for (y in top until bottom) for (x in left until right) {
                val p = pixels[y * width + x]
                sum += (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
                count++
            }
            (sum / count.coerceAtLeast(1)).toByte()
        }
    }

    fun match(query: ByteArray, references: List<Reference>, allowed: Set<String>): String? {
        if (query.size != COLS * ROWS) return null
        val q = normalized(query) ?: return null
        val scores = mutableMapOf<String, Double>()
        for (reference in references) {
            if (reference.name !in allowed || reference.gray.size != query.size) continue
            val r = normalized(reference.gray) ?: continue
            val score = q.indices.sumOf { q[it] * r[it] }
            scores[reference.name] = maxOf(scores[reference.name] ?: -1.0, score)
        }
        val ordered = scores.entries.sortedByDescending { it.value }
        if (ordered.size < 2) return null
        return ordered[0].key.takeIf { ordered[0].value >= .90 && ordered[0].value - ordered[1].value >= .12 }
    }

    private fun normalized(bytes: ByteArray): DoubleArray? {
        val mean = bytes.sumOf { it.toInt() and 255 }.toDouble() / bytes.size
        val values = DoubleArray(bytes.size) { (bytes[it].toInt() and 255) - mean }
        val norm = sqrt(values.sumOf { it * it })
        if (norm < 8 * sqrt(bytes.size.toDouble())) return null
        return DoubleArray(values.size) { values[it] / norm }
    }
}
