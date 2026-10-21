package com.pixelnas.household

import com.pixelnas.household.model.SmbAuthMode
import com.pixelnas.household.model.SmbConnectionConfig
import com.pixelnas.household.smb.SmbShareClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class HouseholdSmbIntegrationTest {

    /** Reads exact (width, height) directly from standard JPEG SOF0/SOF1/SOF2 frame header bytes. */
    private fun parseJpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) {
            return null
        }
        var i = 2
        while (i + 8 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) {
                i++
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF) {
                i++
                continue
            }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) {
                i += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) break
            val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker in listOf(0xC0, 0xC1, 0xC2) && i + 8 < bytes.size) {
                val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return w to h
            }
            if (segLen < 2) break
            i += 2 + segLen
        }
        return null
    }

    @Test
    fun `SmbShareClient connects over live SMB2 uploads atomically browses folders and receives converted output`() {
        val livePort = System.getenv("LIVE_SMB_TEST_PORT")?.toIntOrNull() ?: return
        val sampleJpegPath = System.getenv("LIVE_SMB_TEST_JPEG") ?: return
        val rawJpeg = File(sampleJpegPath).readBytes()
        val origDims = parseJpegDimensions(rawJpeg)
        assertNotNull("Expected valid input JPEG", origDims)
        assertTrue("Expected input > 16 MP", origDims!!.first.toLong() * origDims.second.toLong() > 16_000_000L)

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

        // 4. Wait for NAS background worker to convert 19.7 MP upload -> <= 16 MP JPEG inside Output/
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
        assertNotNull("Expected converted photo in Samba Output share", convertedEntryName)

        // 5. Read back converted photo directly over Samba and verify <= 16 MP downscale!
        val convertedBytes = client.readFileBytes(cfg, "Output\\$convertedEntryName")
        val outDims = parseJpegDimensions(convertedBytes)
        assertNotNull("Expected valid converted JPEG", outDims)
        val outMp = (outDims!!.first.toDouble() * outDims.second.toDouble()) / 1_000_000.0
        assertTrue("Expected converted image <= 16.05 MP, got $outMp MP", outMp <= 16.05)

        // Wait briefly for worker's post-conversion SQLite commit + staging unlink & folder prune
        val cleanupDeadline = System.currentTimeMillis() + 10_000L
        var cleanedUp = false
        while (System.currentTimeMillis() < cleanupDeadline) {
            val saverEntries = client.browseFolder(cfg, "Incoming\\StorageSaver")
            if (saverEntries.none { it.name.contains("PXL_20261021_153000") || it.name == "MomPhone" }) {
                cleanedUp = true
                break
            }
            Thread.sleep(150L)
        }
        assertTrue("Expected raw original & staging folder cleaned up after SQLite commit", cleanedUp)
    }
}
