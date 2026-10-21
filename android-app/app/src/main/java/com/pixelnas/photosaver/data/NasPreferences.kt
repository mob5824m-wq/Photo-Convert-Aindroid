package com.pixelnas.photosaver.data

import android.content.Context
import android.os.Environment
import com.pixelnas.photosaver.model.NasConfig
import com.pixelnas.photosaver.util.PixelDeviceDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class NasPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pixel_nas_prefs", Context.MODE_PRIVATE)
    private val hw = PixelDeviceDetector.detectCurrentDevice()

    private val defaultRoot: File = try {
        Environment.getExternalStorageDirectory()
    } catch (_: Exception) {
        File("/storage/emulated/0")
    }

    private val defaultIncoming = File(defaultRoot, "PixelNAS/Incoming").absolutePath
    private val defaultOutput = File(defaultRoot, "DCIM/PixelNAS").absolutePath

    private val _configFlow = MutableStateFlow(readFromPrefs())
    val configFlow: StateFlow<NasConfig> = _configFlow.asStateFlow()

    val current: NasConfig
        get() = _configFlow.value

    init {
        ensureFolders()
    }

    private fun readFromPrefs(): NasConfig {
        return NasConfig(
            serverEnabled = prefs.getBoolean("server_enabled", true),
            startOnBoot = prefs.getBoolean("start_on_boot", true),
            deviceProfile = prefs.getString("device_profile", hw.profileId) ?: hw.profileId,
            storageSaverEnabled = prefs.getBoolean("storage_saver_enabled", hw.recommendedSaver || hw.profileId != "pixel_1"),
            deleteOriginals = prefs.getBoolean("delete_originals", true),
            autoWatchEnabled = prefs.getBoolean("auto_watch_enabled", true),
            compressVideos = prefs.getBoolean("compress_videos", true),
            avoidRecompressingSmallJpegs = prefs.getBoolean("avoid_recompress_small", true),
            skipDuplicates = prefs.getBoolean("skip_duplicates", true),
            recursiveSubfolders = prefs.getBoolean("recursive_subfolders", true),
            restoreExifFromFilename = prefs.getBoolean("restore_exif_filename", true),
            mergeTakeoutJson = prefs.getBoolean("merge_takeout_json", true),
            autoPurgeOutputDays = prefs.getInt("auto_purge_output_days", 0),
            minFreeStorageGb = prefs.getFloat("min_free_storage_gb", 0.0f),
            maxMegapixels = prefs.getFloat("max_megapixels", 16.0f).toDouble(),
            jpegQuality = prefs.getInt("jpeg_quality", 85),
            maxVideoShortSide = prefs.getInt("max_video_short_side", 1080),
            httpPort = prefs.getInt("http_port", 8080),
            ftpEnabled = prefs.getBoolean("ftp_enabled", true),
            ftpPort = prefs.getInt("ftp_port", 2121),
            thermalLimitCelsius = prefs.getFloat("thermal_limit_celsius", 41.0f),
            incomingDir = prefs.getString("incoming_dir", defaultIncoming) ?: defaultIncoming,
            outputDir = prefs.getString("output_dir", defaultOutput) ?: defaultOutput,
            authEnabled = prefs.getBoolean("auth_enabled", false),
            authUsername = prefs.getString("auth_username", "pixel") ?: "pixel",
            authPassword = prefs.getString("auth_password", "") ?: ""
        )
    }

    @Synchronized
    fun update(transform: (NasConfig) -> NasConfig): NasConfig {
        val updated = transform(_configFlow.value).let { cfg ->
            if (cfg.incomingDir.trim() == cfg.outputDir.trim()) {
                cfg.copy(outputDir = defaultOutput)
            } else {
                cfg
            }
        }
        prefs.edit()
            .putBoolean("server_enabled", updated.serverEnabled)
            .putBoolean("start_on_boot", updated.startOnBoot)
            .putString("device_profile", updated.deviceProfile)
            .putBoolean("storage_saver_enabled", updated.storageSaverEnabled)
            .putBoolean("delete_originals", updated.deleteOriginals)
            .putBoolean("auto_watch_enabled", updated.autoWatchEnabled)
            .putBoolean("compress_videos", updated.compressVideos)
            .putBoolean("avoid_recompress_small", updated.avoidRecompressingSmallJpegs)
            .putBoolean("skip_duplicates", updated.skipDuplicates)
            .putBoolean("recursive_subfolders", updated.recursiveSubfolders)
            .putBoolean("restore_exif_filename", updated.restoreExifFromFilename)
            .putBoolean("merge_takeout_json", updated.mergeTakeoutJson)
            .putInt("auto_purge_output_days", updated.autoPurgeOutputDays.coerceAtLeast(0))
            .putFloat("min_free_storage_gb", updated.minFreeStorageGb.coerceAtLeast(0f))
            .putFloat("max_megapixels", updated.maxMegapixels.toFloat())
            .putInt("jpeg_quality", updated.jpegQuality.coerceIn(40, 98))
            .putInt("max_video_short_side", updated.maxVideoShortSide)
            .putInt("http_port", updated.httpPort)
            .putBoolean("ftp_enabled", updated.ftpEnabled)
            .putInt("ftp_port", updated.ftpPort)
            .putFloat("thermal_limit_celsius", updated.thermalLimitCelsius)
            .putString("incoming_dir", updated.incomingDir.trim())
            .putString("output_dir", updated.outputDir.trim())
            .putBoolean("auth_enabled", updated.authEnabled)
            .putString("auth_username", updated.authUsername.trim().ifBlank { "pixel" })
            .putString("auth_password", updated.authPassword)
            .apply()
        _configFlow.value = updated
        ensureFolders()
        return updated
    }

    fun ensureFolders(): Pair<File, File> {
        val cfg = _configFlow.value
        val inc = File(cfg.incomingDir)
        val out = File(cfg.outputDir)
        try {
            inc.mkdirs()
            out.mkdirs()
            val nomediaIncoming = File(inc, ".nomedia")
            if (!nomediaIncoming.exists()) {
                nomediaIncoming.writeText("")
            }
            val nomediaOutput = File(out, ".nomedia")
            if (nomediaOutput.exists()) {
                nomediaOutput.delete()
            }
        } catch (_: Exception) {
        }
        return inc to out
    }
}
