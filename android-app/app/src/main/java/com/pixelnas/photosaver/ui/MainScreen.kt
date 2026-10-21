package com.pixelnas.photosaver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pixelnas.photosaver.util.PixelDeviceDetector
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: NasViewModel,
    onRequestStorageAccess: () -> Unit
) {
    val context = LocalContext.current
    val cfg by viewModel.configFlow.collectAsState()
    val runtime by viewModel.runtimeFlow.collectAsState()
    val summary by viewModel.summaryFlow.collectAsState()
    val history by viewModel.recentHistoryFlow.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()

    var oledShieldActive by remember { mutableStateOf(false) }
    var pixelShiftStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(oledShieldActive) {
        while (oledShieldActive) {
            delay(20_000L)
            pixelShiftStep = (pixelShiftStep + 1) % 5
        }
    }

    val webPortalUrl = "http://${telemetry.lanIp}:${cfg.httpPort}"
    val webDavUrl = "$webPortalUrl/webdav/Incoming/"
    val ftpUrl = "ftp://${telemetry.lanIp}:${cfg.ftpPort}/"

    // True #000000 OLED Burn-In Blackout Mode for 24/7 desk/closet Pixel 2 XL / 3 / 4 / 5 OLED panels
    if (oledShieldActive) {
        val dy = ((pixelShiftStep - 2) * 28).dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { oledShieldActive = false },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.offset(y = dy).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "PIXEL PHOTO NAS · OLED SHIELD",
                    color = Color(0xFF475569),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    webPortalUrl,
                    color = Color(0xFF64748B),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${summary.totalProcessed} converted · ${formatBytes(summary.totalSavedBytes)} saved · ${runtime.batteryTempCelsius}°C",
                    color = Color(0xFF475569),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Tap anywhere to wake screen",
                    color = Color(0xFF334155),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        return
    }

    val qrBitmap = remember(webPortalUrl) {
        PixelDeviceDetector.renderQrBitmap(webPortalUrl, 300).asImageBitmap()
    }

    val incomingFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uriToStoragePath(uri)?.let { path ->
            viewModel.updateConfig { it.copy(incomingDir = path) }
        }
    }

    val outputFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uriToStoragePath(uri)?.let { path ->
            viewModel.updateConfig { it.copy(outputDir = path) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pixel Photo NAS", fontWeight = FontWeight.Bold)
                        Text(
                            "${viewModel.hardwareInfo.displayName} · ${viewModel.hardwareInfo.googlePhotosTier}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { oledShieldActive = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("OLED Dim")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!telemetry.hasAllFilesAccess) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "All Files Storage Access Required",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Grant storage access so Pixel Photo NAS can convert high-res uploads in Folder 1, move them into DCIM/PixelNAS for Google Photos, and delete originals.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = onRequestStorageAccess) {
                                Text("Grant Storage Permission")
                            }
                        }
                    }
                }
            }

            item {
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (cfg.serverEnabled) "NAS Daemon Online (mDNS: PixelPhotoNAS)" else "NAS Daemon Stopped",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Battery ${runtime.batteryLevelPct}% (${runtime.batteryTempCelsius}°C) · Free ${formatBytes(telemetry.freeBytes)} / ${formatBytes(telemetry.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = cfg.serverEnabled,
                                onCheckedChange = { viewModel.toggleServer(it) }
                            )
                        }

                        HorizontalDivider(Modifier.padding(vertical = 12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "Scan to open Web Portal",
                                modifier = Modifier.size(96.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Browser Portal", style = MaterialTheme.typography.labelMedium)
                                Text(webPortalUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(4.dp))
                                Text("WebDAV Drive", style = MaterialTheme.typography.labelMedium)
                                Text(webDavUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(4.dp))
                                Text("FTP Server", style = MaterialTheme.typography.labelMedium)
                                Text(ftpUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webPortalUrl)))
                                }
                            ) {
                                Text("Open Web UI")
                            }
                            OutlinedButton(
                                onClick = { copyText(context, webDavUrl, "Copied WebDAV URL") }
                            ) {
                                Text("Copy WebDAV")
                            }
                        }
                    }
                }
            }

            item {
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Google Photos Storage Saver Pipeline",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Folder 1 (${telemetry.incomingCount} waiting)  →  ${if (cfg.storageSaverEnabled) "≤${cfg.maxMegapixels.roundToInt()} MP Saver" else "Original Pass-Through"}  →  Folder 2 (${telemetry.outputCount} ready · ${formatBytes(summary.totalSavedBytes)} saved)",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        ToggleSettingRow(
                            title = "Convert to Storage Saver Quality",
                            subtitle = "Shrink photos >16 MP to 16 MP JPEG (${cfg.jpegQuality}%) preserving EXIF Date & GPS",
                            checked = cfg.storageSaverEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateConfig { it.copy(storageSaverEnabled = enabled) }
                            }
                        )

                        ToggleSettingRow(
                            title = "Delete Higher-Res Originals",
                            subtitle = "Automatically remove source files & sidecar JSONs from Folder 1 once verified",
                            checked = cfg.deleteOriginals,
                            onCheckedChange = { enabled ->
                                viewModel.updateConfig { it.copy(deleteOriginals = enabled) }
                            }
                        )

                        ToggleSettingRow(
                            title = "Compress 4K Videos to 1080p MP4",
                            subtitle = "Hardware MediaCodec H.264/AAC encoding for cool operation on Pixel 1–5",
                            checked = cfg.compressVideos,
                            onCheckedChange = { enabled ->
                                viewModel.updateConfig { it.copy(compressVideos = enabled) }
                            }
                        )

                        ToggleSettingRow(
                            title = "Smart EXIF Repair + Takeout JSON + SHA-256 Dedup",
                            subtitle = "Rebuilds WhatsApp/Screenshot EXIF dates, merges Google Takeout .json GPS/dates & skips duplicates",
                            checked = cfg.restoreExifFromFilename && cfg.mergeTakeoutJson,
                            onCheckedChange = { enabled ->
                                viewModel.updateConfig {
                                    it.copy(
                                        skipDuplicates = enabled,
                                        restoreExifFromFilename = enabled,
                                        mergeTakeoutJson = enabled
                                    )
                                }
                            }
                        )

                        ToggleSettingRow(
                            title = "Auto-Start NAS on Pixel Boot",
                            subtitle = "Keeps headless closet server alive automatically after power restarts",
                            checked = cfg.startOnBoot,
                            onCheckedChange = { enabled ->
                                viewModel.updateConfig { it.copy(startOnBoot = enabled) }
                            }
                        )

                        Text(
                            "Auto-Clean Synced Output Retention (Frees local storage after Google Photos sync)",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Off", 7 to "7d", 14 to "14d", 30 to "30d").forEach { (days, label) ->
                                FilterChip(
                                    selected = cfg.autoPurgeOutputDays == days,
                                    onClick = { viewModel.updateConfig { it.copy(autoPurgeOutputDays = days) } },
                                    label = { Text(label) }
                                )
                            }
                        }

                        Text(
                            "JPEG Quality: ${cfg.jpegQuality}%",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = cfg.jpegQuality.toFloat(),
                            onValueChange = { q ->
                                viewModel.updateConfig { it.copy(jpegQuality = q.roundToInt()) }
                            },
                            valueRange = 65f..95f
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.processIncomingNow() }) {
                                Text("Convert Now")
                            }
                            OutlinedButton(onClick = { viewModel.dropSamplePhotos() }) {
                                Text("Test Sample 24MP")
                            }
                        }
                    }
                }
            }

            item {
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Folder Paths",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = cfg.incomingDir,
                            onValueChange = { v -> viewModel.updateConfig { it.copy(incomingDir = v) } },
                            label = { Text("Folder 1 — Incoming Upload Folder (.nomedia protected)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedButton(onClick = { incomingFolderPicker.launch(null) }) {
                            Text("Pick Folder 1")
                        }

                        OutlinedTextField(
                            value = cfg.outputDir,
                            onValueChange = { v -> viewModel.updateConfig { it.copy(outputDir = v) } },
                            label = { Text("Folder 2 — Google Photos Output Folder") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { outputFolderPicker.launch(null) }) {
                                Text("Pick Folder 2")
                            }
                            OutlinedButton(onClick = { viewModel.clearSyncedOutput() }) {
                                Text("Clear Synced Output")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Recent Conversions (${summary.totalProcessed} total)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(history.take(15)) { rec ->
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(rec.outputFilename.ifBlank { rec.originalFilename }, fontWeight = FontWeight.SemiBold)
                        val noteSuffix = if (rec.metadataNote.isNotBlank()) " · ${rec.metadataNote}" else ""
                        Text(
                            "${rec.origWidth}×${rec.origHeight} (${formatBytes(rec.origBytes)}) → ${rec.outWidth}×${rec.outHeight} (${formatBytes(rec.outBytes)}) · -${rec.savedPct}% ${if (rec.originalDeleted) "· High-Res Deleted ✓" else ""}$noteSuffix",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun uriToStoragePath(uri: Uri?): String? {
    if (uri == null) return null
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")
        if (split.size >= 2 && split[0].equals("primary", ignoreCase = true)) {
            "/storage/emulated/0/${split[1]}"
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun copyText(context: Context, text: String, msg: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PixelNAS", text))
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var idx = 0
    while (v >= 1024.0 && idx < units.lastIndex) {
        v /= 1024.0
        idx++
    }
    return String.format("%.1f %s", v, units[idx])
}
