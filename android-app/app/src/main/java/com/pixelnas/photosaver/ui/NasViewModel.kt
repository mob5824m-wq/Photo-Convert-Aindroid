package com.pixelnas.photosaver.ui

import android.app.Application
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelnas.photosaver.PixelNasApp
import com.pixelnas.photosaver.converter.StorageSaverConverter
import com.pixelnas.photosaver.model.ConversionRecord
import com.pixelnas.photosaver.model.FolderProfileRule
import com.pixelnas.photosaver.model.HistorySummary
import com.pixelnas.photosaver.model.NasConfig
import com.pixelnas.photosaver.model.PixelHardwareInfo
import com.pixelnas.photosaver.model.WorkerRuntimeState
import com.pixelnas.photosaver.service.PixelNasService
import com.pixelnas.photosaver.util.PixelDeviceDetector
import com.pixelnas.photosaver.widget.PixelNasUploadWidgetProvider
import com.pixelnas.photosaver.widget.WidgetUploadActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StorageTelemetry(
    val incomingCount: Int = 0,
    val outputCount: Int = 0,
    val quarantineCount: Int = 0,
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val hasAllFilesAccess: Boolean = true,
    val lanIp: String = "127.0.0.1"
)

class NasViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PixelNasApp
    val hardwareInfo: PixelHardwareInfo = PixelDeviceDetector.detectCurrentDevice()

    val configFlow: StateFlow<NasConfig> = app.preferences.configFlow
    val runtimeFlow: StateFlow<WorkerRuntimeState> = app.watcher.state
    val recentHistoryFlow: StateFlow<List<ConversionRecord>> = app.historyStore.recentFlow
    val summaryFlow: StateFlow<HistorySummary> = app.historyStore.summaryFlow

    private val _telemetry = MutableStateFlow(StorageTelemetry())
    val telemetry: StateFlow<StorageTelemetry> = _telemetry.asStateFlow()

    init {
        if (app.preferences.current.serverEnabled) {
            PixelNasService.start(app)
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshTelemetry()
                delay(2000L)
            }
        }
    }

    fun refreshTelemetry() {
        val (incDir, outDir) = app.preferences.ensureFolders()
        val quarDir = app.preferences.quarantineFolder()
        val incCount = app.watcher.listIncomingFiles(incDir, app.preferences.current.recursiveSubfolders).size
        val outCount = app.watcher.listOutputFiles(outDir).size
        val quarCount = quarDir.listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
        val stat = try {
            StatFs(outDir.absolutePath)
        } catch (_: Exception) {
            null
        }
        val allAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        _telemetry.value = StorageTelemetry(
            incomingCount = incCount,
            outputCount = outCount,
            quarantineCount = quarCount,
            freeBytes = stat?.availableBytes ?: 0L,
            totalBytes = stat?.totalBytes ?: 0L,
            hasAllFilesAccess = allAccess,
            lanIp = PixelDeviceDetector.getLanIpv4Addresses().firstOrNull() ?: "127.0.0.1"
        )
    }

    fun toggleServer(enabled: Boolean) {
        app.preferences.update { it.copy(serverEnabled = enabled) }
        if (enabled) {
            PixelNasService.start(app)
        } else {
            PixelNasService.stop(app)
        }
        refreshTelemetry()
    }

    fun updateConfig(transform: (NasConfig) -> NasConfig) {
        val updated = app.preferences.update(transform)
        app.smbServer.startOrReload()
        app.ftpServer.startOrReload()
        PixelNasUploadWidgetProvider.refreshAllWidgets(app)
        if (updated.autoWatchEnabled) {
            app.watcher.processAllNow()
        }
        refreshTelemetry()
    }

    fun addFolderProfile(folderName: String, storageSaver: Boolean, outputSubfolder: String) {
        val cleanName = folderName.trim().replace(Regex("[^A-Za-z0-9._-]"), "")
        if (cleanName.isBlank()) return
        val cleanOut = outputSubfolder.trim().replace(Regex("[^A-Za-z0-9._/-]"), "").trim('/')
        updateConfig { cfg ->
            val filtered = cfg.folderProfiles.filterNot { it.folderName.equals(cleanName, ignoreCase = true) }
            cfg.copy(
                folderProfiles = filtered + FolderProfileRule(
                    folderName = cleanName,
                    storageSaverEnabled = storageSaver,
                    maxMegapixels = 16.0,
                    jpegQuality = 85,
                    compressVideos = storageSaver,
                    outputSubfolder = cleanOut
                )
            )
        }
    }

    fun removeFolderProfile(folderName: String) {
        updateConfig { cfg ->
            cfg.copy(folderProfiles = cfg.folderProfiles.filterNot { it.folderName == folderName })
        }
    }

    fun autoDiscoverRemoteNas(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val found = WidgetUploadActivity.discoverNasOnLan(app)
            if (found != null) {
                updateConfig { it.copy(remoteNasUrl = found) }
            }
            onResult(found)
        }
    }

    fun processIncomingNow() {
        viewModelScope.launch(Dispatchers.IO) {
            app.watcher.processAllNow()
            refreshTelemetry()
        }
    }

    fun dropSamplePhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            val (incDir, _) = app.preferences.ensureFolders()
            StorageSaverConverter.generateSamplePhotos(incDir)
            if (app.preferences.current.autoWatchEnabled) {
                app.watcher.processAllNow()
            }
            refreshTelemetry()
        }
    }

    fun simulateCorruptedUpload() {
        viewModelScope.launch(Dispatchers.IO) {
            val (incDir, _) = app.preferences.ensureFolders()
            StorageSaverConverter.seedCorruptedTransfer(incDir)
            if (app.preferences.current.autoWatchEnabled) {
                app.watcher.processAllNow()
            }
            refreshTelemetry()
        }
    }

    fun retryAllQuarantine() {
        viewModelScope.launch(Dispatchers.IO) {
            app.watcher.retryAllQuarantine()
            refreshTelemetry()
        }
    }

    fun clearQuarantine() {
        viewModelScope.launch(Dispatchers.IO) {
            val quarDir = app.preferences.quarantineFolder()
            quarDir.listFiles()?.forEach {
                if (it.isFile && !it.name.startsWith(".")) it.delete()
            }
            app.historyStore.clearQuarantineMeta()
            refreshTelemetry()
        }
    }

    fun clearSyncedOutput() {
        viewModelScope.launch(Dispatchers.IO) {
            val (_, outDir) = app.preferences.ensureFolders()
            outDir.walkTopDown().forEach {
                if (it.isFile && !it.name.startsWith(".")) it.delete()
            }
            refreshTelemetry()
        }
    }
}
