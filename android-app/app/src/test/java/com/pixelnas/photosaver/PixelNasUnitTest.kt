package com.pixelnas.photosaver

import com.pixelnas.photosaver.converter.PhotoDimensionCalculator
import com.pixelnas.photosaver.util.PixelDeviceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelNasUnitTest {

    @Test
    fun `48MP iPhone RAW JPEG scales strictly within 16MP Storage Saver limit`() {
        val target = PhotoDimensionCalculator.calculate(8000, 6000, 16.0)
        assertTrue(target.wasResized)
        assertTrue(target.width.toLong() * target.height.toLong() <= 16_000_000L)
        assertEquals(4618, target.width)
        assertEquals(3464, target.height)
    }

    @Test
    fun `200MP Samsung camera uses power-of-2 inSampleSize to prevent 4GB Pixel OOM`() {
        val target = PhotoDimensionCalculator.calculate(16320, 12240, 16.0)
        assertTrue(target.wasResized)
        assertTrue("Expected inSampleSize >= 2 for 200MP OOM protection", target.sampleSize >= 2)
        assertTrue(target.width.toLong() * target.height.toLong() <= 16_000_000L)
    }

    @Test
    fun `12MP Pixel photo stays unresized`() {
        val target = PhotoDimensionCalculator.calculate(4032, 3024, 16.0)
        assertFalse(target.wasResized)
        assertEquals(1, target.sampleSize)
        assertEquals(4032, target.width)
        assertEquals(3024, target.height)
    }

    @Test
    fun `All Pixel 1 through Pixel 5 codenames detect proper Google Photos unlimited tier`() {
        val p1 = PixelDeviceDetector.detectByCodename("marlin")
        assertEquals("pixel_1", p1.profileId)
        assertFalse(p1.recommendedSaver)

        for (codename in listOf("walleye", "taimen", "blueline", "sargo", "flame", "sunfish", "bramble", "redfin")) {
            val info = PixelDeviceDetector.detectByCodename(codename)
            assertTrue("Expected Storage Saver recommended for $codename", info.recommendedSaver)
        }
    }
}
