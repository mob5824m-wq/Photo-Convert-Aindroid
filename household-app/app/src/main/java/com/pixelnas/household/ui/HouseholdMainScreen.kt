package com.pixelnas.household.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pixelnas.household.BuildConfig
import com.pixelnas.household.R
import com.pixelnas.household.model.SmbAuthMode
import com.pixelnas.household.widget.HouseholdUploadWidgetProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdMainScreen(
    viewModel: HouseholdViewModel,
    onRequestMediaPermissions: () -> Unit
) {
    val context = LocalContext.current
    val cfg by viewModel.configFlow.collectAsState()
    val connState by viewModel.connectionState.collectAsState()
    val discovered by viewModel.discoveredHosts.collectAsState()
    val scanning by viewModel.scanningLan.collectAsState()
    val transfer by viewModel.transferFlow.collectAsState()
    val recentUploads by viewModel.recentUploadsFlow.collectAsState()
    val browser by viewModel.browserState.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var customProfileInput by remember { mutableStateOf("") }
    var newFolderInput by remember { mutableStateOf("") }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.uploadPickedMedia(uris)
            Toast.makeText(
                context,
                "Queued ${uris.size} photo/video(s) over Samba...",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Image(
                        painter = painterResource(id = R.drawable.app_icon_badge),
                        contentDescription = "Pixel NAS Household App Icon",
                        modifier = Modifier
                            .padding(start = 12.dp, end = 6.dp)
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                },
                title = {
                    Column {
                        Text("Pixel NAS · Household Samba", fontWeight = FontWeight.Bold)
                        Text(
                            "v${BuildConfig.VERSION_NAME} · Direct SMB2/SMB3 File Share Client",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("⬆") },
                    label = { Text("Upload & Sync") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.refreshBrowser()
                    },
                    icon = { Text("📁") },
                    label = { Text("Samba Share") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("⚙") },
                    label = { Text("Samba Setup") }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Brand Logo Hero Banner
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1629)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.brand_logo),
                            contentDescription = "Pixel Photo NAS — Home Photo & Samba Server",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(82.dp)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }

                // Status + Quick NAS Banner
                item {
                    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (connState.connected) "Connected to Home NAS Samba Share"
                                else "Pair Your Home NAS Samba Share",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                cfg.displayUnc,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                connState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            connState.space?.let { sp ->
                                Text(
                                    "NAS Free Space: ${formatSize(sp.freeBytes)} free of ${formatSize(sp.totalBytes)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (cfg.host.isBlank()) {
                                            selectedTab = 2
                                            Toast.makeText(context, "Scan Wi-Fi or enter your NAS IP first", Toast.LENGTH_SHORT).show()
                                        } else {
                                            mediaPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                            )
                                        }
                                    }
                                ) {
                                    Text("Pick Photos & Videos")
                                }
                                OutlinedButton(onClick = { selectedTab = 2 }) {
                                    Text(if (cfg.host.isBlank()) "Scan Wi-Fi NAS" else "Change NAS")
                                }
                            }
                        }
                    }
                }

                // Active Transfer Progress Card
                item {
                    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Samba Transfer Status",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(transfer.statusMessage, style = MaterialTheme.typography.bodySmall)
                            if (transfer.active) {
                                val pct = if (transfer.totalBytesCurrentFile > 0L) {
                                    (transfer.bytesSentCurrentFile.toFloat() / transfer.totalBytesCurrentFile.toFloat())
                                        .coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                if (transfer.totalBytesCurrentFile > 0L) {
                                    LinearProgressIndicator(
                                        progress = { pct },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }

                // Target Profile & Household Member Routing Card
                item {
                    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Target NAS Folder & Household Member",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Choose which Samba profile folder receives uploads from this phone:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "StorageSaver" to "Saver ≤16MP",
                                    "Original" to "Original",
                                    "Family" to "Family"
                                ).forEach { (folder, label) ->
                                    FilterChip(
                                        selected = cfg.targetProfileFolder.equals(folder, ignoreCase = true),
                                        onClick = {
                                            viewModel.updateConfig { it.copy(targetProfileFolder = folder) }
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = customProfileInput,
                                    onValueChange = { customProfileInput = it },
                                    label = { Text("Custom Folder (e.g. Trip2026)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        val clean = customProfileInput.trim()
                                        if (clean.isNotEmpty()) {
                                            viewModel.updateConfig { it.copy(targetProfileFolder = clean) }
                                            customProfileInput = ""
                                        }
                                    }
                                ) {
                                    Text("Use")
                                }
                            }

                            OutlinedTextField(
                                value = cfg.memberName,
                                onValueChange = { name -> viewModel.updateConfig { it.copy(memberName = name) } },
                                label = { Text("Household Member Name (e.g. Mom, Dad, Alex)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Separate Subfolder per Family Member", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Uploads into ${cfg.displayUnc}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = cfg.routeToMemberSubfolder,
                                    onCheckedChange = { enabled ->
                                        viewModel.updateConfig { it.copy(routeToMemberSubfolder = enabled) }
                                    }
                                )
                            }

                            HorizontalDivider()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Automatic Wi-Fi Camera Roll Sync", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Automatically backs up new Camera Roll photos & videos over Samba when on home Wi-Fi",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = cfg.autoCameraSyncWifiOnly,
                                    onCheckedChange = { enabled ->
                                        if (enabled) onRequestMediaPermissions()
                                        viewModel.updateConfig { it.copy(autoCameraSyncWifiOnly = enabled) }
                                    }
                                )
                            }

                            Button(
                                onClick = {
                                    val pinned = HouseholdUploadWidgetProvider.requestPinWidget(context)
                                    if (!pinned) {
                                        Toast.makeText(
                                            context,
                                            "Long-press Home Screen → Widgets → Household Samba NAS Upload",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            ) {
                                Text("Pin Samba Upload Widget to Home Screen")
                            }
                        }
                    }
                }

                // Recent Samba Uploads Ledger
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recent Samba Transfers (${recentUploads.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (recentUploads.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearUploadHistory() }) {
                                Text("Clear")
                            }
                        }
                    }
                }

                if (recentUploads.isEmpty()) {
                    item {
                        Card(shape = RoundedCornerShape(12.dp)) {
                            Text(
                                "No Samba transfers yet — pick photos above, tap the Home Screen Widget, or share from Android Gallery!",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    items(recentUploads) { item ->
                        Card(shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(item.filename, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (item.success) "✓ ${item.profileFolder}" else "✕ Error",
                                        color = if (item.success) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Text(
                                    "${formatSize(item.sizeBytes)} · ${item.remoteUncPath}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            1 -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Live Samba Share Browser",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "\\\\${cfg.host.ifBlank { "<nas>" }}\\${cfg.cleanShareName}" +
                                    (if (browser.currentPath.isEmpty()) "" else "\\${browser.currentPath}"),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (browser.currentPath.isNotEmpty()) {
                                    OutlinedButton(onClick = { viewModel.navigateUpInBrowser() }) {
                                        Text("← Up")
                                    }
                                }
                                Button(onClick = { viewModel.refreshBrowser() }) {
                                    Text(if (browser.loading) "Loading..." else "Refresh")
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = newFolderInput,
                                    onValueChange = { newFolderInput = it },
                                    label = { Text("Create folder here") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (newFolderInput.isNotBlank()) {
                                            viewModel.createRemoteFolder(newFolderInput)
                                            newFolderInput = ""
                                        }
                                    }
                                ) {
                                    Text("+ Folder")
                                }
                            }
                            if (browser.error.isNotEmpty()) {
                                Text(
                                    browser.error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                items(browser.entries) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.isDirectory) {
                                viewModel.refreshBrowser(entry.relativePath)
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (entry.isDirectory) "📁 " else "🖼 ") + entry.name,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (entry.isDirectory) "Samba Folder · Tap to open"
                                    else "${formatSize(entry.sizeBytes)} · ${formatTimestamp(entry.lastModifiedMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!entry.isDirectory) {
                                TextButton(onClick = { viewModel.deleteRemoteEntry(entry) }) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "1-Tap Wi-Fi Samba Discovery",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Finds standard Samba NAS servers (:445) and unrooted Pixel Photo NAS servers (:4445) on your Wi-Fi network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    viewModel.scanWifiForNas { count ->
                                        Toast.makeText(
                                            context,
                                            if (count > 0) "Found $count Samba share(s) on Wi-Fi!"
                                            else "No Samba server auto-detected — enter IP below",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Text(if (scanning) "Scanning Home Wi-Fi..." else "Scan Wi-Fi for Samba NAS")
                            }

                            discovered.forEach { host ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.selectDiscoveredHost(host) },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(host.label, fontWeight = FontWeight.Bold)
                                            Text(
                                                "\\\\${host.host}\\${host.suggestedShare} (port ${host.port} · ${host.source})",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Text("Use →", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Samba (SMB2 / SMB3) Share Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            OutlinedTextField(
                                value = cfg.host,
                                onValueChange = { v -> viewModel.updateConfig { it.copy(host = v) } },
                                label = { Text("NAS Hostname or LAN IP (e.g. 192.168.1.50)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = cfg.port.toString(),
                                    onValueChange = { v ->
                                        v.toIntOrNull()?.let { p ->
                                            viewModel.updateConfig { it.copy(port = p) }
                                        }
                                    },
                                    label = { Text("SMB Port (445 / 4445)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = cfg.shareName,
                                    onValueChange = { v -> viewModel.updateConfig { it.copy(shareName = v) } },
                                    label = { Text("Samba Share Name") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = cfg.baseSubfolder,
                                onValueChange = { v -> viewModel.updateConfig { it.copy(baseSubfolder = v) } },
                                label = { Text("Base Subfolder Inside Share (default: Incoming)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = cfg.authMode == SmbAuthMode.GUEST,
                                    onClick = { viewModel.updateConfig { it.copy(authMode = SmbAuthMode.GUEST) } },
                                    label = { Text("Guest Share (No Password)") }
                                )
                                FilterChip(
                                    selected = cfg.authMode == SmbAuthMode.CREDENTIALS,
                                    onClick = { viewModel.updateConfig { it.copy(authMode = SmbAuthMode.CREDENTIALS) } },
                                    label = { Text("Username & Password") }
                                )
                            }

                            if (cfg.authMode == SmbAuthMode.CREDENTIALS) {
                                OutlinedTextField(
                                    value = cfg.username,
                                    onValueChange = { v -> viewModel.updateConfig { it.copy(username = v) } },
                                    label = { Text("Samba Username") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = cfg.password,
                                    onValueChange = { v -> viewModel.updateConfig { it.copy(password = v) } },
                                    label = { Text("Samba Password") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = cfg.workgroup,
                                    onValueChange = { v -> viewModel.updateConfig { it.copy(workgroup = v) } },
                                    label = { Text("Domain / Workgroup") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.testSambaConnection() }) {
                                    Text(if (connState.checking) "Testing..." else "Test Samba Connection")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Samba UNC", cfg.displayUnc))
                                        Toast.makeText(context, "Copied ${cfg.displayUnc}", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Copy UNC Path")
                                }
                            }
                            Text(
                                connState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connState.connected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var idx = 0
    while (v >= 1024.0 && idx < units.lastIndex) {
        v /= 1024.0
        idx++
    }
    return String.format(Locale.US, if (idx == 0) "%.0f %s" else "%.1f %s", v, units[idx])
}

private fun formatTimestamp(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}
