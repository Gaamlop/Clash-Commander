package com.yoyostudios.clashcompanion.local

import kotlin.math.floor

/** Shared JVM/Android RGB conversion, with center-aligned bilinear resize and a 114 letterbox. */
object ArenaPreprocessor {
    fun encodeCrop(pixels: IntArray, geo: ArenaLetterbox): FloatArray {
        require(pixels.size == geo.cropWidth * geo.cropHeight)
        val area = geo.size * geo.size
        val rgb = FloatArray(3 * area) { 114f / 255f }
        for (y in 0 until geo.resizedHeight) for (x in 0 until geo.resizedWidth) {
            val sx = ((x + .5f) * geo.cropWidth / geo.resizedWidth - .5f).coerceIn(0f, (geo.cropWidth - 1).toFloat())
            val sy = ((y + .5f) * geo.cropHeight / geo.resizedHeight - .5f).coerceIn(0f, (geo.cropHeight - 1).toFloat())
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val x1 = (x0 + 1).coerceAtMost(geo.cropWidth - 1); val y1 = (y0 + 1).coerceAtMost(geo.cropHeight - 1)
            val fx = sx - x0; val fy = sy - y0
            val index = (y + geo.padY) * geo.size + x + geo.padX
            for (channel in 0..2) {
                val shift = 16 - 8 * channel
                fun value(px: Int, py: Int) = ((pixels[py * geo.cropWidth + px] shr shift) and 255).toFloat()
                val top = value(x0,y0)*(1-fx)+value(x1,y0)*fx
                val bottom = value(x0,y1)*(1-fx)+value(x1,y1)*fx
                rgb[channel * area + index] = (top*(1-fy)+bottom*fy)/255f
            }
        }
        return rgb
    }
}
