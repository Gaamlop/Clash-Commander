package com.yoyostudios.clashcompanion.util

import android.content.Context
import android.util.DisplayMetrics

/**
 * Coordinates scaled relative to screen size.
 * Calibrated on Samsung Galaxy A35 (1080 x 2340) then stored as ratios
 * so they work on any device with the same aspect ratio.
 */
object Coordinates {

    // Reference device: 1080 x 2340
    private const val REF_W = 1080f
    private const val REF_H = 2340f

    private var screenW: Float = REF_W
    private var screenH: Float = REF_H

    fun init(context: Context) {
        val dm: DisplayMetrics = context.resources.displayMetrics
        screenW = dm.widthPixels.toFloat()
        screenH = dm.heightPixels.toFloat()
    }

    /**
     * Android's app displayMetrics may exclude system bars while the actual
     * captured match includes the entire physical screen. Calibrate card taps
     * to the same frame pixels used by the classifier after a match is found.
     */
    fun updateFromCapture(frameWidth: Int, frameHeight: Int) {
        if (frameWidth > 0 && frameHeight > frameWidth) {
            screenW = frameWidth.toFloat()
            screenH = frameHeight.toFloat()
        }
    }

    private fun x(refX: Float): Float = refX / REF_W * screenW
    private fun y(refY: Float): Float = refY / REF_H * screenH
    private fun pos(refX: Float, refY: Float): Pair<Float, Float> = Pair(x(refX), y(refY))

    /** Scale a reference width dimension to actual screen pixels */
    private fun dimW(refDim: Float): Int = (refDim / REF_W * screenW).toInt()

    /** Scale a reference height dimension to actual screen pixels */
    private fun dimH(refDim: Float): Int = (refDim / REF_H * screenH).toInt()

    // ── Card Slot ROIs for classifier cropping ────────────────────────────
    // Bounding boxes for the card ART area (not the full card frame).
    // CaptureGeometry holds the single reference used for both crops and taps.
    //
    // IMPORTANT: These use RAW pixel values for the 1080x2340 screen capture
    // frame, NOT scaled via displayMetrics. ScreenCaptureService captures the
    // full resolution including nav bar, while displayMetrics excludes it.
    // Pass actual bitmap dimensions when cropping a captured frame.

    data class CardSlotROI(val x: Int, val y: Int, val w: Int, val h: Int)

    private val REF_NEXT_ROI = CardSlotROI(61, 2215, 79, 110)

    /**
     * Get card slot ROIs scaled to actual frame dimensions.
     * Call with the actual captured frame size, NOT displayMetrics.
     */
    fun getCardSlotROIs(frameW: Int, frameH: Int): List<CardSlotROI> =
        CaptureGeometry.cardSlotROIs(frameW, frameH).map { roi ->
            CardSlotROI(roi.x, roi.y, roi.width, roi.height)
        }

    /** Get next-card ROI scaled to actual frame dimensions. */
    fun getNextCardROI(frameW: Int, frameH: Int): CardSlotROI {
        if (frameW == REF_W.toInt() && frameH == REF_H.toInt()) return REF_NEXT_ROI
        val sx = frameW / REF_W
        val sy = frameH / REF_H
        return CardSlotROI(
            (REF_NEXT_ROI.x * sx).toInt(), (REF_NEXT_ROI.y * sy).toInt(),
            (REF_NEXT_ROI.w * sx).toInt(), (REF_NEXT_ROI.h * sy).toInt()
        )
    }

    /** Convenience: get ROIs using raw reference values (for 1080x2340 frames) */
    val CARD_SLOT_ROIS: List<CardSlotROI> get() = getCardSlotROIs(REF_W.toInt(), REF_H.toInt())
    val NEXT_CARD_ROI: CardSlotROI get() = REF_NEXT_ROI

    // ── Arena Crop Region (for Roboflow detection) ────────────────
    // Excludes top UI bar and bottom card hand area.
    // Values are raw 1080x2340 reference pixels.
    const val ARENA_CROP_TOP = 200
    const val ARENA_CROP_BOTTOM = 1900

    /** Arena crop in actual capture pixels; callers retain its offset for detections. */
    fun getArenaROI(frameWidth: Int, frameHeight: Int): CardSlotROI? =
        CaptureGeometry.arenaROI(frameWidth, frameHeight)?.let {
            CardSlotROI(it.x, it.y, it.width, it.height)
        }

    // ── Card Slot Centers (bottom of screen, 4 visible cards) ──
    val CARD_SLOT_1 get() = CARD_SLOTS[0]
    val CARD_SLOT_2 get() = CARD_SLOTS[1]
    val CARD_SLOT_3 get() = CARD_SLOTS[2]
    val CARD_SLOT_4 get() = CARD_SLOTS[3]

    val CARD_SLOTS get() = CaptureGeometry.cardSlotCenters(screenW.toInt(), screenH.toInt())

    // Arena anchors are calibrated separately from the card-art crops.
    private fun placement(zone: String) = ArenaPlacementCalibration.position(zone, screenW.toInt(), screenH.toInt())
    val LEFT_BRIDGE get() = placement("left_bridge")
    val RIGHT_BRIDGE get() = placement("right_bridge")
    val CENTER get() = placement("center")
    val BEHIND_LEFT get() = placement("behind_left")
    val BEHIND_RIGHT get() = placement("behind_right")
    val FRONT_LEFT get() = placement("front_left")
    val FRONT_RIGHT get() = placement("front_right")
    val BACK_CENTER get() = placement("back_center")
    val OPP_LEFT_TOWER get() = placement("opponent_left")
    val OPP_RIGHT_TOWER get() = placement("opponent_right")
    val OPP_KING get() = placement("opponent_king")
    val MY_LEFT_TOWER get() = placement("my_left")
    val MY_RIGHT_TOWER get() = placement("my_right")
    val MY_KING get() = placement("my_king")

    // Map of zone name aliases to coordinates
    val ZONE_MAP: Map<String, Pair<Float, Float>> get() = mapOf(
        // Bridge zones
        "left_bridge" to LEFT_BRIDGE,
        "left" to LEFT_BRIDGE,
        "left bridge" to LEFT_BRIDGE,
        "bridge left" to LEFT_BRIDGE,
        "right_bridge" to RIGHT_BRIDGE,
        "right" to RIGHT_BRIDGE,
        "right bridge" to RIGHT_BRIDGE,
        "bridge right" to RIGHT_BRIDGE,
        "bridge" to LEFT_BRIDGE,
        // STT misrecognitions for "right"
        "wright" to RIGHT_BRIDGE,
        "wright bridge" to RIGHT_BRIDGE,
        "write" to RIGHT_BRIDGE,
        "write bridge" to RIGHT_BRIDGE,
        "rite" to RIGHT_BRIDGE,
        "rye" to RIGHT_BRIDGE,
        "rye tower" to OPP_RIGHT_TOWER,  // STT: "right tower" -> "rye tower"
        "rite tower" to OPP_RIGHT_TOWER,
        "aright" to RIGHT_BRIDGE,   // STT: "pekka right" -> "pek aright"
        "upright" to OPP_RIGHT_TOWER, // STT: "top right" -> "upright"
        "topright" to OPP_RIGHT_TOWER, // STT: "top right" no space
        "topleft" to OPP_LEFT_TOWER,   // STT: "top left" no space
        // STT misrecognitions for "left"
        "lift" to LEFT_BRIDGE,      // STT: "left" -> "lift"
        "loft" to LEFT_BRIDGE,
        // Center
        "center" to CENTER,
        "middle" to CENTER,
        "mid" to CENTER,
        "centre" to CENTER,
        // Behind / back (defensive)
        "behind" to BACK_CENTER,
        "behind_left" to BEHIND_LEFT,
        "back left" to BEHIND_LEFT,
        "behind left" to BEHIND_LEFT,
        "behind_right" to BEHIND_RIGHT,
        "back right" to BEHIND_RIGHT,
        "behind right" to BEHIND_RIGHT,
        "back_center" to BACK_CENTER,
        "back" to BACK_CENTER,
        "back center" to BACK_CENTER,
        "behind king" to BACK_CENTER,
        // Front (offensive)
        "front_left" to FRONT_LEFT,
        "front left" to FRONT_LEFT,
        "front_right" to FRONT_RIGHT,
        "front right" to FRONT_RIGHT,
        // Bottom (same as behind/back)
        "bottom left" to BEHIND_LEFT,
        "bottom right" to BEHIND_RIGHT,
        // Top (same as front/opponent side)
        "top left" to OPP_LEFT_TOWER,
        "top right" to OPP_RIGHT_TOWER,
        // Opponent towers — "left tower" defaults to ENEMY (you'd never fireball your own)
        "left tower" to OPP_LEFT_TOWER,
        "right tower" to OPP_RIGHT_TOWER,
        "his left tower" to OPP_LEFT_TOWER,
        "his right tower" to OPP_RIGHT_TOWER,
        "his king" to OPP_KING,
        "his left" to OPP_LEFT_TOWER,
        "his right" to OPP_RIGHT_TOWER,
        "enemy left" to OPP_LEFT_TOWER,
        "enemy right" to OPP_RIGHT_TOWER,
        "enemy tower" to OPP_LEFT_TOWER,
        "their king" to OPP_KING,
        "opponent king" to OPP_KING,
        // Player towers — must say "my" explicitly
        "my left tower" to MY_LEFT_TOWER,
        "my right tower" to MY_RIGHT_TOWER,
        "my king" to MY_KING,
        "my left" to MY_LEFT_TOWER,
        "my right" to MY_RIGHT_TOWER,
        // Defend — same as back left/right (defensive placement near your towers)
        "defend left" to BEHIND_LEFT,
        "defend right" to BEHIND_RIGHT,
        "defend" to BACK_CENTER,
        "defense left" to BEHIND_LEFT,
        "defense right" to BEHIND_RIGHT,
        "defense" to BACK_CENTER,
        // STT hallucinations for "defend right/left"
        "fenderite" to BEHIND_RIGHT,   // STT: "defend right" -> "fenderite"
        "fender right" to BEHIND_RIGHT,
        "fender left" to BEHIND_LEFT,
        "fender" to BACK_CENTER,
        "defened left" to BEHIND_LEFT,  // STT: "defend left" -> "defened left"
        "defened right" to BEHIND_RIGHT,
        "defended left" to BEHIND_LEFT,
        "defended right" to BEHIND_RIGHT,
        "defended back" to BACK_CENTER,
        "defended" to BACK_CENTER,
        "fund right" to BEHIND_RIGHT,  // STT: "defend right" -> "fund right"
        "fund left" to BEHIND_LEFT,
        "the fund right" to BEHIND_RIGHT, // STT: "defend right" -> "the fund right"
        "the fund left" to BEHIND_LEFT,
        "friend right" to BEHIND_RIGHT, // STT: "defend right" -> "the friend right"
        "friend left" to BEHIND_LEFT,
        "the friend right" to BEHIND_RIGHT,
        "the friend left" to BEHIND_LEFT,
        // Bare "king" = enemy king (you'd never target your own king)
        "king" to OPP_KING,
        "king tower" to OPP_KING,
    )
}
