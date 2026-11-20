package com.yoyostudios.clashcompanion.local

import org.junit.Assert.*
import org.junit.Test

class YoloArenaDecoderTest {
    private val labels = listOf("hog-rider_1", "hog-rider_0", "king-tower_0")
    private fun prediction() = Array(7) { FloatArray(2) }.apply {
        this[0].fill(320f); this[1].fill(400f); this[2].fill(50f); this[3].fill(60f)
        this[4][0] = .9f; this[4][1] = .8f
    }
    @Test fun duplicateAnchorsAreOneUnitAndPaddingMapsToScreenCoordinates() {
        val geo = ArenaLetterbox(1080, 2400)
        val boxes = YoloArenaDecoder.decode(prediction(), labels, geo)
        assertEquals(1, boxes.size); assertEquals("hog-rider", boxes[0].name)
        assertTrue(boxes[0].enemy)
        assertEquals(.5f, boxes[0].x, .003f)
        assertTrue(boxes[0].y in .4f.. .5f)
    }
    @Test fun ambiguousSidesNaNAndWeakScoresDoNotBecomeEnemyThreats() {
        val p = prediction(); p[5][0] = .85f; p[4][1] = Float.NaN
        assertTrue(YoloArenaDecoder.decode(p, labels, ArenaLetterbox(1080, 2400)).isEmpty())
        val low = prediction(); low[4].fill(.49f)
        assertTrue(YoloArenaDecoder.decode(low, labels, ArenaLetterbox(1080, 2400)).isEmpty())
    }
    @Test fun malformedShapeFailsExplicitly() {
        assertTrue(runCatching { YoloArenaDecoder.decode(Array(6) { FloatArray(2) }, labels, ArenaLetterbox(1080, 2400)) }.isFailure)
    }
    @Test fun geometryRescalesConsistentlyAcrossThreePortraitResolutions() {
        val points = listOf(576 to 1280, 1080 to 2400, 1440 to 3200).map { (w, h) ->
            ArenaLetterbox(w, h).decode("king-tower_0", .9f, 320f, 600f, 60f, 70f)!!
        }
        assertTrue(points.all { kotlin.math.abs(it.x - points[0].x) < .003f && kotlin.math.abs(it.y - points[0].y) < .003f })
    }
}
