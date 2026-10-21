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
import com.pixelnas.photosaver.model.HistorySummary
import com.pixelnas.photosaver.model.NasConfig
import com.pixelnas.photosaver.model.PixelHardwareInfo
import com.pixelnas.photosaver.model.WorkerRuntimeState
import com.pixelnas.photosaver.service.PixelNasService
import com.pixelnas.photosaver.util.PixelDeviceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class StorageTelemetry(
    val incomingCount: Int = 0,
    val outputCount: Int = 0,
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
        val incCount = incDir.listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
        val outCount = outDir.listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
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
        app.ftpServer.startOrReload()
        if (updated.autoWatchEnabled) {
            app.watcher.processAllNow()
        }
        refreshTelemetry()
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

    fun clearSyncedOutput() {
        viewModelScope.launch(Dispatchers.IO) {
            val (_, outDir) = app.preferences.ensureFolders()
            outDir.listFiles()?.forEach {
                if (it.isFile && !it.name.startsWith(".")) it.delete()
            }
            refreshTelemetry()
        }
    }
}
