package com.pixelnas.photosaver.watcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.FileObserver
import android.os.StatFs
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
import java.util.UUID
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
    private val failureCounts = ConcurrentHashMap<String, Int>()

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
                        val cfg = prefs.current
                        val rec = StorageSaverConverter.processFile(context, file, cfg, historyStore)
                        failureCounts.remove(pathKey)
                        historyStore.record(rec)
                        if (cfg.deleteOriginals) {
                            pruneEmptySubfolders(File(cfg.incomingDir))
                        }
                        _state.update { it.copy(lastError = "") }
                        onStatsChanged()
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Corrupted or unreadable media stream"
                    val attempts = (failureCounts[pathKey] ?: 0) + 1
                    failureCounts[pathKey] = attempts
                    val cfg = prefs.current
                    if (cfg.quarantineEnabled && file.exists() && attempts >= cfg.quarantineMaxRetries) {
                        val origSize = file.length()
                        val quarDest = moveToQuarantine(file, attempts, errMsg)
                        failureCounts.remove(pathKey)
                        if (quarDest != null) {
                            _state.update {
                                it.copy(
                                    quarantinedTotal = it.quarantinedTotal + 1,
                                    lastError = "Quarantined ${file.name} after $attempts failed attempts"
                                )
                            }
                            historyStore.record(
                                ConversionRecord(
                                    originalFilename = file.name,
                                    outputFilename = "Quarantine/${quarDest.name}",
                                    mediaType = "photo",
                                    mode = "quarantined",
                                    origBytes = origSize,
                                    outBytes = quarDest.length(),
                                    savedBytes = 0L,
                                    savedPct = 0.0,
                                    originalDeleted = true,
                                    status = "quarantined",
                                    errorMessage = "Quarantined after $attempts failed attempts: $errMsg"
                                )
                            )
                            onStatsChanged()
                        }
                    } else {
                        _state.update { it.copy(lastError = "${file.name} (attempt $attempts): $errMsg") }
                    }
                } finally {
                    queuedPaths.remove(pathKey)
                    _state.update { it.copy(activeFile = null, queueSize = queuedPaths.size) }
                }
            }
        }

        sweeperJob = scope.launch {
            while (isActive) {
                rebindFileObserver()
                val (temp, pct) = readBatteryStatus()
                _state.update { it.copy(batteryTempCelsius = temp, batteryLevelPct = pct) }
                val cfg = prefs.current
                val (inc, out) = prefs.ensureFolders()
                if (cfg.autoWatchEnabled) {
                    listIncomingFiles(inc, cfg.recursiveSubfolders)
                        .sortedBy { it.lastModified() }
                        .forEach { enqueue(it) }
                    if (cfg.deleteOriginals) {
                        pruneEmptySubfolders(inc)
                    }
                }
                if (cfg.autoPurgeOutputDays > 0 || cfg.minFreeStorageGb > 0f) {
                    val removed = enforceOutputRetention(out, cfg.autoPurgeOutputDays, cfg.minFreeStorageGb)
                    if (removed > 0) {
                        _state.update { it.copy(autoPurgedCount = it.autoPurgedCount + removed) }
                    }
                }
                delay(2000L)
            }
        }
    }

    private fun moveToQuarantine(file: File, attempts: Int, errorReason: String): File? {
        if (!file.exists()) return null
        val incDir = File(prefs.current.incomingDir)
        val quarDir = prefs.quarantineFolder()
        val relPath = try {
            file.relativeTo(incDir).invariantSeparatorsPath
        } catch (_: Exception) {
            file.name
        }
        var dest = File(quarDir, file.name)
        var idx = 1
        while (dest.exists()) {
            dest = File(quarDir, "${file.nameWithoutExtension}_$idx.${file.extension}")
            idx++
        }
        return try {
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
                file.delete()
            }
            historyStore.recordQuarantine(dest.name, relPath, attempts, errorReason)
            dest
        } catch (_: Exception) {
            null
        }
    }

    fun retryQuarantineFile(quarantineFilename: String): String? {
        val quarDir = prefs.quarantineFolder()
        val target = File(quarDir, File(quarantineFilename).name)
        if (!target.isFile) return null
        val (incDir, _) = prefs.ensureFolders()
        val meta = historyStore.getQuarantineMeta(target.name)
        val relPath = meta?.originalRelPath ?: target.name
        var dest = File(incDir, relPath)
        dest.parentFile?.mkdirs()
        if (dest.exists()) {
            dest = File(dest.parentFile, "${dest.nameWithoutExtension}_${UUID.randomUUID().toString().take(4)}.${dest.extension}")
        }
        if (!target.renameTo(dest)) {
            target.copyTo(dest, overwrite = true)
            target.delete()
        }
        historyStore.removeQuarantineMeta(target.name)
        failureCounts.remove(dest.absolutePath)
        enqueue(dest)
        return dest.relativeTo(incDir).invariantSeparatorsPath
    }

    fun retryAllQuarantine(): Int {
        val quarDir = prefs.quarantineFolder()
        var count = 0
        quarDir.listFiles()?.forEach { f ->
            if (f.isFile && !f.name.startsWith(".")) {
                if (retryQuarantineFile(f.name) != null) count++
            }
        }
        return count
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
        val cfg = prefs.current
        val (inc, _) = prefs.ensureFolders()
        var count = 0
        listIncomingFiles(inc, cfg.recursiveSubfolders).forEach { f ->
            if (enqueue(f)) count++
        }
        return count
    }

    fun listIncomingFiles(incDir: File, recursive: Boolean): List<File> {
        if (!incDir.isDirectory) return emptyList()
        return if (recursive) {
            incDir.walkTopDown()
                .onEnter { dir -> dir == incDir || !dir.name.startsWith(".") }
                .filter { it.isFile && StorageSaverConverter.isSupportedMedia(it) }
                .toList()
        } else {
            incDir.listFiles()?.filter { it.isFile && StorageSaverConverter.isSupportedMedia(it) }.orEmpty()
        }
    }

    fun listOutputFiles(outDir: File): List<File> {
        if (!outDir.isDirectory) return emptyList()
        return outDir.walkTopDown()
            .onEnter { dir -> dir == outDir || !dir.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") }
            .toList()
    }

    private fun pruneEmptySubfolders(incDir: File) {
        if (!incDir.isDirectory) return
        val protectedNames = prefs.current.folderProfiles.map { it.folderName.lowercase() }.toSet()
        incDir.walkBottomUp().forEach { dir ->
            if (dir != incDir && dir.isDirectory && dir.listFiles().isNullOrEmpty()) {
                val isProtectedRoot = dir.parentFile == incDir && dir.name.lowercase() in protectedNames
                if (!isProtectedRoot) {
                    dir.delete()
                }
            }
        }
    }

    private fun enforceOutputRetention(outDir: File, retentionDays: Int, minFreeGb: Float): Int {
        if (!outDir.isDirectory) return 0
        var purged = 0
        val nowSec = System.currentTimeMillis() / 1000.0
        fun ingestedEpochSec(file: File): Double {
            val rel = try { file.relativeTo(outDir).invariantSeparatorsPath } catch (_: Exception) { file.name }
            return historyStore.lookupByOutput(rel)?.createdAtEpochSec
                ?: historyStore.lookupByOutput(file.name)?.createdAtEpochSec
                ?: (file.lastModified() / 1000.0)
        }
        val allOutFiles = listOutputFiles(outDir)
        if (retentionDays > 0) {
            val cutoffSec = nowSec - (retentionDays.toDouble() * 86_400.0)
            allOutFiles.forEach { f ->
                if (ingestedEpochSec(f) <= cutoffSec && f.delete()) purged++
            }
        }
        if (minFreeGb > 0f) {
            val minBytes = (minFreeGb.toDouble() * 1024.0 * 1024.0 * 1024.0).toLong()
            val filesOldestFirst = listOutputFiles(outDir).sortedBy { ingestedEpochSec(it) }
            for (f in filesOldestFirst) {
                val freeNow = try { StatFs(outDir.absolutePath).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
                if (freeNow >= minBytes) break
                if (f.delete()) purged++
            }
        }
        return purged
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
            val (temp, pct) = readBatteryStatus()
            val limit = prefs.current.thermalLimitCelsius
            if (temp <= limit) {
                if (_state.value.thermalPaused) {
                    _state.update { it.copy(thermalPaused = false, batteryTempCelsius = temp, batteryLevelPct = pct) }
                }
                return
            }
            _state.update { it.copy(thermalPaused = true, batteryTempCelsius = temp, batteryLevelPct = pct) }
            delay(5000L)
        }
    }

    fun readBatteryStatus(): Pair<Float, Int> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
            (tenths / 10.0f) to ((level * 100) / scale)
        } catch (_: Exception) {
            28.0f to 100
        }
    }
}
