package com.pixelnas.photosaver

import com.pixelnas.photosaver.converter.PhotoDimensionCalculator
import com.pixelnas.photosaver.converter.StorageSaverConverter
import com.pixelnas.photosaver.util.PixelDeviceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `WhatsApp and PXL stripped EXIF filenames reconstruct historical EXIF date`() {
        val wa = StorageSaverConverter.parseDateFromFilename("WhatsApp Image 2023-07-19 at 18.45.12.jpeg")
        assertNotNull(wa)
        assertEquals("2023:07:19 18:45:12", wa!!.first)

        val pxl = StorageSaverConverter.parseDateFromFilename("PXL_20241225_083015999.jpg")
        assertNotNull(pxl)
        assertEquals("2024:12:25 08:30:15", pxl!!.first)
    }

    @Test
    fun `Google Takeout JSON sidecar extracts timestamp and GPS coordinates`() {
        val tmp = File.createTempFile("takeout_test", ".json")
        try {
            tmp.writeText(
                """
                {
                  "photoTakenTime": { "timestamp": "1712998200" },
                  "geoData": { "latitude": 34.9671, "longitude": 135.7727 }
                }
                """.trimIndent()
            )
            val parsed = StorageSaverConverter.parseTakeoutSidecar(tmp)
            assertNotNull(parsed.epochMillis)
            assertEquals(34.9671, parsed.latitude!!, 0.0001)
            assertEquals(135.7727, parsed.longitude!!, 0.0001)
        } finally {
            tmp.delete()
        }
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
