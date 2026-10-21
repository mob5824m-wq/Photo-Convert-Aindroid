package com.pixelnas.household.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelnas.household.HouseholdApp
import com.pixelnas.household.discovery.NsdSmbDiscovery
import com.pixelnas.household.model.DiscoveredSmbHost
import com.pixelnas.household.model.SmbConnectionConfig
import com.pixelnas.household.model.SmbRemoteEntry
import com.pixelnas.household.model.SmbShareSpace
import com.pixelnas.household.model.TransferBatchState
import com.pixelnas.household.model.UploadLedgerEntry
import com.pixelnas.household.sync.SmbUploadService
import com.pixelnas.household.widget.HouseholdUploadWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowserUiState(
    val currentPath: String = "",
    val entries: List<SmbRemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String = ""
)

data class ConnectionCheckState(
    val checking: Boolean = false,
    val connected: Boolean = false,
    val space: SmbShareSpace? = null,
    val message: String = "Not tested yet — scan Wi-Fi or enter your NAS IP"
)

class HouseholdViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HouseholdApp

    val configFlow: StateFlow<SmbConnectionConfig> = app.preferences.configFlow
    val recentUploadsFlow: StateFlow<List<UploadLedgerEntry>> = app.preferences.recentUploadsFlow
    val transferFlow: StateFlow<TransferBatchState> = app.transferState.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredSmbHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredSmbHost>> = _discoveredHosts.asStateFlow()

    private val _scanningLan = MutableStateFlow(false)
    val scanningLan: StateFlow<Boolean> = _scanningLan.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionCheckState())
    val connectionState: StateFlow<ConnectionCheckState> = _connectionState.asStateFlow()

    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    init {
        if (app.preferences.current.host.isNotBlank()) {
            testSambaConnection()
        }
    }

    fun updateConfig(transform: (SmbConnectionConfig) -> SmbConnectionConfig) {
        val prev = app.preferences.current
        val updated = app.preferences.update(transform)
        if (prev.autoCameraSyncWifiOnly != updated.autoCameraSyncWifiOnly) {
            app.syncAutoBackupSchedule(updated.autoCameraSyncWifiOnly)
        }
        HouseholdUploadWidgetProvider.refreshAllWidgets(app)
    }

    fun scanWifiForNas(onComplete: ((Int) -> Unit)? = null) {
        if (_scanningLan.value) return
        _scanningLan.value = true
        viewModelScope.launch {
            val hosts = runCatching { NsdSmbDiscovery.scanLanForSambaShares(app) }
                .getOrDefault(emptyList())
            _discoveredHosts.value = hosts
            _scanningLan.value = false
            if (hosts.size == 1 && app.preferences.current.host.isBlank()) {
                selectDiscoveredHost(hosts.first())
            }
            onComplete?.invoke(hosts.size)
        }
    }

    fun selectDiscoveredHost(host: DiscoveredSmbHost) {
        updateConfig {
            it.copy(
                host = host.host,
                port = host.port,
                shareName = host.suggestedShare
            )
        }
        testSambaConnection()
    }

    fun testSambaConnection() {
        val cfg = app.preferences.current
        if (cfg.host.isBlank()) {
            _connectionState.value = ConnectionCheckState(
                checking = false,
                connected = false,
                message = "Enter your NAS IP address or tap Scan Wi-Fi first."
            )
            return
        }
        _connectionState.value = _connectionState.value.copy(
            checking = true,
            message = "Connecting to \\\\${cfg.host}\\${cfg.cleanShareName} over SMB2/3..."
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val space = app.smbClient.testConnectionAndInspect(cfg)
                if (space.activePort != cfg.port) {
                    app.preferences.update { it.copy(port = space.activePort) }
                    HouseholdUploadWidgetProvider.refreshAllWidgets(app)
                }
                _connectionState.value = ConnectionCheckState(
                    checking = false,
                    connected = true,
                    space = space,
                    message = "Connected via Samba SMB2/3 (:${space.activePort}) · Shares: ${space.topFolders.joinToString(", ").ifBlank { "Ready" }}"
                )
                refreshBrowser(_browserState.value.currentPath)
            } catch (e: Throwable) {
                _connectionState.value = ConnectionCheckState(
                    checking = false,
                    connected = false,
                    space = null,
                    message = "Samba error: ${e.message ?: "Connection failed"}"
                )
            }
        }
    }

    fun uploadPickedMedia(uris: List<Uri>) {
        SmbUploadService.enqueueBatch(app, uris)
    }

    fun refreshBrowser(relativePath: String = _browserState.value.currentPath) {
        val cfg = app.preferences.current
        if (cfg.host.isBlank()) {
            _browserState.value = BrowserUiState(
                currentPath = "",
                entries = emptyList(),
                loading = false,
                error = "Configure NAS Samba IP first in Samba Setup."
            )
            return
        }
        _browserState.value = _browserState.value.copy(
            currentPath = relativePath,
            loading = true,
            error = ""
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = app.smbClient.browseFolder(cfg, relativePath)
                _browserState.value = BrowserUiState(
                    currentPath = relativePath,
                    entries = list,
                    loading = false,
                    error = ""
                )
            } catch (e: Throwable) {
                _browserState.value = BrowserUiState(
                    currentPath = relativePath,
                    entries = emptyList(),
                    loading = false,
                    error = e.message ?: "Unable to list Samba directory"
                )
            }
        }
    }

    fun navigateUpInBrowser() {
        val curr = _browserState.value.currentPath.trim('\\', '/')
        if (curr.isEmpty()) return
        val parent = curr.substringBeforeLast('\\', "")
        refreshBrowser(parent)
    }

    fun createRemoteFolder(folderName: String) {
        val clean = SmbConnectionConfig.sanitizeSegment(folderName)
        if (clean.isEmpty()) return
        val curr = _browserState.value.currentPath
        val full = if (curr.isEmpty()) clean else "$curr\\$clean"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.smbClient.createFolder(app.preferences.current, full) }
            refreshBrowser(curr)
        }
    }

    fun deleteRemoteEntry(entry: SmbRemoteEntry) {
        val curr = _browserState.value.currentPath
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                app.smbClient.deleteEntry(app.preferences.current, entry.relativePath, entry.isDirectory)
            }
            refreshBrowser(curr)
        }
    }

    fun clearUploadHistory() {
        app.preferences.clearRecentUploads()
    }
}
