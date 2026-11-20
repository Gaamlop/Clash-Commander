package com.yoyostudios.clashcompanion.util

/** Approximate placement anchors annotated on the owner's 576x1280 match captures.
 * Card recognition crops retain their separate reference. These anchors need live verification.
 */
object ArenaPlacementCalibration {
    private val anchors = mapOf(
        "left_bridge" to (112f to 620f), "right_bridge" to (464f to 620f),
        "center" to (288f to 700f),
        "behind_left" to (125f to 860f), "behind_right" to (455f to 860f),
        "front_left" to (160f to 650f), "front_right" to (416f to 650f),
        "back_center" to (288f to 940f),
        "opponent_left" to (125f to 335f), "opponent_right" to (455f to 335f),
        "opponent_king" to (288f to 200f),
        "my_left" to (125f to 800f), "my_right" to (455f to 800f),
        "my_king" to (288f to 885f)
    )
    fun position(zone: String, width: Int, height: Int): Pair<Float, Float> {
        require(width > 0 && height > width) { "Portrait capture required" }
        val point = requireNotNull(anchors[zone]) { "Unknown placement anchor" }
        return point.first / 576f * width to point.second / 1280f * height
    }
}
