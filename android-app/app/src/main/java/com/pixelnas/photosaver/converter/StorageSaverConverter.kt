package com.pixelnas.photosaver.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.pixelnas.photosaver.data.HistoryStore
import com.pixelnas.photosaver.model.ConversionRecord
import com.pixelnas.photosaver.model.NasConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

data class SidecarMetadata(
    val exifDateTime: String? = null,
    val epochMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sourceLabel: String = ""
)

@OptIn(UnstableApi::class)
object StorageSaverConverter {

    val PHOTO_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp"
    )

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mov", "mkv", "webm", "m4v", "3gp"
    )

    private val IGNORED_SUFFIXES = listOf(
        ".tmp", ".part", ".partial", ".crdownload", ".download", ".lock", ".json"
    )

    private val FILENAME_DATE_REGEXES = listOf(
        Regex("""(?:IMG|VID|PXL|SAM|DSC|MVIMG|Screenshot|Signal)[_-]?(\d{4})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})""", RegexOption.IGNORE_CASE),
        Regex("""WhatsApp\s+(?:Image|Video)\s+(\d{4})-(\d{2})-(\d{2})\s+at\s+(\d{1,2})\.(\d{2})\.(\d{2})""", RegexOption.IGNORE_CASE),
        Regex("""(20\d{2}|19\d{2})[-_](\d{2})[-_](\d{2})[\s_T-]+(\d{2})[.:_-](\d{2})[.:_-](\d{2})"""),
        Regex("""(20\d{2}|19\d{2})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})""")
    )

    private val PRESERVED_EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    fun isSupportedMedia(file: File): Boolean {
        val name = file.name
        if (name.isBlank() || name.startsWith(".") || name.startsWith("~")) return false
        val lower = name.lowercase()
        if (IGNORED_SUFFIXES.any { lower.endsWith(it) } || lower.contains(".syncthing.")) {
            return false
        }
        val ext = file.extension.lowercase()
        return ext in PHOTO_EXTENSIONS || ext in VIDEO_EXTENSIONS
    }

    fun parseDateFromFilename(filename: String): Pair<String, Long>? {
        val stem = filename.substringBeforeLast('.')
        for (rx in FILENAME_DATE_REGEXES) {
            val m = rx.find(stem) ?: continue
            val g = m.groupValues
            val year = g[1].toIntOrNull() ?: continue
            val month = g[2].toIntOrNull() ?: continue
            val day = g[3].toIntOrNull() ?: continue
            val hour = g[4].toIntOrNull() ?: continue
            val minute = g[5].toIntOrNull() ?: continue
            val second = g[6].toIntOrNull() ?: continue
            if (year !in 1970..2100 || month !in 1..12 || day !in 1..31) continue
            val cal = Calendar.getInstance().apply {
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }
            val exifStr = String.format(
                Locale.US,
                "%04d:%02d:%02d %02d:%02d:%02d",
                year, month, day, hour, minute, second
            )
            return exifStr to cal.timeInMillis
        }
        return null
    }

    fun findSidecarJson(mediaFile: File): File? {
        val parent = mediaFile.parentFile ?: return null
        val candidates = listOf(
            File(parent, "${mediaFile.name}.json"),
            File(parent, "${mediaFile.name}.supplemental-metadata.json"),
            File(parent, "${mediaFile.nameWithoutExtension}.json")
        )
        return candidates.firstOrNull { it.isFile }
    }

    fun parseTakeoutSidecar(sidecarFile: File): SidecarMetadata {
        return try {
            val json = JSONObject(sidecarFile.readText())
            val tsSec = json.optJSONObject("photoTakenTime")?.optString("timestamp")?.toLongOrNull()
                ?: json.optJSONObject("creationTime")?.optString("timestamp")?.toLongOrNull()
            val epochMs = if (tsSec != null && tsSec > 0L) tsSec * 1000L else null
            val exifDt = epochMs?.let {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(it))
            }
            val geo = json.optJSONObject("geoData") ?: json.optJSONObject("geoDataExif")
            val lat = geo?.optDouble("latitude", 0.0) ?: 0.0
            val lon = geo?.optDouble("longitude", 0.0) ?: 0.0
            val hasGps = abs(lat) > 0.00001 || abs(lon) > 0.00001
            SidecarMetadata(
                exifDateTime = exifDt,
                epochMillis = epochMs,
                latitude = if (hasGps) lat else null,
                longitude = if (hasGps) lon else null,
                sourceLabel = "Takeout"
            )
        } catch (_: Exception) {
            SidecarMetadata()
        }
    }

    fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1024 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifySourceIntegrities(sourceFile: File, ext: String) {
        val size = sourceFile.length()
        if (size <= 0L) {
            throw IllegalStateException("Corrupted or empty transfer (0 bytes): ${sourceFile.name}")
        }
        if (ext in PHOTO_EXTENSIONS) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalStateException("Corrupted or truncated photo stream: ${sourceFile.name}")
            }
        } else if (ext in VIDEO_EXTENSIONS) {
            val (vw, vh) = probeVideoDimensions(sourceFile)
            if (vw <= 0 || vh <= 0) {
                throw IllegalStateException("Corrupted or unreadable video container: ${sourceFile.name}")
            }
        }
    }

    suspend fun processFile(
        context: Context,
        sourceFile: File,
        config: NasConfig,
        historyStore: HistoryStore? = null
    ): ConversionRecord = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        if (!sourceFile.isFile) {
            throw IllegalArgumentException("Source file missing: ${sourceFile.absolutePath}")
        }

        val incomingRoot = File(config.incomingDir)
        val matchedProfile = config.matchFolderProfile(incomingRoot, sourceFile)
        val activeProfileName = matchedProfile?.folderName ?: "default"
        val effectiveConfig = if (matchedProfile != null) {
            config.copy(
                storageSaverEnabled = matchedProfile.storageSaverEnabled,
                maxMegapixels = matchedProfile.maxMegapixels,
                jpegQuality = matchedProfile.jpegQuality,
                compressVideos = matchedProfile.compressVideos
            )
        } else {
            config
        }

        val baseOutputRoot = File(config.outputDir).apply { mkdirs() }
        val targetOutputDir = if (!matchedProfile?.outputSubfolder.isNullOrBlank()) {
            File(baseOutputRoot, matchedProfile!!.outputSubfolder).apply { mkdirs() }
        } else {
            baseOutputRoot
        }

        val origBytes = sourceFile.length()
        val ext = sourceFile.extension.lowercase()

        // Verify decode health upfront so truncated/corrupted uploads quarantine reliably in every mode
        verifySourceIntegrities(sourceFile, ext)

        val sidecar = if (effectiveConfig.mergeTakeoutJson) findSidecarJson(sourceFile) else null
        val takeout = if (sidecar != null) parseTakeoutSidecar(sidecar) else SidecarMetadata()
        val parsedFnDate = if (effectiveConfig.restoreExifFromFilename) parseDateFromFilename(sourceFile.name) else null

        val effectiveModified = takeout.epochMillis ?: parsedFnDate?.second ?: sourceFile.lastModified()

        // SHA-256 duplicate shield
        val sha256 = computeSha256(sourceFile)
        if (effectiveConfig.skipDuplicates && historyStore != null) {
            val existingOut = historyStore.lookupHash(sha256)
            if (existingOut != null) {
                var deleted = false
                if (effectiveConfig.deleteOriginals) {
                    deleted = sourceFile.delete() || !sourceFile.exists()
                    sidecar?.delete()
                }
                return@withContext ConversionRecord(
                    originalFilename = sourceFile.name,
                    outputFilename = existingOut,
                    mediaType = if (ext in VIDEO_EXTENSIONS) "video" else "photo",
                    mode = "duplicate_skipped",
                    profileName = activeProfileName,
                    origBytes = origBytes,
                    outBytes = 0L,
                    savedBytes = origBytes,
                    savedPct = 100.0,
                    metadataNote = "SHA-256 Duplicate Skipped",
                    originalDeleted = deleted,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
        }

        val relPrefix = try {
            val relParent = sourceFile.parentFile?.relativeTo(incomingRoot)?.invariantSeparatorsPath.orEmpty()
            val subParts = relParent.split("/").filter { it.isNotBlank() && it != "." }
            val stripped = if (matchedProfile != null && subParts.firstOrNull().equals(matchedProfile.folderName, ignoreCase = true)) {
                subParts.drop(1)
            } else {
                subParts
            }
            if (stripped.isNotEmpty()) stripped.joinToString("_") + "_" else ""
        } catch (_: Exception) {
            ""
        }
        val outputStem = "$relPrefix${sourceFile.nameWithoutExtension}"

        val result = when {
            ext in PHOTO_EXTENSIONS -> processPhoto(
                sourceFile = sourceFile,
                outputDir = targetOutputDir,
                outputStem = outputStem,
                origBytes = origBytes,
                effectiveModified = effectiveModified,
                takeout = takeout,
                fallbackFilenameExifDate = parsedFnDate?.first,
                config = effectiveConfig
            )
            ext in VIDEO_EXTENSIONS -> processVideo(
                context = context,
                sourceFile = sourceFile,
                outputDir = targetOutputDir,
                outputStem = outputStem,
                origBytes = origBytes,
                effectiveModified = effectiveModified,
                config = effectiveConfig
            )
            else -> throw IllegalArgumentException("Unsupported format: .$ext")
        }

        val outFile = File(targetOutputDir, result.outputFilename)
        if (!outFile.isFile || outFile.length() <= 0L) {
            throw IllegalStateException("Output verification failed; keeping original safe in Incoming.")
        }

        val relOutputName = outFile.relativeTo(baseOutputRoot).invariantSeparatorsPath
        historyStore?.recordHash(sha256, relOutputName)

        var deleted = false
        if (effectiveConfig.deleteOriginals && sourceFile.exists()) {
            deleted = sourceFile.delete() || !sourceFile.exists()
            sidecar?.delete()
        }

        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(outFile.absolutePath),
            null,
            null
        )

        val elapsed = System.currentTimeMillis() - startMs
        result.copy(
            outputFilename = relOutputName,
            profileName = activeProfileName,
            originalDeleted = deleted,
            durationMs = elapsed
        )
    }

    private fun processPhoto(
        sourceFile: File,
        outputDir: File,
        outputStem: String,
        origBytes: Long,
        effectiveModified: Long,
        takeout: SidecarMetadata,
        fallbackFilenameExifDate: String?,
        config: NasConfig
    ): ConversionRecord {
        val srcExif = try {
            ExifInterface(sourceFile.absolutePath)
        } catch (_: Exception) {
            null
        }
        val cameraModel = buildCameraLabel(srcExif)
        val orientation = srcExif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            throw IllegalStateException("Could not decode image header: ${sourceFile.name}")
        }

        val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        val uprightOrigW = if (swapped) boundsOpts.outHeight else boundsOpts.outWidth
        val uprightOrigH = if (swapped) boundsOpts.outWidth else boundsOpts.outHeight

        if (!config.storageSaverEnabled) {
            val dest = uniqueOutputFile(outputDir, outputStem, ".${sourceFile.extension}")
            val tmp = File(outputDir, ".converting_${UUID.randomUUID()}.${sourceFile.extension}")
            var note = ""
            try {
                sourceFile.copyTo(tmp, overwrite = true)
                if (sourceFile.extension.lowercase() in listOf("jpg", "jpeg")) {
                    note = enrichExifTags(srcExif, tmp, takeout, fallbackFilenameExifDate, keepOriginalOrientation = true)
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            dest.setLastModified(effectiveModified)
            val outExif = try { ExifInterface(dest.absolutePath) } catch (_: Exception) { srcExif }
            val mp = roundMp(uprightOrigW, uprightOrigH)
            return ConversionRecord(
                originalFilename = sourceFile.name,
                outputFilename = dest.name,
                mediaType = "photo",
                mode = "original_passthrough",
                origWidth = uprightOrigW,
                origHeight = uprightOrigH,
                origMp = mp,
                origBytes = origBytes,
                outWidth = uprightOrigW,
                outHeight = uprightOrigH,
                outMp = mp,
                outBytes = dest.length(),
                savedBytes = 0L,
                savedPct = 0.0,
                exifPreserved = true,
                cameraModel = cameraModel,
                dateTaken = outExif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: "",
                gpsCoords = formatLatLong(outExif),
                metadataNote = note
            )
        }

        val calc = PhotoDimensionCalculator.calculate(uprightOrigW, uprightOrigH, config.maxMegapixels)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calc.sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts)
            ?: throw IllegalStateException("Failed decoding bitmap: ${sourceFile.name}")

        val oriented = applyExifOrientation(decoded, orientation)
        if (oriented !== decoded) {
            decoded.recycle()
        }

        val finalBitmap = if (oriented.width != calc.width || oriented.height != calc.height) {
            Bitmap.createScaledBitmap(oriented, calc.width, calc.height, true).also {
                if (it !== oriented) oriented.recycle()
            }
        } else {
            oriented
        }

        val dest = uniqueOutputFile(outputDir, outputStem, ".jpg")
        val tmp = File(outputDir, ".converting_${UUID.randomUUID()}.jpg")
        var note = ""
        try {
            FileOutputStream(tmp).use { fos ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, fos)
                fos.flush()
            }
            finalBitmap.recycle()

            note = enrichExifTags(srcExif, tmp, takeout, fallbackFilenameExifDate, keepOriginalOrientation = false)

            val extLower = sourceFile.extension.lowercase()
            if (!calc.wasResized &&
                config.avoidRecompressingSmallJpegs &&
                (extLower == "jpg" || extLower == "jpeg") &&
                tmp.length() >= origBytes
            ) {
                sourceFile.copyTo(tmp, overwrite = true)
                note = enrichExifTags(srcExif, tmp, takeout, fallbackFilenameExifDate, keepOriginalOrientation = true)
            }

            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }

        dest.setLastModified(effectiveModified)
        val outExif = try { ExifInterface(dest.absolutePath) } catch (_: Exception) { srcExif }
        val outBytes = dest.length()
        val saved = origBytes - outBytes
        val savedPct = if (origBytes > 0) round((saved.toDouble() / origBytes.toDouble()) * 1000.0) / 10.0 else 0.0

        return ConversionRecord(
            originalFilename = sourceFile.name,
            outputFilename = dest.name,
            mediaType = "photo",
            mode = "storage_saver",
            origWidth = uprightOrigW,
            origHeight = uprightOrigH,
            origMp = roundMp(uprightOrigW, uprightOrigH),
            origBytes = origBytes,
            outWidth = calc.width,
            outHeight = calc.height,
            outMp = roundMp(calc.width, calc.height),
            outBytes = outBytes,
            savedBytes = saved,
            savedPct = savedPct,
            exifPreserved = true,
            cameraModel = cameraModel,
            dateTaken = outExif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: "",
            gpsCoords = formatLatLong(outExif),
            metadataNote = note
        )
    }

    private suspend fun processVideo(
        context: Context,
        sourceFile: File,
        outputDir: File,
        outputStem: String,
        origBytes: Long,
        effectiveModified: Long,
        config: NasConfig
    ): ConversionRecord {
        val (origW, origH) = probeVideoDimensions(sourceFile)
        val shortSide = if (origW > 0 && origH > 0) min(origW, origH) else 0
        val extLower = sourceFile.extension.lowercase()
        val needsTranscode = config.storageSaverEnabled &&
            config.compressVideos &&
            (shortSide > config.maxVideoShortSide || (extLower != "mp4" && extLower != "m4v"))

        if (!needsTranscode) {
            val dest = uniqueOutputFile(outputDir, outputStem, ".${sourceFile.extension}")
            val tmp = File(outputDir, ".converting_${UUID.randomUUID()}.${sourceFile.extension}")
            try {
                sourceFile.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            dest.setLastModified(effectiveModified)
            val mp = roundMp(origW, origH)
            return ConversionRecord(
                originalFilename = sourceFile.name,
                outputFilename = dest.name,
                mediaType = "video",
                mode = if (config.storageSaverEnabled) "storage_saver" else "original_passthrough",
                origWidth = origW,
                origHeight = origH,
                origMp = mp,
                origBytes = origBytes,
                outWidth = origW,
                outHeight = origH,
                outMp = mp,
                outBytes = dest.length(),
                savedBytes = 0L,
                savedPct = 0.0,
                exifPreserved = true
            )
        }

        val targetVideoHeight = if (origW > 0 && origH > origW) {
            (((config.maxVideoShortSide.toDouble() * origH.toDouble()) / origW.toDouble()).toInt() / 2) * 2
        } else {
            config.maxVideoShortSide
        }
        val dest = uniqueOutputFile(outputDir, outputStem, ".mp4")
        val tmp = File(outputDir, ".converting_${UUID.randomUUID()}.mp4")
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(sourceFile)))
                        .setEffects(
                            Effects(
                                emptyList(),
                                listOf(Presentation.createForHeight(targetVideoHeight))
                            )
                        )
                        .build()

                    val transformer = Transformer.Builder(context.applicationContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()

                    transformer.start(edited, tmp.absolutePath)
                    cont.invokeOnCancellation { transformer.cancel() }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }

        dest.setLastModified(effectiveModified)
        val (outW, outH) = probeVideoDimensions(dest)
        val outBytes = dest.length()
        val saved = origBytes - outBytes
        val savedPct = if (origBytes > 0) round((saved.toDouble() / origBytes.toDouble()) * 1000.0) / 10.0 else 0.0

        return ConversionRecord(
            originalFilename = sourceFile.name,
            outputFilename = dest.name,
            mediaType = "video",
            mode = "storage_saver",
            origWidth = origW,
            origHeight = origH,
            origMp = roundMp(origW, origH),
            origBytes = origBytes,
            outWidth = outW,
            outHeight = outH,
            outMp = roundMp(outW, outH),
            outBytes = outBytes,
            savedBytes = saved,
            savedPct = savedPct,
            exifPreserved = true
        )
    }

    private fun enrichExifTags(
        sourceExif: ExifInterface?,
        targetFile: File,
        takeout: SidecarMetadata,
        fallbackFilenameDate: String?,
        keepOriginalOrientation: Boolean
    ): String {
        val outExif = ExifInterface(targetFile.absolutePath)
        if (sourceExif != null) {
            for (tag in PRESERVED_EXIF_TAGS) {
                val v = sourceExif.getAttribute(tag)
                if (v != null) {
                    outExif.setAttribute(tag, v)
                }
            }
        }
        if (!keepOriginalOrientation) {
            outExif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
        }

        val notes = mutableListOf<String>()
        if (outExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()) {
            when {
                !takeout.exifDateTime.isNullOrBlank() -> {
                    outExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, takeout.exifDateTime)
                    outExif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, takeout.exifDateTime)
                    outExif.setAttribute(ExifInterface.TAG_DATETIME, takeout.exifDateTime)
                    notes.add("Takeout Date")
                }
                !fallbackFilenameDate.isNullOrBlank() -> {
                    outExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, fallbackFilenameDate)
                    outExif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, fallbackFilenameDate)
                    outExif.setAttribute(ExifInterface.TAG_DATETIME, fallbackFilenameDate)
                    notes.add("Filename Date")
                }
            }
        }

        if (outExif.latLong == null && takeout.latitude != null && takeout.longitude != null) {
            outExif.setLatLong(takeout.latitude, takeout.longitude)
            notes.add("Takeout GPS")
        }

        outExif.saveAttributes()
        return notes.joinToString(" + ")
    }

    private fun formatLatLong(exif: ExifInterface?): String {
        val ll = exif?.latLong ?: return ""
        return "${round(ll[0] * 100000.0) / 100000.0}, ${round(ll[1] * 100000.0) / 100000.0}"
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun buildCameraLabel(exif: ExifInterface?): String {
        if (exif == null) return ""
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        return when {
            make.isNotEmpty() && model.isNotEmpty() && !model.startsWith(make, ignoreCase = true) -> "$make $model"
            model.isNotEmpty() -> model
            else -> make
        }
    }

    fun probeVideoDimensions(file: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) {
            0 to 0
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun generateSamplePhotos(incomingDir: File): List<String> {
        incomingDir.mkdirs()
        val created = mutableListOf<String>()
        val specs = listOf(
            Triple(File(incomingDir, "StorageSaver/PXL_24MP_Pixel9Pro_Sample.jpg"), 6000 to 4000, Color.rgb(29, 78, 137)),
            Triple(File(incomingDir, "Original/SAM_24MP_GalaxyS24Ultra.jpg"), 6000 to 4000, Color.rgb(15, 118, 110)),
            Triple(File(incomingDir, "Family/WhatsApp Image 2023-07-19 at 18.45.12.jpg"), 5184 to 3888, Color.rgb(16, 124, 92))
        )
        for ((dest, dims, baseColor) in specs) {
            dest.parentFile?.mkdirs()
            val bmp = Bitmap.createBitmap(dims.first, dims.second, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(baseColor)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 140f
            }
            canvas.drawText("Pixel Photo NAS Android ${dims.first}x${dims.second}", 160f, 360f, paint)
            val tmp = File(dest.parentFile, ".tmp_${UUID.randomUUID()}.jpg")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            if (!dest.name.startsWith("WhatsApp")) {
                ExifInterface(tmp.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_MAKE, if (dest.name.startsWith("SAM")) "Samsung" else "Google")
                    setAttribute(ExifInterface.TAG_MODEL, if (dest.name.startsWith("SAM")) "Galaxy S24 Ultra" else "Pixel 9 Pro")
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:20 16:30:00")
                    saveAttributes()
                }
            }
            tmp.renameTo(dest)
            created.add(dest.relativeTo(incomingDir).invariantSeparatorsPath)
        }
        return created
    }

    fun seedCorruptedTransfer(incomingDir: File): String {
        val saverDir = File(incomingDir, "StorageSaver").apply { mkdirs() }
        val target = File(saverDir, "CORRUPT_Incomplete_Upload_PXL_20261021.jpg")
        val corruptedBytes = ByteArray(1400) { idx ->
            when (idx) {
                0 -> 0xFF.toByte()
                1 -> 0xD8.toByte()
                2 -> 0xFF.toByte()
                3 -> 0xE0.toByte()
                else -> 0x5A.toByte()
            }
        }
        target.writeBytes(corruptedBytes)
        return target.relativeTo(incomingDir).invariantSeparatorsPath
    }

    private fun uniqueOutputFile(dir: File, stem: String, suffix: String): File {
        var candidate = File(dir, "$stem$suffix")
        var idx = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stem}_$idx$suffix")
            idx++
        }
        return candidate
    }

    private fun roundMp(w: Int, h: Int): Double {
        if (w <= 0 || h <= 0) return 0.0
        return round(((w.toLong() * h.toLong()).toDouble() / 1_000_000.0) * 100.0) / 10.0.let { it / 10.0 }
    }
}
