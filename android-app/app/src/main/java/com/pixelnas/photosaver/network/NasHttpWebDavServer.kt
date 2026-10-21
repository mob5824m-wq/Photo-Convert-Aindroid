package com.pixelnas.photosaver.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.StatFs
import android.util.Base64
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.pixelnas.photosaver.converter.StorageSaverConverter
import com.pixelnas.photosaver.data.HistoryStore
import com.pixelnas.photosaver.data.NasPreferences
import com.pixelnas.photosaver.model.ConversionRecord
import com.pixelnas.photosaver.util.PixelDeviceDetector
import com.pixelnas.photosaver.watcher.IncomingFolderWatcher
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.round

class NasHttpWebDavServer(
    private val context: Context,
    private val prefs: NasPreferences,
    private val historyStore: HistoryStore,
    private val watcher: IncomingFolderWatcher,
    private val ftpServer: NasFtpServer
) {
    private var engine: CIOApplicationEngine? = null

    @Synchronized
    fun start() {
        stop()
        val port = prefs.current.httpPort
        val srv = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            routing {
                // Static Web UI served directly from APK assets/webui/
                get("/") { serveAsset(call, "webui/index.html", ContentType.Text.Html) }
                get("/index.html") { serveAsset(call, "webui/index.html", ContentType.Text.Html) }
                get("/styles.css") { serveAsset(call, "webui/styles.css", ContentType.Text.CSS) }
                get("/app.js") { serveAsset(call, "webui/app.js", ContentType.Application.JavaScript) }

                // JSON Status API (100% compatible with shared Web Portal)
                get("/api/status") {
                    val cfg = prefs.current
                    val (incDir, outDir) = prefs.ensureFolders()
                    val incFiles = incDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
                    val outFiles = outDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
                    val statFs = StatFs(outDir.absolutePath)
                    val totalBytes = statFs.totalBytes
                    val freeBytes = statFs.availableBytes
                    val usedBytes = totalBytes - freeBytes

                    val ips = PixelDeviceDetector.getLanIpv4Addresses()
                    val hostHdr = call.request.headers[HttpHeaders.Host] ?: "${ips.first()}:$port"
                    val baseUrl = "http://$hostHdr"
                    val hw = PixelDeviceDetector.detectCurrentDevice()
                    val summary = historyStore.summary()
                    val wState = watcher.state.value

                    val root = JSONObject().apply {
                        put("app_name", "Pixel Photo NAS")
                        put("version", "1.0.0")
                        put("settings", configToJson(cfg, hw.displayName, hw.googlePhotosTier, hw.notes))
                        put("worker", JSONObject().apply {
                            put("watcher_running", wState.serviceRunning)
                            put("auto_watch_enabled", cfg.autoWatchEnabled)
                            put("queue_size", wState.queueSize)
                            put("active_file", wState.activeFile ?: JSONObject.NULL)
                            put("last_error", wState.lastError)
                        })
                        put("stats", summaryToJson(summary))
                        put("folders", JSONObject().apply {
                            put("incoming", JSONObject().apply {
                                put("path", incDir.absolutePath)
                                put("file_count", incFiles.size)
                                put("total_bytes", incFiles.sumOf { it.length() })
                                put("nomedia_protected", File(incDir, ".nomedia").exists())
                            })
                            put("output", JSONObject().apply {
                                put("path", outDir.absolutePath)
                                put("file_count", outFiles.size)
                                put("total_bytes", outFiles.sumOf { it.length() })
                            })
                        })
                        put("storage", JSONObject().apply {
                            put("total_bytes", totalBytes)
                            put("used_bytes", usedBytes)
                            put("free_bytes", freeBytes)
                            put("used_pct", if (totalBytes > 0) round(usedBytes * 1000.0 / totalBytes) / 10.0 else 0.0)
                        })
                        put("network", JSONObject().apply {
                            put("lan_ips", JSONArray(ips))
                            put("web_ui_url", baseUrl)
                            put("webdav_url", "$baseUrl/webdav/")
                            put("webdav_incoming_url", "$baseUrl/webdav/Incoming/")
                            put("ftp_enabled", ftpServer.activePort > 0)
                            put("ftp_port", if (ftpServer.activePort > 0) ftpServer.activePort else cfg.ftpPort)
                            put("ftp_url", "ftp://${ips.first()}:${cfg.ftpPort}/")
                            put("video_engine_available", true)
                        })
                    }
                    call.respondText(root.toString(), ContentType.Application.Json)
                }

                get("/api/settings") {
                    val hw = PixelDeviceDetector.detectCurrentDevice()
                    call.respondText(
                        configToJson(prefs.current, hw.displayName, hw.googlePhotosTier, hw.notes).toString(),
                        ContentType.Application.Json
                    )
                }

                post("/api/settings") {
                    val body = JSONObject(call.receiveText())
                    val updated = prefs.update { old ->
                        old.copy(
                            deviceProfile = body.optString("device_profile", old.deviceProfile),
                            storageSaverEnabled = body.optBoolean("storage_saver_enabled", old.storageSaverEnabled),
                            deleteOriginals = body.optBoolean("delete_originals", old.deleteOriginals),
                            autoWatchEnabled = body.optBoolean("auto_watch_enabled", old.autoWatchEnabled),
                            compressVideos = body.optBoolean("compress_videos", old.compressVideos),
                            maxMegapixels = body.optDouble("max_megapixels", old.maxMegapixels),
                            jpegQuality = body.optInt("jpeg_quality", old.jpegQuality),
                            maxVideoShortSide = body.optInt("max_video_short_side", old.maxVideoShortSide),
                            incomingDir = body.optString("incoming_dir", old.incomingDir),
                            outputDir = body.optString("output_dir", old.outputDir),
                            ftpPort = body.optInt("ftp_port", old.ftpPort),
                            authEnabled = body.optBoolean("auth_enabled", old.authEnabled),
                            authUsername = body.optString("auth_username", old.authUsername),
                            authPassword = body.optString("auth_password", old.authPassword),
                            thermalLimitCelsius = body.optDouble("thermal_limit_celsius", old.thermalLimitCelsius.toDouble()).toFloat()
                        )
                    }
                    ftpServer.startOrReload()
                    if (updated.autoWatchEnabled) watcher.processAllNow()
                    val hw = PixelDeviceDetector.detectCurrentDevice()
                    call.respondText(
                        configToJson(updated, hw.displayName, hw.googlePhotosTier, hw.notes).toString(),
                        ContentType.Application.Json
                    )
                }

                get("/api/files") {
                    val folder = call.request.queryParameters["folder"] ?: "output"
                    val (incDir, outDir) = prefs.ensureFolders()
                    val targetDir = if (folder == "incoming") incDir else outDir
                    val arr = JSONArray()
                    targetDir.listFiles()
                        ?.filter { it.isFile && !it.name.startsWith(".") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.forEach { file ->
                            val item = inspectFileJson(file, folder)
                            if (folder == "output") {
                                historyStore.lookupByOutput(file.name)?.let {
                                    item.put("conversion", recordToJson(it))
                                }
                            }
                            arr.put(item)
                        }
                    val resp = JSONObject().apply {
                        put("folder", folder)
                        put("path", targetDir.absolutePath)
                        put("files", arr)
                    }
                    call.respondText(resp.toString(), ContentType.Application.Json)
                }

                post("/api/upload") {
                    val (incDir, _) = prefs.ensureFolders()
                    val multipart = call.receiveMultipart()
                    val uploaded = JSONArray()
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val rawName = File(part.originalFileName ?: "upload_${UUID.randomUUID()}.jpg").name
                            var dest = File(incDir, rawName)
                            if (dest.exists()) {
                                dest = File(incDir, "${dest.nameWithoutExtension}_${UUID.randomUUID().toString().take(6)}.${dest.extension}")
                            }
                            val tmp = File(incDir, ".upload_${UUID.randomUUID()}.part")
                            part.streamProvider().use { input ->
                                FileOutputStream(tmp).use { output -> input.copyTo(output, 65536) }
                            }
                            tmp.renameTo(dest)
                            if (StorageSaverConverter.isSupportedMedia(dest)) {
                                watcher.enqueueIfAuto(dest)
                                uploaded.put(dest.name)
                            }
                        }
                        part.dispose()
                    }
                    call.respondText(
                        JSONObject().put("uploaded", uploaded).put("auto_queued", prefs.current.autoWatchEnabled).toString(),
                        ContentType.Application.Json
                    )
                }

                post("/api/process") {
                    val queued = watcher.processAllNow()
                    call.respondText(JSONObject().put("queued", queued).toString(), ContentType.Application.Json)
                }

                post("/api/demo/seed") {
                    val (incDir, _) = prefs.ensureFolders()
                    val created = StorageSaverConverter.generateSamplePhotos(incDir)
                    if (prefs.current.autoWatchEnabled) watcher.processAllNow()
                    call.respondText(
                        JSONObject().put("seeded", JSONArray(created)).put("count", created.size).toString(),
                        ContentType.Application.Json
                    )
                }

                get("/api/history") {
                    val arr = JSONArray()
                    historyStore.recent(60).forEach { arr.put(recordToJson(it)) }
                    val resp = JSONObject()
                        .put("items", arr)
                        .put("summary", summaryToJson(historyStore.summary()))
                    call.respondText(resp.toString(), ContentType.Application.Json)
                }

                delete("/api/history") {
                    historyStore.clear()
                    call.respondText(JSONObject().put("cleared", true).toString(), ContentType.Application.Json)
                }

                delete("/api/files/{folder}/{filename}") {
                    val folder = call.parameters["folder"] ?: "output"
                    val name = File(call.parameters["filename"] ?: "").name
                    val (incDir, outDir) = prefs.ensureFolders()
                    val target = File(if (folder == "incoming") incDir else outDir, name)
                    if (target.isFile) target.delete()
                    call.respondText(JSONObject().put("deleted", name).toString(), ContentType.Application.Json)
                }

                post("/api/files/{folder}/clear") {
                    val folder = call.parameters["folder"] ?: "output"
                    val (incDir, outDir) = prefs.ensureFolders()
                    val dir = if (folder == "incoming") incDir else outDir
                    var removed = 0
                    dir.listFiles()?.forEach {
                        if (it.isFile && !it.name.startsWith(".") && it.delete()) removed++
                    }
                    call.respondText(JSONObject().put("deleted_count", removed).toString(), ContentType.Application.Json)
                }

                get("/api/media/{folder}/{filename}") {
                    val folder = call.parameters["folder"] ?: "output"
                    val name = File(call.parameters["filename"] ?: "").name
                    val (incDir, outDir) = prefs.ensureFolders()
                    val target = File(if (folder == "incoming") incDir else outDir, name)
                    if (!target.isFile) {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound)
                    } else {
                        call.respondFile(target)
                    }
                }

                get("/api/thumb/{folder}/{filename}") {
                    val folder = call.parameters["folder"] ?: "output"
                    val name = File(call.parameters["filename"] ?: "").name
                    val (incDir, outDir) = prefs.ensureFolders()
                    val target = File(if (folder == "incoming") incDir else outDir, name)
                    if (!target.isFile) {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound)
                    } else {
                        call.respondBytes(renderThumbBytes(target), ContentType.Image.JPEG)
                    }
                }

                get("/api/qrcode") {
                    val text = call.request.queryParameters["text"] ?: "http://127.0.0.1:$port"
                    call.respondBytes(PixelDeviceDetector.renderQrPngBytes(text), ContentType.Image.PNG)
                }

                // Full RFC 4918 WebDAV Mount Endpoint (/webdav/Incoming & /webdav/Output)
                route("/webdav/{...}") {
                    handle { handleWebDav(call) }
                }
                route("/webdav") {
                    handle { handleWebDav(call) }
                }
            }
        }
        srv.start(wait = false)
        engine = srv
    }

    @Synchronized
    fun stop() {
        try {
            engine?.stop(300, 800)
        } catch (_: Exception) {
        }
        engine = null
    }

    private suspend fun serveAsset(call: ApplicationCall, assetPath: String, contentType: ContentType) {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        call.respondBytes(bytes, contentType)
    }

    private suspend fun handleWebDav(call: ApplicationCall) {
        if (!checkBasicAuth(call)) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Pixel Photo NAS\"")
            call.response.header("DAV", "1, 2")
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return
        }

        call.response.header("DAV", "1, 2")
        call.response.header("MS-Author-Via", "DAV")
        call.response.header(HttpHeaders.Allow, "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, MOVE, COPY")

        val method = call.request.httpMethod
        if (method == HttpMethod.Options) {
            call.respondText("", status = HttpStatusCode.OK)
            return
        }

        val rawRel = URLDecoder.decode(call.request.path().removePrefix("/webdav"), "UTF-8").trim('/')
        val (kind, fsTarget) = resolveDavPath(rawRel)

        when (method.value.uppercase()) {
            "PROPFIND" -> {
                val depth = call.request.headers["Depth"] ?: "1"
                val xml = buildPropfindXml(kind, rawRel, fsTarget, depth)
                if (xml == null) {
                    call.respondText("Not Found", status = HttpStatusCode.NotFound)
                } else {
                    call.respondText(xml, ContentType.Application.Xml, HttpStatusCode.MultiStatus)
                }
            }
            "GET", "HEAD" -> {
                if (fsTarget == null || !fsTarget.exists()) {
                    call.respondText("Not Found", status = HttpStatusCode.NotFound)
                } else if (fsTarget.isDirectory) {
                    call.respondText("Pixel Photo NAS WebDAV Share: ${fsTarget.name}\n")
                } else {
                    call.respondFile(fsTarget)
                }
            }
            "MKCOL" -> {
                if (fsTarget == null || fsTarget.exists()) {
                    call.respondText("", status = HttpStatusCode.MethodNotAllowed)
                } else {
                    fsTarget.mkdirs()
                    call.respondText("", status = HttpStatusCode.Created)
                }
            }
            "PUT" -> {
                if (fsTarget == null) {
                    call.respondText("", status = HttpStatusCode.Forbidden)
                    return
                }
                fsTarget.parentFile?.mkdirs()
                val existed = fsTarget.exists()
                val tmp = File(fsTarget.parentFile, ".dav_${UUID.randomUUID()}.tmp")
                val channel = call.receiveChannel()
                val buf = ByteArray(65536)
                FileOutputStream(tmp).use { out ->
                    while (true) {
                        val read = channel.readAvailable(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
                tmp.renameTo(fsTarget)
                if (kind == "incoming") watcher.enqueueIfAuto(fsTarget)
                call.respondText("", status = if (existed) HttpStatusCode.NoContent else HttpStatusCode.Created)
            }
            "DELETE" -> {
                val (incDir, outDir) = prefs.ensureFolders()
                if (fsTarget == null || !fsTarget.exists() || fsTarget == incDir || fsTarget == outDir) {
                    call.respondText("", status = HttpStatusCode.NotFound)
                } else {
                    fsTarget.deleteRecursively()
                    call.respondText("", status = HttpStatusCode.NoContent)
                }
            }
            "MOVE", "COPY" -> {
                val destHdr = call.request.headers[HttpHeaders.Destination] ?: ""
                val destRel = URI(destHdr).path.removePrefix("/webdav").trim('/')
                val (destKind, destFile) = resolveDavPath(destRel)
                if (fsTarget == null || !fsTarget.exists() || destFile == null) {
                    call.respondText("", status = HttpStatusCode.BadGateway)
                } else {
                    val existed = destFile.exists()
                    destFile.parentFile?.mkdirs()
                    if (method.value.uppercase() == "MOVE") {
                        if (!fsTarget.renameTo(destFile)) {
                            fsTarget.copyTo(destFile, overwrite = true)
                            fsTarget.delete()
                        }
                    } else {
                        fsTarget.copyTo(destFile, overwrite = true)
                    }
                    if (destKind == "incoming" && destFile.isFile) watcher.enqueueIfAuto(destFile)
                    call.respondText("", status = if (existed) HttpStatusCode.NoContent else HttpStatusCode.Created)
                }
            }
            else -> call.respondText("", status = HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun checkBasicAuth(call: ApplicationCall): Boolean {
        val cfg = prefs.current
        if (!cfg.authEnabled || cfg.authPassword.isBlank()) return true
        val hdr = call.request.headers[HttpHeaders.Authorization] ?: return false
        if (!hdr.startsWith("Basic ")) return false
        val decoded = String(Base64.decode(hdr.removePrefix("Basic ").trim(), Base64.DEFAULT))
        return decoded == "${cfg.authUsername}:${cfg.authPassword}"
    }

    private fun resolveDavPath(rel: String): Pair<String, File?> {
        val (incDir, outDir) = prefs.ensureFolders()
        if (rel.isBlank()) return "root" to null
        val parts = rel.split("/").filter { it.isNotBlank() && it != "." && it != ".." }
        if (parts.isEmpty()) return "root" to null
        val head = parts.first().lowercase()
        val tail = parts.drop(1).joinToString("/")
        return when (head) {
            "incoming" -> "incoming" to (if (tail.isEmpty()) incDir else File(incDir, tail))
            "output" -> "output" to (if (tail.isEmpty()) outDir else File(outDir, tail))
            else -> "not_found" to null
        }
    }

    private fun buildPropfindXml(kind: String, rel: String, fsTarget: File?, depth: String): String? {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
        if (kind == "root") {
            sb.append(davResponseEntry("/webdav/", "PixelPhotoNAS", true, 0L, System.currentTimeMillis()))
            if (depth != "0") {
                val (incDir, outDir) = prefs.ensureFolders()
                sb.append(davResponseEntry("/webdav/Incoming/", "Incoming", true, 0L, incDir.lastModified()))
                sb.append(davResponseEntry("/webdav/Output/", "Output", true, 0L, outDir.lastModified()))
            }
        } else if (fsTarget != null && fsTarget.exists()) {
            val cleanHref = "/webdav/" + rel.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } +
                (if (fsTarget.isDirectory) "/" else "")
            sb.append(davResponseEntry(cleanHref, fsTarget.name, fsTarget.isDirectory, fsTarget.length(), fsTarget.lastModified()))
            if (fsTarget.isDirectory && depth != "0") {
                fsTarget.listFiles()?.filter { !it.name.startsWith(".") }?.forEach { child ->
                    val enc = URLEncoder.encode(child.name, "UTF-8").replace("+", "%20")
                    val href = cleanHref + enc + if (child.isDirectory) "/" else ""
                    sb.append(davResponseEntry(href, child.name, child.isDirectory, child.length(), child.lastModified()))
                }
            }
        } else {
            return null
        }
        sb.append("</D:multistatus>")
        return sb.toString()
    }

    private fun davResponseEntry(href: String, name: String, isDir: Boolean, size: Long, mtimeMs: Long): String {
        val httpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date(mtimeMs))
        val typeXml = if (isDir) "<D:collection/>" else ""
        val sizeXml = if (!isDir) "<D:getcontentlength>$size</D:getcontentlength>" else ""
        return """
            <D:response>
              <D:href>$href</D:href>
              <D:propstat>
                <D:prop>
                  <D:displayname>${escapeXml(name)}</D:displayname>
                  <D:resourcetype>$typeXml</D:resourcetype>
                  $sizeXml
                  <D:getlastmodified>$httpDate</D:getlastmodified>
                </D:prop>
                <D:status>HTTP/1.1 200 OK</D:status>
              </D:propstat>
            </D:response>
        """.trimIndent()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inspectFileJson(file: File, folder: String): JSONObject {
        val ext = file.extension.lowercase()
        val isVideo = ext in StorageSaverConverter.VIDEO_EXTENSIONS
        var w = 0
        var h = 0
        var cam = ""
        var dt = ""
        var gps = ""
        if (!isVideo) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            w = opts.outWidth.coerceAtLeast(0)
            h = opts.outHeight.coerceAtLeast(0)
            try {
                val exif = ExifInterface(file.absolutePath)
                cam = listOfNotNull(
                    exif.getAttribute(ExifInterface.TAG_MAKE)?.trim(),
                    exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                ).joinToString(" ").trim()
                dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ""
                exif.latLong?.let { gps = "${round(it[0] * 10000.0) / 10000.0}, ${round(it[1] * 10000.0) / 10000.0}" }
            } catch (_: Exception) {
            }
        } else {
            val dims = StorageSaverConverter.probeVideoDimensions(file)
            w = dims.first
            h = dims.second
        }
        val mp = if (w > 0 && h > 0) round((w.toLong() * h.toLong()) / 10000.0) / 100.0 else 0.0
        val encName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        return JSONObject().apply {
            put("name", file.name)
            put("folder", folder)
            put("size_bytes", file.length())
            put("modified_at", file.lastModified() / 1000.0)
            put("media_type", if (isVideo) "video" else "photo")
            put("width", w)
            put("height", h)
            put("megapixels", mp)
            put("camera_model", cam)
            put("date_taken", dt)
            put("gps_coords", gps)
            put("is_processing", watcher.state.value.activeFile == file.name)
            put("media_url", "/api/media/$folder/$encName")
            put("thumb_url", "/api/thumb/$folder/$encName?v=${file.lastModified() / 1000}")
        }
    }

    private fun renderThumbBytes(file: File): ByteArray {
        val out = ByteArrayOutputStream()
        val ext = file.extension.lowercase()
        val bmp: Bitmap? = try {
            if (ext in StorageSaverConverter.VIDEO_EXTENSIONS) {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) {
            null
        }
        if (bmp != null) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
        } else {
            val fallback = Bitmap.createBitmap(Size(240, 160).width, 160, Bitmap.Config.RGB_565)
            fallback.compress(Bitmap.CompressFormat.JPEG, 80, out)
            fallback.recycle()
        }
        return out.toByteArray()
    }

    private fun configToJson(cfg: com.pixelnas.photosaver.model.NasConfig, name: String, tier: String, notes: String): JSONObject {
        return JSONObject().apply {
            put("device_profile", cfg.deviceProfile)
            put("storage_saver_enabled", cfg.storageSaverEnabled)
            put("delete_originals", cfg.deleteOriginals)
            put("auto_watch_enabled", cfg.autoWatchEnabled)
            put("compress_videos", cfg.compressVideos)
            put("max_megapixels", cfg.maxMegapixels)
            put("jpeg_quality", cfg.jpegQuality)
            put("max_video_short_side", cfg.maxVideoShortSide)
            put("incoming_dir", cfg.incomingDir)
            put("output_dir", cfg.outputDir)
            put("ftp_enabled", cfg.ftpEnabled)
            put("ftp_port", cfg.ftpPort)
            put("thermal_limit_celsius", cfg.thermalLimitCelsius)
            put("auth_enabled", cfg.authEnabled)
            put("auth_username", cfg.authUsername)
            put("auth_password", cfg.authPassword)
            put("profile_details", JSONObject().apply {
                put("name", name)
                put("google_photos_tier", tier)
                put("notes", notes)
            })
        }
    }

    private fun recordToJson(r: ConversionRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("original_filename", r.originalFilename)
        put("output_filename", r.outputFilename)
        put("media_type", r.mediaType)
        put("mode", r.mode)
        put("orig_width", r.origWidth)
        put("orig_height", r.origHeight)
        put("orig_mp", r.origMp)
        put("orig_bytes", r.origBytes)
        put("out_width", r.outWidth)
        put("out_height", r.outHeight)
        put("out_mp", r.outMp)
        put("out_bytes", r.outBytes)
        put("saved_bytes", r.savedBytes)
        put("saved_pct", r.savedPct)
        put("exif_preserved", r.exifPreserved)
        put("camera_model", r.cameraModel)
        put("date_taken", r.dateTaken)
        put("gps_coords", r.gpsCoords)
        put("original_deleted", r.originalDeleted)
        put("status", r.status)
    }

    private fun summaryToJson(s: com.pixelnas.photosaver.model.HistorySummary): JSONObject = JSONObject().apply {
        put("total_processed", s.totalProcessed)
        put("saver_count", s.saverCount)
        put("passthrough_count", s.passthroughCount)
        put("total_orig_bytes", s.totalOrigBytes)
        put("total_out_bytes", s.totalOutBytes)
        put("total_saved_bytes", s.totalSavedBytes)
        put("originals_deleted_count", s.originalsDeletedCount)
        put("avg_saved_pct", s.avgSavedPct)
    }
}
