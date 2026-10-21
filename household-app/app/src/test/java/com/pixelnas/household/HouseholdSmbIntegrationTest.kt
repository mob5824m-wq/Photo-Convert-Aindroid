package com.pixelnas.household

import com.pixelnas.household.model.SmbAuthMode
import com.pixelnas.household.model.SmbConnectionConfig
import com.pixelnas.household.smb.SmbShareClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class HouseholdSmbIntegrationTest {

    private fun makeHighResJpegBytes(width: Int = 5000, height: Int = 3600): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(20, 148, 136)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    @Test
    fun `SmbShareClient connects over live SMB2 uploads atomically browses folders and receives converted output`() {
        val livePort = System.getenv("LIVE_SMB_TEST_PORT")?.toIntOrNull() ?: return
        val client = SmbShareClient()
        val cfg = SmbConnectionConfig(
            host = "127.0.0.1",
            port = livePort,
            shareName = "PixelNAS",
            baseSubfolder = "Incoming",
            targetProfileFolder = "StorageSaver",
            memberName = "MomPhone",
            routeToMemberSubfolder = true,
            authMode = SmbAuthMode.GUEST
        )

        // 1. Live SMB2 connection + disk space & top-folder inspection
        val space = client.testConnectionAndInspect(cfg)
        assertEquals(livePort, space.activePort)
        assertTrue("Expected non-zero total NAS bytes", space.totalBytes > 0L)
        assertTrue(space.topFolders.containsAll(listOf("Incoming", "Output", "Quarantine")))

        // 2. Atomic streaming upload (.part -> .jpg) with capture timestamp preservation
        val rawJpeg = makeHighResJpegBytes() // 18.0 MP (> 16.0 MP threshold!)
        var lastReportedBytes = 0L
        val captureTimeMs = 1_761_060_000_000L
        val (remoteUnc, bytesSent) = client.uploadStreamAtomically(
            cfg = cfg,
            filename = "PXL_20261021_153000.jpg",
            inputStream = ByteArrayInputStream(rawJpeg),
            capturedAtMillis = captureTimeMs,
            onBytesSent = { sent -> lastReportedBytes = sent }
        )
        assertEquals(rawJpeg.size.toLong(), bytesSent)
        assertEquals(rawJpeg.size.toLong(), lastReportedBytes)
        assertTrue(remoteUnc.contains("Incoming\\StorageSaver\\MomPhone\\PXL_20261021_153000.jpg"))

        // 3. Create & delete custom folder over Samba browser API
        client.createFolder(cfg, "Incoming\\FamilyTrip")
        val incEntries = client.browseFolder(cfg, "Incoming")
        assertTrue(incEntries.any { it.isDirectory && it.name == "FamilyTrip" })
        client.deleteEntry(cfg, "Incoming\\FamilyTrip", isDirectory = true)

        // 4. Wait for NAS background worker to convert 18 MP upload -> <= 16 MP JPEG inside Output/
        val deadline = System.currentTimeMillis() + 15_000L
        var convertedEntryName: String? = null
        while (System.currentTimeMillis() < deadline) {
            val outEntries = client.browseFolder(cfg, "Output")
            val match = outEntries.firstOrNull { !it.isDirectory && it.name.contains("PXL_20261021_153000") }
            if (match != null) {
                convertedEntryName = match.name
                break
            }
            Thread.sleep(250L)
        }
        assertTrue("Expected converted photo in Samba Output share", convertedEntryName != null)

        // 5. Read back converted photo directly over Samba and verify <= 16 MP downscale!
        val convertedBytes = client.readFileBytes(cfg, "Output\\$convertedEntryName")
        val decoded = ImageIO.read(ByteArrayInputStream(convertedBytes))
        val outMp = (decoded.width.toDouble() * decoded.height.toDouble()) / 1_000_000.0
        assertTrue("Expected converted image <= 16.05 MP, got $outMp MP", outMp <= 16.05)

        // Verify raw original in Incoming/StorageSaver/MomPhone was cleaned up after verification
        val remainingInStaging = client.browseFolder(cfg, "Incoming\\StorageSaver\\MomPhone")
        assertTrue(remainingInStaging.isEmpty())
    }
}
