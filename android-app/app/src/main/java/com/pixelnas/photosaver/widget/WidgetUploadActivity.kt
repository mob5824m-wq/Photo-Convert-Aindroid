package com.pixelnas.photosaver.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.pixelnas.photosaver.PixelNasApp
import com.pixelnas.photosaver.ui.theme.PixelNasTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.resume

class WidgetUploadActivity : ComponentActivity() {

    private var selectedProfile: String = "StorageSaver"
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    private var uiMode by mutableStateOf("uploading")
    private var statusMessage by mutableStateOf("Opening Android Photo Picker...")
    private var uploadProgress by mutableFloatStateOf(0f)
    private var remoteUrlInput by mutableStateOf("")
    private var sharedUris: List<Uri> = emptyList()

    private val visualPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        if (uris.isEmpty()) {
            finish()
        } else {
            runBatchUpload(uris, selectedProfile)
        }
    }

    private val fallbackDocPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            finish()
        } else {
            runBatchUpload(uris, selectedProfile)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PixelNasApp
        remoteUrlInput = app.preferences.current.remoteNasUrl

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val isWidgetConfigure = intent?.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE ||
            intent?.getBooleanExtra(PixelNasUploadWidgetProvider.EXTRA_CONFIGURE_ONLY, false) == true

        sharedUris = extractSharedUris(intent)
        selectedProfile = intent?.getStringExtra(PixelNasUploadWidgetProvider.EXTRA_PROFILE)
            ?.ifBlank { "StorageSaver" } ?: "StorageSaver"

        uiMode = when {
            isWidgetConfigure -> "configure"
            sharedUris.isNotEmpty() -> "share_pick_profile"
            else -> "uploading"
        }

        setContent {
            PixelNasTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ElevatedCard(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                when (uiMode) {
                                    "configure" -> {
                                        Text(
                                            "Pixel Photo NAS Upload Widget",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Leave blank if this widget runs directly on your Pixel NAS phone, OR enter your closet Pixel's Wi-Fi URL (or auto-discover via mDNS) to upload from your daily Android phone.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        OutlinedTextField(
                                            value = remoteUrlInput,
                                            onValueChange = { remoteUrlInput = it },
                                            label = { Text("Closet Pixel Wi-Fi URL (e.g. http://192.168.1.50:8080)") },
                                            placeholder = { Text("Blank = Local Device Folder") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (statusMessage.isNotBlank() && statusMessage != "Opening Android Photo Picker...") {
                                            Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    statusMessage = "Scanning Wi-Fi for PixelPhotoNAS..."
                                                    lifecycleScope.launch {
                                                        val found = discoverNasOnLan(this@WidgetUploadActivity)
                                                        if (found != null) {
                                                            remoteUrlInput = found
                                                            statusMessage = "Found closet Pixel at $found!"
                                                        } else {
                                                            statusMessage = "No remote Pixel found — enter http://<IP>:8080 or leave blank for local mode."
                                                        }
                                                    }
                                                }
                                            ) {
                                                Text("Auto-Find on Wi-Fi")
                                            }
                                            Button(
                                                onClick = {
                                                    app.preferences.update {
                                                        it.copy(remoteNasUrl = remoteUrlInput.trim().trimEnd('/'))
                                                    }
                                                    PixelNasUploadWidgetProvider.refreshAllWidgets(this@WidgetUploadActivity)
                                                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                                        val resultValue = Intent().putExtra(
                                                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                                                            appWidgetId
                                                        )
                                                        setResult(Activity.RESULT_OK, resultValue)
                                                    }
                                                    Toast.makeText(
                                                        this@WidgetUploadActivity,
                                                        "Pixel Photo NAS Widget ready!",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    finish()
                                                }
                                            ) {
                                                Text("Save Widget")
                                            }
                                        }
                                    }

                                    "share_pick_profile" -> {
                                        Text(
                                            "Upload ${sharedUris.size} Shared Item(s) to Pixel NAS",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Choose which Per-Folder Profile should process these files:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Button(
                                            onClick = { runBatchUpload(sharedUris, "StorageSaver") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("StorageSaver (/Incoming/StorageSaver → ≤16 MP)")
                                        }
                                        OutlinedButton(
                                            onClick = { runBatchUpload(sharedUris, "Original") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Original (/Incoming/Original → 100% Untouched)")
                                        }
                                        OutlinedButton(
                                            onClick = { runBatchUpload(sharedUris, "Family") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Family (/Incoming/Family → Output/Family)")
                                        }
                                    }

                                    else -> {
                                        Text(
                                            "Uploading to Pixel Photo NAS ($selectedProfile)",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { uploadProgress },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (uiMode == "uploading" && savedInstanceState == null) {
            try {
                visualPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            } catch (_: Exception) {
                fallbackDocPicker.launch(arrayOf("image/*", "video/*"))
            }
        }
    }

    private fun extractSharedUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val list = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val single: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (single != null) list.add(single)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val multi: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (multi != null) list.addAll(multi)
            }
        }
        return list
    }

    private fun runBatchUpload(uris: List<Uri>, profile: String) {
        uiMode = "uploading"
        selectedProfile = profile
        val app = application as PixelNasApp

        lifecycleScope.launch {
            val cfg = app.preferences.current
            val remoteBase = cfg.remoteNasUrl.trim().trimEnd('/')
            var okCount = 0
            try {
                uris.forEachIndexed { idx, uri ->
                    val name = resolveDisplayName(uri)
                    statusMessage = "Sending ${idx + 1}/${uris.size}: $name → /$profile"
                    uploadProgress = idx.toFloat() / uris.size.coerceAtLeast(1).toFloat()

                    withContext(Dispatchers.IO) {
                        if (remoteBase.isNotBlank()) {
                            uploadUriOverHttp(uri, name, profile, remoteBase, cfg.authEnabled, cfg.authUsername, cfg.authPassword)
                        } else {
                            copyUriToLocalIncoming(app, uri, name, profile)
                        }
                    }
                    okCount++
                    uploadProgress = (idx + 1).toFloat() / uris.size.coerceAtLeast(1).toFloat()
                }
                val summaryMsg = "✓ Sent $okCount file(s) → $profile"
                app.preferences.update { it.copy(widgetStatusLine = summaryMsg) }
                PixelNasUploadWidgetProvider.refreshAllWidgets(this@WidgetUploadActivity)
                Toast.makeText(this@WidgetUploadActivity, summaryMsg, Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                val err = "Upload error: ${e.message ?: "Check Wi-Fi NAS URL"}"
                statusMessage = err
                app.preferences.update { it.copy(widgetStatusLine = "⚠ Upload failed") }
                PixelNasUploadWidgetProvider.refreshAllWidgets(this@WidgetUploadActivity)
                Toast.makeText(this@WidgetUploadActivity, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyUriToLocalIncoming(app: PixelNasApp, uri: Uri, displayName: String, profile: String) {
        val (incDir, _) = app.preferences.ensureFolders()
        val targetDir = File(incDir, profile).apply { mkdirs() }
        var dest = File(targetDir, displayName)
        if (dest.exists()) {
            dest = File(targetDir, "${dest.nameWithoutExtension}_${UUID.randomUUID().toString().take(5)}.${dest.extension}")
        }
        val tmp = File(targetDir, ".widget_${UUID.randomUUID()}.part")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output, 65536) }
        } ?: throw IllegalStateException("Cannot read selected media URI")
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        app.watcher.enqueueIfAuto(dest)
    }

    private fun uploadUriOverHttp(
        uri: Uri,
        displayName: String,
        profile: String,
        baseUrl: String,
        authEnabled: Boolean,
        username: String,
        password: String
    ) {
        val boundary = "----PixelPhotoNasBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val encProfile = URLEncoder.encode(profile, "UTF-8")
        val endpoint = URL("$baseUrl/api/upload?profile=$encProfile")
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 120_000
            setChunkedStreamingMode(65536)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (authEnabled && password.isNotBlank()) {
                val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $token")
            }
        }
        try {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            BufferedOutputStream(conn.outputStream, 65536).use { out ->
                val header = "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"files\"; filename=\"${displayName.replace("\"", "_")}\"\r\n" +
                    "Content-Type: $mime\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                contentResolver.openInputStream(uri)?.use { input ->
                    input.copyTo(out, 65536)
                } ?: throw IllegalStateException("Cannot open media stream")
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("NAS HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val raw = c.getString(0)
                if (!raw.isNullOrBlank()) return File(raw).name
            }
        }
        val mime = contentResolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("video") -> "mp4"
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("heic") -> "heic"
            else -> "jpg"
        }
        return "PXL_${System.currentTimeMillis()}.$ext"
    }

    companion object {
        suspend fun discoverNasOnLan(context: Context): String? = withContext(Dispatchers.IO) {
            withTimeoutOrNull(3500L) {
                suspendCancellableCoroutine { cont ->
                    val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
                    var discListener: NsdManager.DiscoveryListener? = null
                    discListener = object : NsdManager.DiscoveryListener {
                        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
                        override fun onDiscoveryStarted(serviceType: String?) = Unit
                        override fun onDiscoveryStopped(serviceType: String?) = Unit
                        override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit

                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            if (serviceInfo.serviceName.contains("PixelPhotoNAS", ignoreCase = true)) {
                                @Suppress("DEPRECATION")
                                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(si: NsdServiceInfo?, err: Int) = Unit
                                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                                        @Suppress("DEPRECATION")
                                        val host = resolved.host?.hostAddress
                                        val port = resolved.port
                                        try {
                                            discListener?.let { nsd.stopServiceDiscovery(it) }
                                        } catch (_: Exception) {
                                        }
                                        if (!host.isNullOrBlank() && cont.isActive) {
                                            cont.resume("http://$host:$port")
                                        }
                                    }
                                })
                            }
                        }
                    }
                    try {
                        nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discListener)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                    cont.invokeOnCancellation {
                        try {
                            nsd.stopServiceDiscovery(discListener)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }
}
