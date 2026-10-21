package com.pixelnas.photosaver.watcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.FileObserver
import com.pixelnas.photosaver.converter.StorageSaverConverter
import com.pixelnas.photosaver.data.HistoryStore
import com.pixelnas.photosaver.data.NasPreferences
import com.pixelnas.photosaver.model.ConversionRecord
import com.pixelnas.photosaver.model.WorkerRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class IncomingFolderWatcher(
    private val context: Context,
    private val prefs: NasPreferences,
    private val historyStore: HistoryStore,
    private val onStatsChanged: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<File>(Channel.UNLIMITED)
    private val queuedPaths = ConcurrentHashMap.newKeySet<String>()

    private var workerJob: Job? = null
    private var sweeperJob: Job? = null
    private var fileObserver: FileObserver? = null
    private var observedPath: String? = null

    private val _state = MutableStateFlow(WorkerRuntimeState())
    val state: StateFlow<WorkerRuntimeState> = _state.asStateFlow()

    fun start() {
        if (workerJob?.isActive == true) return
        _state.update { it.copy(serviceRunning = true) }
        rebindFileObserver()

        workerJob = scope.launch {
            for (file in channel) {
                val pathKey = file.absolutePath
                try {
                    waitIfOverheated()
                    _state.update { it.copy(activeFile = file.name, queueSize = (queuedPaths.size - 1).coerceAtLeast(0)) }
                    if (file.isFile && waitUntilFileStable(file)) {
                        val rec = StorageSaverConverter.processFile(context, file, prefs.current)
                        historyStore.record(rec)
                        _state.update { it.copy(lastError = "") }
                        onStatsChanged()
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Conversion error"
                    _state.update { it.copy(lastError = "${file.name}: $errMsg") }
                    historyStore.record(
                        ConversionRecord(
                            originalFilename = file.name,
                            outputFilename = "",
                            mediaType = "photo",
                            mode = "error",
                            origBytes = if (file.exists()) file.length() else 0L,
                            outBytes = 0L,
                            savedBytes = 0L,
                            savedPct = 0.0,
                            originalDeleted = false,
                            status = "error",
                            errorMessage = errMsg
                        )
                    )
                } finally {
                    queuedPaths.remove(pathKey)
                    _state.update { it.copy(activeFile = null, queueSize = queuedPaths.size) }
                }
            }
        }

        sweeperJob = scope.launch {
            while (isActive) {
                rebindFileObserver()
                val temp = readBatteryTemperatureCelsius()
                _state.update { it.copy(batteryTempCelsius = temp) }
                if (prefs.current.autoWatchEnabled) {
                    val (inc, _) = prefs.ensureFolders()
                    inc.listFiles()?.sortedBy { it.lastModified() }?.forEach { f ->
                        if (f.isFile && StorageSaverConverter.isSupportedMedia(f)) {
                            enqueue(f)
                        }
                    }
                }
                delay(2500L)
            }
        }
    }

    fun stop() {
        fileObserver?.stopWatching()
        fileObserver = null
        observedPath = null
        workerJob?.cancel()
        sweeperJob?.cancel()
        _state.update { it.copy(serviceRunning = false, activeFile = null) }
    }

    fun enqueueIfAuto(file: File): Boolean {
        if (!prefs.current.autoWatchEnabled) return false
        return enqueue(file)
    }

    fun enqueue(file: File): Boolean {
        if (!StorageSaverConverter.isSupportedMedia(file)) return false
        val key = file.absolutePath
        if (!queuedPaths.add(key)) return false
        channel.trySend(file)
        _state.update { it.copy(queueSize = queuedPaths.size) }
        return true
    }

    fun processAllNow(): Int {
        val (inc, _) = prefs.ensureFolders()
        var count = 0
        inc.listFiles()?.forEach { f ->
            if (f.isFile && StorageSaverConverter.isSupportedMedia(f)) {
                if (enqueue(f)) count++
            }
        }
        return count
    }

    private fun rebindFileObserver() {
        val (inc, _) = prefs.ensureFolders()
        if (observedPath == inc.absolutePath && fileObserver != null) return
        fileObserver?.stopWatching()
        @Suppress("DEPRECATION")
        val obs = object : FileObserver(inc.absolutePath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) {
                    enqueueIfAuto(File(inc, path))
                }
            }
        }
        obs.startWatching()
        fileObserver = obs
        observedPath = inc.absolutePath
    }

    private suspend fun waitUntilFileStable(file: File): Boolean {
        var prevSize = -1L
        repeat(40) {
            if (!file.isFile) return false
            val cur = file.length()
            if (cur > 0L && cur == prevSize) return true
            prevSize = cur
            delay(200L)
        }
        return file.isFile && file.length() > 0L
    }

    private suspend fun waitIfOverheated() {
        while (true) {
            val temp = readBatteryTemperatureCelsius()
            val limit = prefs.current.thermalLimitCelsius
            if (temp <= limit) {
                if (_state.value.thermalPaused) {
                    _state.update { it.copy(thermalPaused = false, batteryTempCelsius = temp) }
                }
                return
            }
            _state.update { it.copy(thermalPaused = true, batteryTempCelsius = temp) }
            delay(5000L)
        }
    }

    fun readBatteryTemperatureCelsius(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280
            tenths / 10.0f
        } catch (_: Exception) {
            28.0f
        }
    }
}
