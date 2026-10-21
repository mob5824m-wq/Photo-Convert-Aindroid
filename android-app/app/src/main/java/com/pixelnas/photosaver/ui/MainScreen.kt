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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pixelnas.photosaver.util.PixelDeviceDetector
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

    val webPortalUrl = "http://${telemetry.lanIp}:${cfg.httpPort}"
    val webDavUrl = "$webPortalUrl/webdav/Incoming/"
    val ftpUrl = "ftp://${telemetry.lanIp}:${cfg.ftpPort}/"

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
            // Permission Banner for Android 11–14 Scoped Storage
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

            // Master NAS Daemon Card + Scannable LAN QR
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
                                    if (cfg.serverEnabled) "NAS Daemon Online" else "NAS Daemon Stopped",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Battery ${runtime.batteryTempCelsius}°C · Free ${formatBytes(telemetry.freeBytes)} / ${formatBytes(telemetry.totalBytes)}",
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

            // Pipeline Status & Space Saved Card
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
                            subtitle = "Automatically remove source files from Folder 1 once verified in Folder 2",
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
                            title = "Auto-Start NAS on Pixel Boot",
                            subtitle = "Keeps headless closet server alive automatically after power restarts",
                            checked = cfg.startOnBoot,
                            onCheckedChange = { enabled ->
                                viewModel.updateConfig { it.copy(startOnBoot = enabled) }
                            }
                        )

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

            // Folder Configuration Card
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

            // Recent Conversions List
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
                        Text(
                            "${rec.origWidth}×${rec.origHeight} (${formatBytes(rec.origBytes)}) → ${rec.outWidth}×${rec.outHeight} (${formatBytes(rec.outBytes)}) · -${rec.savedPct}% ${if (rec.originalDeleted) "· High-Res Deleted ✓" else ""}",
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
