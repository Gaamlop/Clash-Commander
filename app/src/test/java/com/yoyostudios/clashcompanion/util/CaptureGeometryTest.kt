package com.yoyostudios.clashcompanion.util

import org.junit.Assert.*
import org.junit.Test

class CaptureGeometryTest {
    @Test fun referenceDeviceKeepsCalibratedArena() {
        assertEquals(CaptureGeometry.Region(0, 200, 1080, 1700), CaptureGeometry.arenaROI(1080, 2340))
    }

    @Test fun smallerCaptureScalesCropInsteadOfDiscardingVision() {
        val roi = requireNotNull(CaptureGeometry.arenaROI(720, 1560))
        assertEquals(CaptureGeometry.Region(0, 133, 720, 1133), roi)
        assertTrue(roi.y + roi.height <= 1560)
    }

    @Test fun largerCapturePreservesArenaRatiosAndBounds() {
        val roi = requireNotNull(CaptureGeometry.arenaROI(1440, 3120))
        assertEquals(CaptureGeometry.Region(0, 266, 1440, 2267), roi)
        assertTrue(roi.y + roi.height <= 3120)
    }

    @Test fun invalidFramesHaveNoArenaCrop() {
        assertNull(CaptureGeometry.arenaROI(0, 2340))
        assertNull(CaptureGeometry.arenaROI(1080, -1))
        assertNull(CaptureGeometry.arenaROI(1, 1))
    }

    @Test fun cardSelectionUsesRecognizedArtCentersOnReferenceDevice() {
        assertEquals(
            listOf(336.5f to 2132.5f, 540f to 2132.5f, 742.5f to 2132.5f, 946.5f to 2132.5f),
            CaptureGeometry.cardSlotCenters(1080, 2340)
        )
    }

    @Test fun nothingPhoneDimensionsKeepSelectionInsideEachRecognizedSlot() {
        assertEquals(
            listOf(336.5f to 2186f, 540f to 2186f, 742.5f to 2186f, 946.5f to 2186f),
            CaptureGeometry.cardSlotCenters(1080, 2400)
        )
        assertCentersInsideRecognizedSlots(1080, 2400)
    }

    @Test fun cardSelectionStaysInsideOwnRoiAcrossCaptureSizes() {
        listOf(1080 to 2340, 720 to 1560, 1440 to 3120).forEach { (width, height) ->
            assertCentersInsideRecognizedSlots(width, height)
        }
    }

    private fun assertCentersInsideRecognizedSlots(width: Int, height: Int) {
        val rois = CaptureGeometry.cardSlotROIs(width, height)
        val centers = CaptureGeometry.cardSlotCenters(width, height)
        assertEquals(4, centers.size)
        centers.forEachIndexed { index, (x, y) ->
            val roi = rois[index]
            assertTrue(x > roi.x && x < roi.x + roi.width)
            assertTrue(y > roi.y && y < roi.y + roi.height)
            assertEquals(1, rois.count { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height })
        }
    }
}
