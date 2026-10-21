package com.pixelnas.household.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.pixelnas.household.model.SmbAuthMode
import com.pixelnas.household.model.SmbConnectionConfig
import com.pixelnas.household.model.SmbRemoteEntry
import com.pixelnas.household.model.SmbShareSpace
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Native Android Samba / SMB2 / SMB3 File Share Client built on smbj.
 *
 * Connects directly to any Samba server on standard port 445 (Linux smbd, Synology, TrueNAS,
 * OpenMediaVault, Windows File Sharing) AND unrooted Pixel Photo NAS shares on port 4445!
 */
class SmbShareClient {

    private val smbConfig: SmbConfig = SmbConfig.builder()
        .withTimeout(15, TimeUnit.SECONDS)
        .withSoTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun buildAuthContext(cfg: SmbConnectionConfig): AuthenticationContext {
        return when (cfg.authMode) {
            SmbAuthMode.GUEST -> AuthenticationContext.guest()
            SmbAuthMode.CREDENTIALS -> AuthenticationContext(
                cfg.username.trim().ifBlank { "pixel" },
                cfg.password.toCharArray(),
                cfg.workgroup.trim()
            )
        }
    }

    /**
     * Opens a live SMB2/SMB3 connection + authenticated DiskShare, automatically falling back between
     * standard Samba port 445 and unrooted Pixel NAS port 4445 if the first TCP port refuses connection.
     */
    fun <T> withDiskShare(
        cfg: SmbConnectionConfig,
        block: (share: DiskShare, activePort: Int) -> T
    ): T {
        val cleanHost = cfg.host.trim()
        require(cleanHost.isNotEmpty()) { "NAS Samba host/IP is not configured yet." }

        val candidatePorts = linkedSetOf(cfg.port).apply {
            if (cfg.port == 445) add(4445)
        }

        var lastConnectError: Throwable? = null
        SMBClient(smbConfig).use { client ->
            for (port in candidatePorts) {
                val conn: Connection = try {
                    client.connect(cleanHost, port)
                } catch (e: ConnectException) {
                    lastConnectError = e
                    continue
                } catch (e: SocketTimeoutException) {
                    lastConnectError = e
                    continue
                }
                conn.use { activeConn ->
                    val session = activeConn.authenticate(buildAuthContext(cfg))
                    session.use { activeSession ->
                        val share = activeSession.connectShare(cfg.cleanShareName) as? DiskShare
                            ?: error("Share '${cfg.cleanShareName}' is not a Samba disk share.")
                        share.use { diskShare ->
                            return block(diskShare, port)
                        }
                    }
                }
            }
        }
        throw (lastConnectError ?: ConnectException("Could not reach Samba share at $cleanHost:${cfg.port}"))
    }

    fun testConnectionAndInspect(cfg: SmbConnectionConfig): SmbShareSpace {
        return withDiskShare(cfg) { share, activePort ->
            val info = share.shareInformation
            val folders = share.list("")
                .filter { it.fileName != "." && it.fileName != ".." && isDirAttr(it.fileAttributes) }
                .map { it.fileName }
                .sorted()
            SmbShareSpace(
                activePort = activePort,
                totalBytes = info.totalSpace,
                freeBytes = info.callerFreeSpace.takeIf { it > 0L } ?: info.freeSpace,
                topFolders = folders
            )
        }
    }

    fun browseFolder(cfg: SmbConnectionConfig, relativePath: String = ""): List<SmbRemoteEntry> {
        val cleanRel = normalizeSmbPath(relativePath)
        return withDiskShare(cfg) { share, _ ->
            share.list(cleanRel)
                .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".upload_") }
                .map { item ->
                    val childRel = if (cleanRel.isEmpty()) item.fileName else "$cleanRel\\${item.fileName}"
                    val isDir = isDirAttr(item.fileAttributes)
                    SmbRemoteEntry(
                        name = item.fileName,
                        relativePath = childRel,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else item.endOfFile,
                        lastModifiedMillis = item.lastWriteTime.toEpochMillis()
                    )
                }
                .sortedWith(compareByDescending<SmbRemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    fun createFolder(cfg: SmbConnectionConfig, relativePath: String) {
        val cleanRel = normalizeSmbPath(relativePath)
        if (cleanRel.isEmpty()) return
        withDiskShare(cfg) { share, _ ->
            ensureSmbDirectoryRecursive(share, cleanRel)
        }
    }

    fun deleteEntry(cfg: SmbConnectionConfig, relativePath: String, isDirectory: Boolean) {
        val cleanRel = normalizeSmbPath(relativePath)
        require(cleanRel.isNotEmpty()) { "Cannot delete share root." }
        withDiskShare(cfg) { share, _ ->
            if (isDirectory) {
                share.rmdir(cleanRel, true)
            } else {
                share.rm(cleanRel)
            }
        }
    }

    fun readFileBytes(cfg: SmbConnectionConfig, relativePath: String): ByteArray {
        val cleanRel = normalizeSmbPath(relativePath)
        return withDiskShare(cfg) { share, _ ->
            share.openFile(
                cleanRel,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { remoteFile ->
                remoteFile.inputStream.readBytes()
            }
        }
    }

    /**
     * Streams a photo or video directly into the remote Samba share using atomic staging:
     *   1. Ensures destination directory chain exists (`Incoming\StorageSaver`, etc.)
     *   2. Streams 64 KB chunks to `.<filename>.part` so NAS folder watchers ignore partial Wi-Fi writes
     *   3. Preserves original EXIF/MediaStore capture timestamp via SMB2 FileBasicInformation
     *   4. Atomically renames `.<filename>.part` -> `<filename>` inside the Samba share
     */
    fun uploadStreamAtomically(
        cfg: SmbConnectionConfig,
        filename: String,
        inputStream: InputStream,
        capturedAtMillis: Long? = null,
        overrideProfileFolder: String? = null,
        onBytesSent: ((Long) -> Unit)? = null
    ): Pair<String, Long> {
        val safeName = filename.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank {
            "IMG_${System.currentTimeMillis()}.jpg"
        }
        val destDir = normalizeSmbPath(cfg.destinationRelativeDir(overrideProfileFolder))

        return withDiskShare(cfg) { share, activePort ->
            if (destDir.isNotEmpty()) {
                ensureSmbDirectoryRecursive(share, destDir)
            }
            val partRel = if (destDir.isEmpty()) ".upload_$safeName.part" else "$destDir\\.upload_$safeName.part"
            val finalRel = if (destDir.isEmpty()) safeName else "$destDir\\$safeName"

            var totalWritten = 0L
            share.openFile(
                partRel,
                EnumSet.of(
                    AccessMask.GENERIC_WRITE,
                    AccessMask.DELETE,
                    AccessMask.FILE_WRITE_ATTRIBUTES
                ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            ).use { remoteFile ->
                remoteFile.outputStream.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        totalWritten += read
                        onBytesSent?.invoke(totalWritten)
                    }
                    out.flush()
                }
                if (capturedAtMillis != null && capturedAtMillis > 0L) {
                    val ft = FileTime.ofEpochMillis(capturedAtMillis)
                    runCatching {
                        remoteFile.setFileInformation(
                            FileBasicInformation(
                                ft,
                                ft,
                                ft,
                                ft,
                                FileAttributes.FILE_ATTRIBUTE_NORMAL.value
                            )
                        )
                    }
                }
                remoteFile.rename(finalRel, true)
            }

            val unc = "\\\\${cfg.host.trim()}\\${cfg.cleanShareName}\\$finalRel"
            unc to totalWritten
        }
    }

    fun ensureSmbDirectoryRecursive(share: DiskShare, smbDirPath: String) {
        val parts = normalizeSmbPath(smbDirPath).split('\\').filter { it.isNotBlank() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current\\$part"
            if (!share.folderExists(current)) {
                runCatching { share.mkdir(current) }
            }
        }
    }

    companion object {
        private const val DIR_ATTR_MASK = 0x10L

        fun normalizeSmbPath(raw: String): String =
            raw.trim().replace('/', '\\').trim('\\')

        private fun isDirAttr(attrs: Long): Boolean = (attrs and DIR_ATTR_MASK) != 0L
    }
}
