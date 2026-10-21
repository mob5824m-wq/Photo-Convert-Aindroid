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
import com.pixelnas.photosaver.model.ConversionRecord
import com.pixelnas.photosaver.model.NasConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.round

@OptIn(UnstableApi::class)
object StorageSaverConverter {

    val PHOTO_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp"
    )

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mov", "mkv", "webm", "m4v", "3gp"
    )

    private val IGNORED_SUFFIXES = listOf(
        ".tmp", ".part", ".partial", ".crdownload", ".download", ".lock"
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

    suspend fun processFile(
        context: Context,
        sourceFile: File,
        config: NasConfig
    ): ConversionRecord = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        if (!sourceFile.isFile) {
            throw IllegalArgumentException("Source file missing: ${sourceFile.absolutePath}")
        }

        val outputDir = File(config.outputDir).apply { mkdirs() }
        val origBytes = sourceFile.length()
        val origModified = sourceFile.lastModified()
        val ext = sourceFile.extension.lowercase()

        val result = when {
            ext in PHOTO_EXTENSIONS -> processPhoto(sourceFile, outputDir, origBytes, origModified, config)
            ext in VIDEO_EXTENSIONS -> processVideo(context, sourceFile, outputDir, origBytes, origModified, config)
            else -> throw IllegalArgumentException("Unsupported format: .$ext")
        }

        val outFile = File(outputDir, result.outputFilename)
        // Strict atomic safety gate: never delete original unless verified output is non-empty!
        if (!outFile.isFile || outFile.length() <= 0L) {
            throw IllegalStateException("Output verification failed; keeping original safe in Incoming.")
        }

        var deleted = false
        if (config.deleteOriginals && sourceFile.exists()) {
            deleted = sourceFile.delete() || !sourceFile.exists()
        }

        // Notify Android MediaStore immediately so Google Photos backs up the new file!
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(outFile.absolutePath),
            null,
            null
        )

        val elapsed = System.currentTimeMillis() - startMs
        result.copy(
            originalDeleted = deleted,
            durationMs = elapsed
        )
    }

    private fun processPhoto(
        sourceFile: File,
        outputDir: File,
        origBytes: Long,
        origModified: Long,
        config: NasConfig
    ): ConversionRecord {
        val srcExif = try {
            ExifInterface(sourceFile.absolutePath)
        } catch (_: Exception) {
            null
        }
        val cameraModel = buildCameraLabel(srcExif)
        val dateTaken = srcExif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: srcExif?.getAttribute(ExifInterface.TAG_DATETIME) ?: ""
        val gpsCoords = srcExif?.latLong?.let {
            "${round(it[0] * 100000.0) / 100000.0}, ${round(it[1] * 100000.0) / 100000.0}"
        } ?: ""
        val orientation = srcExif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        // Pass 1: Bounds only (zero pixel allocation)
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
            val dest = uniqueOutputFile(outputDir, sourceFile.nameWithoutExtension, ".${sourceFile.extension}")
            val tmp = File(outputDir, ".converting_${UUID.randomUUID()}.${sourceFile.extension}")
            try {
                sourceFile.copyTo(tmp, overwrite = true)
                if (tmp.length() != origBytes) throw IllegalStateException("Copy size mismatch")
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            dest.setLastModified(origModified)
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
                outBytes = origBytes,
                savedBytes = 0L,
                savedPct = 0.0,
                exifPreserved = srcExif != null,
                cameraModel = cameraModel,
                dateTaken = dateTaken,
                gpsCoords = gpsCoords
            )
        }

        // Storage Saver Mode: sub-sample for 4GB RAM safety, rotate upright, scale <= 16.0 MP, preserve all EXIF
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

        val dest = uniqueOutputFile(outputDir, sourceFile.nameWithoutExtension, ".jpg")
        val tmp = File(outputDir, ".converting_${UUID.randomUUID()}.jpg")
        try {
            FileOutputStream(tmp).use { fos ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, fos)
                fos.flush()
            }
            finalBitmap.recycle()

            if (srcExif != null) {
                copyExifMetadata(srcExif, tmp)
            }

            // Smart guard: avoid enlarging already-compact <=16MP JPEGs
            val extLower = sourceFile.extension.lowercase()
            if (!calc.wasResized &&
                config.avoidRecompressingSmallJpegs &&
                (extLower == "jpg" || extLower == "jpeg") &&
                tmp.length() >= origBytes
            ) {
                sourceFile.copyTo(tmp, overwrite = true)
            }

            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }

        dest.setLastModified(origModified)
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
            dateTaken = dateTaken,
            gpsCoords = gpsCoords
        )
    }

    private suspend fun processVideo(
        context: Context,
        sourceFile: File,
        outputDir: File,
        origBytes: Long,
        origModified: Long,
        config: NasConfig
    ): ConversionRecord {
        val (origW, origH) = probeVideoDimensions(sourceFile)
        val shortSide = if (origW > 0 && origH > 0) min(origW, origH) else 0
        val extLower = sourceFile.extension.lowercase()
        val needsTranscode = config.storageSaverEnabled &&
            config.compressVideos &&
            (shortSide > config.maxVideoShortSide || (extLower != "mp4" && extLower != "m4v"))

        if (!needsTranscode) {
            val dest = uniqueOutputFile(outputDir, sourceFile.nameWithoutExtension, ".${sourceFile.extension}")
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
            dest.setLastModified(origModified)
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

        // Hardware MediaCodec 1080p MP4 transcode via AndroidX Media3 Transformer
        val dest = uniqueOutputFile(outputDir, sourceFile.nameWithoutExtension, ".mp4")
        val tmp = File(outputDir, ".converting_${UUID.randomUUID()}.mp4")
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(sourceFile)))
                        .setEffects(
                            Effects(
                                emptyList(),
                                listOf(Presentation.createForShortSide(config.maxVideoShortSide))
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

        dest.setLastModified(origModified)
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

    private fun copyExifMetadata(sourceExif: ExifInterface, targetFile: File) {
        val outExif = ExifInterface(targetFile.absolutePath)
        for (tag in PRESERVED_EXIF_TAGS) {
            val v = sourceExif.getAttribute(tag)
            if (v != null) {
                outExif.setAttribute(tag, v)
            }
        }
        outExif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        outExif.saveAttributes()
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
            Triple("IMG_24MP_Sample_LakeLouise.jpg", 6000 to 4000, Color.rgb(29, 78, 137)),
            Triple("PXL_20MP_Sample_Autumn.jpg", 5184 to 3888, Color.rgb(180, 83, 9))
        )
        for ((name, dims, baseColor) in specs) {
            val bmp = Bitmap.createBitmap(dims.first, dims.second, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(baseColor)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 140f
            }
            canvas.drawText("Pixel Photo NAS Sample ${dims.first}x${dims.second}", 160f, 360f, paint)
            val tmp = File(incomingDir, ".tmp_${UUID.randomUUID()}.jpg")
            val dest = File(incomingDir, name)
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            ExifInterface(tmp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_MAKE, "Google")
                setAttribute(ExifInterface.TAG_MODEL, "Pixel 9 Pro Sample")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:20 16:30:00")
                saveAttributes()
            }
            tmp.renameTo(dest)
            created.add(dest.name)
        }
        return created
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
        return round(((w.toLong() * h.toLong()).toDouble() / 1_000_000.0) * 100.0) / 100.0
    }
}
