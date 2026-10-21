package com.pixelnas.photosaver

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.pixelnas.photosaver.converter.PhotoDimensionCalculator
import com.pixelnas.photosaver.converter.StorageSaverConverter
import com.pixelnas.photosaver.model.NasConfig
import com.pixelnas.photosaver.network.NasSmbServer
import com.pixelnas.photosaver.util.PixelDeviceDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList

class PixelNasUnitTest {

    @Test
    fun `50MP Pixel Pro JPEG scales strictly within 16MP Storage Saver limit`() {
        val target = PhotoDimensionCalculator.calculate(8160, 6120, 16.0)
        assertTrue(target.wasResized)
        assertTrue(target.width.toLong() * target.height.toLong() <= 16_000_000L)
        assertEquals(4618, target.width)
        assertEquals(3464, target.height)
    }

    @Test
    fun `200MP Samsung Galaxy camera uses power-of-2 inSampleSize to prevent 4GB Pixel OOM`() {
        val target = PhotoDimensionCalculator.calculate(16320, 12240, 16.0)
        assertTrue(target.wasResized)
        assertTrue("Expected inSampleSize >= 2 for 200MP OOM protection", target.sampleSize >= 2)
        assertTrue(target.width.toLong() * target.height.toLong() <= 16_000_000L)
    }

    @Test
    fun `Per-Folder Profiles match StorageSaver Original and Family incoming subfolders`() {
        val cfg = NasConfig()
        val incRoot = File("/storage/emulated/0/PixelNAS/Incoming")

        val saver = cfg.matchFolderProfile(incRoot, File(incRoot, "StorageSaver/PXL_20261021_120000.jpg"))
        assertNotNull(saver)
        assertTrue(saver!!.storageSaverEnabled)

        val orig = cfg.matchFolderProfile(incRoot, File(incRoot, "Original/SAM_20261021_120000.jpg"))
        assertNotNull(orig)
        assertFalse(orig!!.storageSaverEnabled)

        val fam = cfg.matchFolderProfile(incRoot, File(incRoot, "family/IMG_20261021_120000.jpg"))
        assertNotNull(fam)
        assertEquals("Family", fam!!.outputSubfolder)

        val rootDefault = cfg.matchFolderProfile(incRoot, File(incRoot, "direct_root.jpg"))
        assertNull(rootDefault)
    }

    @Test
    fun `WhatsApp Samsung and PXL stripped EXIF filenames reconstruct historical EXIF date`() {
        val wa = StorageSaverConverter.parseDateFromFilename("WhatsApp Image 2023-07-19 at 18.45.12.jpeg")
        assertNotNull(wa)
        assertEquals("2023:07:19 18:45:12", wa!!.first)

        val pxl = StorageSaverConverter.parseDateFromFilename("PXL_20241225_083015999.jpg")
        assertNotNull(pxl)
        assertEquals("2024:12:25 08:30:15", pxl!!.first)

        val sam = StorageSaverConverter.parseDateFromFilename("SAM_20250504_194501.jpg")
        assertNotNull(sam)
        assertEquals("2025:05:04 19:45:01", sam!!.first)
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

    @Test
    fun `NasSmbServer handles live SMB2 Guest and NTLMv2 signed uploads from smbj`() {
        val tempRoot = Files.createTempDirectory("pixelnas_smb_test").toFile()
        val incDir = File(tempRoot, "Incoming").also {
            File(it, "StorageSaver").mkdirs()
            File(it, "Original").mkdirs()
            File(it, "Family").mkdirs()
        }
        val outDir = File(tempRoot, "Output").also { it.mkdirs() }
        val quarDir = File(tempRoot, "Quarantine").also { it.mkdirs() }
        val uploadedCallbacks = CopyOnWriteArrayList<File>()
        var authRequired = false

        val smb = NasSmbServer(
            incomingDirProvider = { incDir },
            outputDirProvider = { outDir },
            quarantineDirProvider = { quarDir },
            enabledProvider = { true },
            portProvider = { 0 },
            shareNameProvider = { "PixelNAS" },
            authEnabledProvider = { authRequired },
            authUserProvider = { "household" },
            authPasswordProvider = { "SambaPass!2026" },
            onIncomingFileClosed = { uploadedCallbacks.add(it) }
        )

        try {
            smb.startOrReload()
            assertTrue("Expected ephemeral port > 0", smb.activePort > 0)

            // 1. Live SMB2 Guest connection + directory browse + atomic .part -> .jpg upload
            val payload = ByteArray(90_000) { idx -> (idx % 251).toByte() }
            SMBClient().use { client ->
                client.connect("127.0.0.1", smb.activePort).use { conn ->
                    val session = conn.authenticate(AuthenticationContext.guest())
                    (session.connectShare("PixelNAS") as DiskShare).use { share ->
                        val rootNames = share.list("").map { it.fileName }.toSet()
                        assertTrue(rootNames.containsAll(setOf("Incoming", "Output", "Quarantine")))

                        share.openFile(
                            "Incoming\\StorageSaver\\.part_photo_1",
                            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            null
                        ).use { remoteFile ->
                            remoteFile.outputStream.use { os -> os.write(payload) }
                            remoteFile.rename("Incoming\\StorageSaver\\family_shot.jpg", true)
                        }

                        val readBack = share.openFile(
                            "Incoming\\StorageSaver\\family_shot.jpg",
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                        ).use { rf -> rf.inputStream.readBytes() }
                        assertArrayEquals(payload, readBack)
                    }
                }
            }
            assertTrue(uploadedCallbacks.any { it.name == "family_shot.jpg" })

            // 2. Live NTLMv2 signed auth mode
            authRequired = true
            SMBClient().use { client ->
                client.connect("127.0.0.1", smb.activePort).use { conn ->
                    val auth = AuthenticationContext("household", "SambaPass!2026".toCharArray(), "")
                    val session = conn.authenticate(auth)
                    (session.connectShare("Incoming") as DiskShare).use { share ->
                        share.mkdir("MomPhone")
                        assertTrue(share.folderExists("MomPhone"))
                    }
                }
            }
        } finally {
            smb.stop()
            tempRoot.deleteRecursively()
        }
    }
}
