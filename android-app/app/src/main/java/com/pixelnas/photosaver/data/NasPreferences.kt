package com.pixelnas.photosaver.data

import android.content.Context
import android.os.Environment
import com.pixelnas.photosaver.model.FolderProfileRule
import com.pixelnas.photosaver.model.NasConfig
import com.pixelnas.photosaver.model.defaultFolderProfiles
import com.pixelnas.photosaver.util.PixelDeviceDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
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
    private val defaultQuarantine = File(defaultRoot, "PixelNAS/Quarantine").absolutePath

    private val _configFlow = MutableStateFlow(readFromPrefs())
    val configFlow: StateFlow<NasConfig> = _configFlow.asStateFlow()

    val current: NasConfig
        get() = _configFlow.value

    init {
        ensureFolders()
    }

    private fun decodeProfiles(rawJson: String?): List<FolderProfileRule> {
        if (rawJson.isNullOrBlank()) return defaultFolderProfiles()
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<FolderProfileRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val fn = obj.optString("folder_name", "").replace(Regex("[^A-Za-z0-9._-]"), "")
                if (fn.isBlank()) continue
                list.add(
                    FolderProfileRule(
                        folderName = fn,
                        storageSaverEnabled = obj.optBoolean("storage_saver_enabled", true),
                        maxMegapixels = obj.optDouble("max_megapixels", 16.0),
                        jpegQuality = obj.optInt("jpeg_quality", 85).coerceIn(40, 98),
                        compressVideos = obj.optBoolean("compress_videos", true),
                        outputSubfolder = obj.optString("output_subfolder", "")
                            .replace(Regex("[^A-Za-z0-9._/-]"), "")
                            .trim('/')
                    )
                )
            }
            if (list.isEmpty()) defaultFolderProfiles() else list
        } catch (_: Exception) {
            defaultFolderProfiles()
        }
    }

    private fun encodeProfiles(profiles: List<FolderProfileRule>): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("folder_name", p.folderName)
                    put("storage_saver_enabled", p.storageSaverEnabled)
                    put("max_megapixels", p.maxMegapixels)
                    put("jpeg_quality", p.jpegQuality)
                    put("compress_videos", p.compressVideos)
                    put("output_subfolder", p.outputSubfolder)
                }
            )
        }
        return arr.toString()
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
            quarantineEnabled = prefs.getBoolean("quarantine_enabled", true),
            quarantineMaxRetries = prefs.getInt("quarantine_max_retries", 2).coerceIn(1, 5),
            autoPurgeOutputDays = prefs.getInt("auto_purge_output_days", 0),
            minFreeStorageGb = prefs.getFloat("min_free_storage_gb", 0.0f),
            maxMegapixels = prefs.getFloat("max_megapixels", 16.0f).toDouble(),
            jpegQuality = prefs.getInt("jpeg_quality", 85),
            maxVideoShortSide = prefs.getInt("max_video_short_side", 1080),
            httpPort = prefs.getInt("http_port", 8080),
            smbEnabled = prefs.getBoolean("smb_enabled", true),
            smbPort = prefs.getInt("smb_port", 4445),
            smbShareName = prefs.getString("smb_share_name", "PixelNAS") ?: "PixelNAS",
            ftpEnabled = prefs.getBoolean("ftp_enabled", true),
            ftpPort = prefs.getInt("ftp_port", 2121),
            thermalLimitCelsius = prefs.getFloat("thermal_limit_celsius", 41.0f),
            incomingDir = prefs.getString("incoming_dir", defaultIncoming) ?: defaultIncoming,
            outputDir = prefs.getString("output_dir", defaultOutput) ?: defaultOutput,
            quarantineDir = prefs.getString("quarantine_dir", defaultQuarantine) ?: defaultQuarantine,
            folderProfiles = decodeProfiles(prefs.getString("folder_profiles_json", null)),
            remoteNasUrl = prefs.getString("remote_nas_url", "") ?: "",
            widgetStatusLine = prefs.getString("widget_status_line", "") ?: "",
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
            .putBoolean("quarantine_enabled", updated.quarantineEnabled)
            .putInt("quarantine_max_retries", updated.quarantineMaxRetries.coerceIn(1, 5))
            .putInt("auto_purge_output_days", updated.autoPurgeOutputDays.coerceAtLeast(0))
            .putFloat("min_free_storage_gb", updated.minFreeStorageGb.coerceAtLeast(0f))
            .putFloat("max_megapixels", updated.maxMegapixels.toFloat())
            .putInt("jpeg_quality", updated.jpegQuality.coerceIn(40, 98))
            .putInt("max_video_short_side", updated.maxVideoShortSide)
            .putInt("http_port", updated.httpPort)
            .putBoolean("smb_enabled", updated.smbEnabled)
            .putInt("smb_port", updated.smbPort)
            .putString("smb_share_name", updated.smbShareName.trim().ifBlank { "PixelNAS" })
            .putBoolean("ftp_enabled", updated.ftpEnabled)
            .putInt("ftp_port", updated.ftpPort)
            .putFloat("thermal_limit_celsius", updated.thermalLimitCelsius)
            .putString("incoming_dir", updated.incomingDir.trim())
            .putString("output_dir", updated.outputDir.trim())
            .putString("quarantine_dir", updated.quarantineDir.trim())
            .putString("folder_profiles_json", encodeProfiles(updated.folderProfiles))
            .putString("remote_nas_url", updated.remoteNasUrl.trim().trimEnd('/'))
            .putString("widget_status_line", updated.widgetStatusLine)
            .putBoolean("auth_enabled", updated.authEnabled)
            .putString("auth_username", updated.authUsername.trim().ifBlank { "pixel" })
            .putString("auth_password", updated.authPassword)
            .apply()
        _configFlow.value = updated
        ensureFolders()
        return updated
    }

    fun quarantineFolder(): File {
        val quar = File(_configFlow.value.quarantineDir)
        try {
            quar.mkdirs()
            val nomedia = File(quar, ".nomedia")
            if (!nomedia.exists()) nomedia.writeText("")
        } catch (_: Exception) {
        }
        return quar
    }

    fun ensureFolders(): Pair<File, File> {
        val cfg = _configFlow.value
        val inc = File(cfg.incomingDir)
        val out = File(cfg.outputDir)
        val quar = File(cfg.quarantineDir)
        try {
            inc.mkdirs()
            out.mkdirs()
            quar.mkdirs()
            val nomediaIncoming = File(inc, ".nomedia")
            if (!nomediaIncoming.exists()) {
                nomediaIncoming.writeText("")
            }
            val nomediaQuar = File(quar, ".nomedia")
            if (!nomediaQuar.exists()) {
                nomediaQuar.writeText("")
            }
            val nomediaOutput = File(out, ".nomedia")
            if (nomediaOutput.exists()) {
                nomediaOutput.delete()
            }
            cfg.folderProfiles.forEach { rule ->
                if (rule.folderName.isNotBlank()) {
                    File(inc, rule.folderName).mkdirs()
                }
                if (rule.outputSubfolder.isNotBlank()) {
                    File(out, rule.outputSubfolder).mkdirs()
                }
            }
        } catch (_: Exception) {
        }
        return inc to out
    }
}
