package com.yoyostudios.clashcompanion.util

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ArenaPlacementCalibrationTest {
    private val fixture = javaClass.getResourceAsStream("/nothing-phone-1-arena-landmarks.json")!!.bufferedReader().use {
        JsonParser.parseReader(it).asJsonObject
    }
    @Test fun allTroopAnchorsAreBelowObservedRiverAcrossCaptureSizes() {
        for ((w,h) in listOf(576 to 1280,1080 to 2400,720 to 1600)) {
            for(zone in listOf("left_bridge","right_bridge","center","front_left","front_right","behind_left","behind_right","back_center")) {
                val (x,y)=ArenaPlacementCalibration.position(zone,w,h)
                val displayY=y/h*fixture.get("height").asFloat
                assertTrue("$zone enters river/enemy half",displayY>fixture.get("riverBottom").asFloat)
                assertTrue("$zone outside own arena",displayY<fixture.get("ownArenaBottom").asFloat)
                assertTrue(x in 0f..w.toFloat())
            }
        }
    }
    @Test fun towerTargetsLandInsideObservedTowerBoxes() {
        for((zone,box) in mapOf("opponent_left" to "enemyLeftTower","opponent_right" to "enemyRightTower","my_left" to "ownLeftTower","my_right" to "ownRightTower")) {
            val (x,y)=ArenaPlacementCalibration.position(zone,576,1280)
            val b=fixture.getAsJsonArray(box).map { it.asFloat }
            assertTrue(x in b[0]..b[2] && y in b[1]..b[3])
        }
    }
    @Test fun invalidCaptureOrUnknownZoneCannotProduceAGesture() {
        assertThrows(IllegalArgumentException::class.java) { ArenaPlacementCalibration.position("center",0,0) }
        assertThrows(IllegalArgumentException::class.java) { ArenaPlacementCalibration.position("center",2400,1080) }
        assertThrows(IllegalArgumentException::class.java) { ArenaPlacementCalibration.position("guess",1080,2400) }
    }
}
