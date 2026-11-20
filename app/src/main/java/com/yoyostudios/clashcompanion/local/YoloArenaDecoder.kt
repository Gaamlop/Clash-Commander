package com.yoyostudios.clashcompanion.local

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Identical square letterbox contract to the pinned model export. Coordinates are full-screen fractions. */
data class ArenaLetterbox(val frameWidth: Int, val frameHeight: Int, val size: Int = 640) {
    val cropX = (frameWidth * .020f).roundToInt()
    val cropY = (frameHeight * .035f).roundToInt()
    val cropWidth = (frameWidth * .960f).roundToInt()
    val cropHeight = (frameHeight * .684f).roundToInt()
    private val gain = min(size.toFloat() / cropWidth, size.toFloat() / cropHeight)
    val resizedWidth = (cropWidth * gain).roundToInt()
    val resizedHeight = (cropHeight * gain).roundToInt()
    val padX = (size - resizedWidth) / 2
    val padY = (size - resizedHeight) / 2

    fun decode(label: String, confidence: Float, cx: Float, cy: Float, w: Float, h: Float): LocalUnit? {
        if (!label.endsWith("_0") && !label.endsWith("_1")) return null
        if (listOf(cx, cy, w, h, confidence).any { !it.isFinite() } || w <= 0 || h <= 0) return null
        val x = ((cx - padX) / resizedWidth * cropWidth + cropX) / frameWidth
        val y = ((cy - padY) / resizedHeight * cropHeight + cropY) / frameHeight
        if (x !in .02f.. .98f || y !in .06f.. .76f) return null
        return LocalUnit(label.dropLast(2), label.endsWith("_1"), confidence, x, y,
            w / resizedWidth * cropWidth / frameWidth, h / resizedHeight * cropHeight / frameHeight)
    }
}

object YoloArenaDecoder {
    fun decode(output: Array<FloatArray>, labels: List<String>, geometry: ArenaLetterbox,
               confidence: Float = .50f): List<LocalUnit> {
        require(output.size == labels.size + 4 && output.isNotEmpty())
        val count = output[0].size
        require(output.all { it.size == count })
        val boxes = ArrayList<LocalUnit>()
        for (i in 0 until count) {
            var best = -1
            var score = confidence
            for (c in labels.indices) {
                val candidate = output[c + 4][i]
                if (candidate.isFinite() && candidate > score) { score = candidate; best = c }
            }
            if (best < 0) continue
            val opposite = labels.indexOf(labels[best].dropLast(1) + if (labels[best].endsWith("1")) "0" else "1")
            if (opposite >= 0 && score - output[opposite + 4][i] < .10f) continue
            geometry.decode(labels[best], score, output[0][i], output[1][i], output[2][i], output[3][i])
                ?.let(boxes::add)
        }
        val kept = ArrayList<LocalUnit>()
        for (box in boxes.sortedByDescending { it.confidence }) {
            if (boxes.any { it.enemy != box.enemy && kotlin.math.abs(it.confidence - box.confidence) < .12f && iou(it, box) > .5f }) continue
            // Class-agnostic suppression also prevents two side labels for one body.
            if (kept.none { iou(it, box) > .50f }) kept.add(box)
            if (kept.size >= 100) break
        }
        return kept
    }

    private fun iou(a: LocalUnit, b: LocalUnit): Float {
        val w = max(0f, min(a.x + a.width / 2, b.x + b.width / 2) - max(a.x - a.width / 2, b.x - b.width / 2))
        val h = max(0f, min(a.y + a.height / 2, b.y + b.height / 2) - max(a.y - a.height / 2, b.y - b.height / 2))
        val intersection = w * h
        return intersection / (a.width * a.height + b.width * b.height - intersection).coerceAtLeast(.000001f)
    }
}
