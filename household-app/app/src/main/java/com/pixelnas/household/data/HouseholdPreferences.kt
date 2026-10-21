package com.pixelnas.household.data

import android.content.Context
import com.pixelnas.household.model.SmbAuthMode
import com.pixelnas.household.model.SmbConnectionConfig
import com.pixelnas.household.model.UploadLedgerEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class HouseholdPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "pixelnas_household_samba_prefs",
        Context.MODE_PRIVATE
    )

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<SmbConnectionConfig> = _configFlow.asStateFlow()

    private val _recentUploadsFlow = MutableStateFlow(loadRecentUploads())
    val recentUploadsFlow: StateFlow<List<UploadLedgerEntry>> = _recentUploadsFlow.asStateFlow()

    val current: SmbConnectionConfig
        get() = _configFlow.value

    private fun loadConfig(): SmbConnectionConfig {
        val modeStr = prefs.getString("auth_mode", SmbAuthMode.GUEST.name) ?: SmbAuthMode.GUEST.name
        val mode = runCatching { SmbAuthMode.valueOf(modeStr) }.getOrDefault(SmbAuthMode.GUEST)
        return SmbConnectionConfig(
            host = prefs.getString("smb_host", "") ?: "",
            port = prefs.getInt("smb_port", 445),
            shareName = prefs.getString("smb_share", "PixelNAS") ?: "PixelNAS",
            baseSubfolder = prefs.getString("base_subfolder", "Incoming") ?: "Incoming",
            targetProfileFolder = prefs.getString("target_profile_folder", "StorageSaver") ?: "StorageSaver",
            memberName = prefs.getString("member_name", "") ?: "",
            routeToMemberSubfolder = prefs.getBoolean("route_member_subfolder", false),
            authMode = mode,
            username = prefs.getString("smb_username", "pixel") ?: "pixel",
            password = prefs.getString("smb_password", "") ?: "",
            workgroup = prefs.getString("smb_workgroup", "WORKGROUP") ?: "WORKGROUP",
            autoCameraSyncWifiOnly = prefs.getBoolean("auto_camera_sync_wifi", false),
            syncVideosToo = prefs.getBoolean("sync_videos_too", true)
        )
    }

    @Synchronized
    fun update(transform: (SmbConnectionConfig) -> SmbConnectionConfig): SmbConnectionConfig {
        val next = transform(_configFlow.value)
        prefs.edit()
            .putString("smb_host", next.host.trim())
            .putInt("smb_port", next.port.coerceIn(1, 65535))
            .putString("smb_share", next.cleanShareName)
            .putString("base_subfolder", next.baseSubfolder.trim().trim('/', '\\'))
            .putString("target_profile_folder", next.targetProfileFolder.trim().trim('/', '\\').ifBlank { "StorageSaver" })
            .putString("member_name", next.memberName.trim())
            .putBoolean("route_member_subfolder", next.routeToMemberSubfolder)
            .putString("auth_mode", next.authMode.name)
            .putString("smb_username", next.username.trim().ifBlank { "pixel" })
            .putString("smb_password", next.password)
            .putString("smb_workgroup", next.workgroup.trim().ifBlank { "WORKGROUP" })
            .putBoolean("auto_camera_sync_wifi", next.autoCameraSyncWifiOnly)
            .putBoolean("sync_videos_too", next.syncVideosToo)
            .apply()
        _configFlow.value = next
        return next
    }

    @Synchronized
    fun hasSyncedFingerprint(key: String): Boolean {
        val set = prefs.getStringSet("synced_media_keys", emptySet()) ?: emptySet()
        return key in set
    }

    @Synchronized
    fun markSyncedFingerprint(key: String) {
        val existing = (prefs.getStringSet("synced_media_keys", emptySet()) ?: emptySet()).toMutableSet()
        if (existing.size > 5000) {
            val trimmed = existing.drop(existing.size - 4000).toMutableSet()
            trimmed.add(key)
            prefs.edit().putStringSet("synced_media_keys", trimmed).apply()
        } else {
            existing.add(key)
            prefs.edit().putStringSet("synced_media_keys", existing).apply()
        }
    }

    @Synchronized
    fun recordUpload(entry: UploadLedgerEntry) {
        val updated = (listOf(entry) + _recentUploadsFlow.value).take(50)
        val arr = JSONArray()
        updated.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("filename", item.filename)
                    put("remoteUncPath", item.remoteUncPath)
                    put("sizeBytes", item.sizeBytes)
                    put("profileFolder", item.profileFolder)
                    put("uploadedAtMillis", item.uploadedAtMillis)
                    put("success", item.success)
                    put("message", item.message)
                }
            )
        }
        prefs.edit().putString("recent_uploads_json", arr.toString()).apply()
        _recentUploadsFlow.value = updated
    }

    @Synchronized
    fun clearRecentUploads() {
        prefs.edit().putString("recent_uploads_json", "[]").apply()
        _recentUploadsFlow.value = emptyList()
    }

    private fun loadRecentUploads(): List<UploadLedgerEntry> {
        val raw = prefs.getString("recent_uploads_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<UploadLedgerEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    UploadLedgerEntry(
                        filename = o.optString("filename", ""),
                        remoteUncPath = o.optString("remoteUncPath", ""),
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        profileFolder = o.optString("profileFolder", ""),
                        uploadedAtMillis = o.optLong("uploadedAtMillis", 0L),
                        success = o.optBoolean("success", true),
                        message = o.optString("message", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
