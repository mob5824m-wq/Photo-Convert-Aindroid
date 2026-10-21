package com.pixelnas.photosaver.model

data class PixelHardwareInfo(
    val profileId: String,
    val displayName: String,
    val codename: String,
    val googlePhotosTier: String,
    val recommendedSaver: Boolean,
    val notes: String
)

data class NasConfig(
    val serverEnabled: Boolean = true,
    val startOnBoot: Boolean = true,
    val deviceProfile: String = "pixel_5",
    val storageSaverEnabled: Boolean = true,
    val deleteOriginals: Boolean = true,
    val autoWatchEnabled: Boolean = true,
    val compressVideos: Boolean = true,
    val avoidRecompressingSmallJpegs: Boolean = true,
    val maxMegapixels: Double = 16.0,
    val jpegQuality: Int = 85,
    val maxVideoShortSide: Int = 1080,
    val httpPort: Int = 8080,
    val ftpEnabled: Boolean = true,
    val ftpPort: Int = 2121,
    val thermalLimitCelsius: Float = 41.0f,
    val incomingDir: String = "/storage/emulated/0/PixelNAS/Incoming",
    val outputDir: String = "/storage/emulated/0/DCIM/PixelNAS",
    val authEnabled: Boolean = false,
    val authUsername: String = "pixel",
    val authPassword: String = ""
)

data class ConversionRecord(
    val id: Long = 0,
    val originalFilename: String,
    val outputFilename: String,
    val mediaType: String,
    val mode: String,
    val origWidth: Int = 0,
    val origHeight: Int = 0,
    val origMp: Double = 0.0,
    val origBytes: Long,
    val outWidth: Int = 0,
    val outHeight: Int = 0,
    val outMp: Double = 0.0,
    val outBytes: Long,
    val savedBytes: Long,
    val savedPct: Double,
    val exifPreserved: Boolean = true,
    val cameraModel: String = "",
    val dateTaken: String = "",
    val gpsCoords: String = "",
    val originalDeleted: Boolean = true,
    val durationMs: Long = 0,
    val status: String = "completed",
    val errorMessage: String = "",
    val createdAtEpochSec: Double = System.currentTimeMillis() / 1000.0
)

data class HistorySummary(
    val totalProcessed: Int = 0,
    val saverCount: Int = 0,
    val passthroughCount: Int = 0,
    val totalOrigBytes: Long = 0,
    val totalOutBytes: Long = 0,
    val totalSavedBytes: Long = 0,
    val originalsDeletedCount: Int = 0,
    val avgSavedPct: Double = 0.0
)

data class WorkerRuntimeState(
    val serviceRunning: Boolean = false,
    val queueSize: Int = 0,
    val activeFile: String? = null,
    val batteryTempCelsius: Float = 28.0f,
    val thermalPaused: Boolean = false,
    val lastError: String = ""
)
