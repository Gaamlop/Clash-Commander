package com.yoyostudios.clashcompanion.util

/** Pixel geometry derived from the calibrated 1080 x 2340 portrait capture. */
object CaptureGeometry {
    data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

    private const val REFERENCE_WIDTH = 1080f
    private const val REFERENCE_HEIGHT = 2340f
    private val referenceCardROIs = listOf(
        Region(248, 2025, 177, 215),
        Region(452, 2025, 176, 215),
        Region(654, 2025, 177, 215),
        Region(858, 2025, 177, 215)
    )

    /** Shared by card recognition and selection, including integer crop rounding. */
    fun cardSlotROIs(frameWidth: Int, frameHeight: Int): List<Region> {
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()
        val sx = frameWidth / REFERENCE_WIDTH
        val sy = frameHeight / REFERENCE_HEIGHT
        return referenceCardROIs.map { roi ->
            Region(
                (roi.x * sx).toInt(), (roi.y * sy).toInt(),
                (roi.width * sx).toInt(), (roi.height * sy).toInt()
            )
        }
    }

    fun cardSlotCenters(frameWidth: Int, frameHeight: Int): List<Pair<Float, Float>> =
        cardSlotROIs(frameWidth, frameHeight).map { roi ->
            (roi.x + roi.width / 2f) to (roi.y + roi.height / 2f)
        }

    fun arenaROI(frameWidth: Int, frameHeight: Int): Region? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val top = (200.0 * frameHeight / 2340.0).toInt()
        val bottom = (1900.0 * frameHeight / 2340.0).toInt().coerceAtMost(frameHeight)
        if (bottom <= top) return null
        return Region(0, top, frameWidth, bottom - top)
    }
}
