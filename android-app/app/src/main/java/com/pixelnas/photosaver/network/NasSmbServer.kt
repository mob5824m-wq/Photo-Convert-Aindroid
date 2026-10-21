package com.pixelnas.photosaver.network

import android.os.StatFs
import com.pixelnas.photosaver.data.NasPreferences
import com.pixelnas.photosaver.watcher.IncomingFolderWatcher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin Microsoft [MS-SMB2] (SMB 2.0.2 / 2.1) Samba File Share Server.
 *
 * Exposes shares:
 *   - \\<pixel-ip>\PixelNAS   (virtual root with Incoming/, Output/, Quarantine/)
 *   - \\<pixel-ip>\Incoming   (direct write target for Household App, FolderSync, Solid Explorer, Windows/Linux)
 *   - \\<pixel-ip>\Output     (Google Photos ready output folder)
 *   - \\<pixel-ip>\Quarantine (isolated corrupted uploads)
 *   - \\<pixel-ip>\IPC$       (standard SMB IPC pipe share)
 *
 * Supports:
 *   - Direct TCP NetBIOS framing + legacy SMB1 multi-protocol Negotiate upgrade
 *   - SPNEGO + NTLMv2 authentication (pure-Kotlin RFC 1320 MD4 + HMAC-MD5 + RC4 key exchange)
 *   - HMAC-SHA256 SMB2 packet signing (including zero-password Guest/Member key recovery)
 *   - Compound SMB2 requests with related FileId inheritance
 *   - Atomic .part -> final rename (FileRenameInformation) & capture mtime preservation (FileBasicInformation)
 *   - Automatic Storage Saver conversion trigger upon SMB2 CLOSE inside Incoming/
 */
class NasSmbServer(
    private val incomingDirProvider: () -> File,
    private val outputDirProvider: () -> File,
    private val quarantineDirProvider: () -> File,
    private val enabledProvider: () -> Boolean,
    private val portProvider: () -> Int,
    private val shareNameProvider: () -> String,
    private val authEnabledProvider: () -> Boolean,
    private val authUserProvider: () -> String,
    private val authPasswordProvider: () -> String,
    private val onIncomingFileClosed: (File) -> Unit
) {

    constructor(
        prefs: NasPreferences,
        watcher: IncomingFolderWatcher
    ) : this(
        incomingDirProvider = { prefs.ensureFolders().first },
        outputDirProvider = { prefs.ensureFolders().second },
        quarantineDirProvider = { prefs.quarantineFolder() },
        enabledProvider = { prefs.current.smbEnabled },
        portProvider = { prefs.current.smbPort },
        shareNameProvider = { prefs.current.smbShareName },
        authEnabledProvider = { prefs.current.authEnabled && prefs.current.authPassword.isNotBlank() },
        authUserProvider = { prefs.current.authUsername },
        authPasswordProvider = { prefs.current.authPassword },
        onIncomingFileClosed = { file -> watcher.enqueueIfAuto(file) }
    )

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var executor = Executors.newCachedThreadPool()

    @Volatile
    var activePort: Int = 0
        private set

    private val serverGuid = ByteArray(16) { (0x41 + it).toByte() }
    private val rng = SecureRandom()

    @Synchronized
    fun startOrReload() {
        stop()
        if (!enabledProvider()) return

        val preferred = portProvider()
        val candidates = if (preferred == 0) listOf(0) else listOf(preferred, 4445, 0)

        for (candidate in candidates) {
            try {
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(InetSocketAddress("0.0.0.0", candidate), 64)
                serverSocket = sock
                activePort = sock.localPort
                running.set(true)
                if (executor.isShutdown) {
                    executor = Executors.newCachedThreadPool()
                }
                executor.execute { acceptLoop(sock) }
                return
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        activePort = 0
    }

    private fun acceptLoop(sock: ServerSocket) {
        while (running.get() && !sock.isClosed) {
            try {
                val client = sock.accept()
                client.tcpNoDelay = true
                executor.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running.get() || sock.isClosed) break
            }
        }
    }

    private data class OpenHandle(
        val handleId: Long,
        val shareKind: String,
        var relPath: String,
        var fsFile: File?,
        val isDir: Boolean,
        var deleteOnClose: Boolean = false,
        var wasWritten: Boolean = false,
        var dirScanDone: Boolean = false,
        var dirScanIndex: Int = 0
    )

    private class SmbSession(
        val sessionId: Long,
        val serverChallenge: ByteArray
    ) {
        var authenticated: Boolean = false
        var isGuest: Boolean = false
        var sessionKey: ByteArray = ByteArray(16)
        val trees = HashMap<Int, String>()
        val handles = HashMap<Long, OpenHandle>()
        var nextTreeId: Int = 1
        var nextHandleId: Long = 1L
    }

    private fun handleClient(socket: Socket) {
        val sessions = HashMap<Long, SmbSession>()
        var nextSessionId = 0x100001L
        socket.use { sock ->
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            while (running.get() && !sock.isClosed) {
                val hdr4 = readExact(input, 4) ?: break
                val frameLen = ((hdr4[1].toInt() and 0xFF) shl 16) or
                    ((hdr4[2].toInt() and 0xFF) shl 8) or
                    (hdr4[3].toInt() and 0xFF)
                if (frameLen <= 0 || frameLen > 16 * 1024 * 1024) break
                val payload = readExact(input, frameLen) ?: break

                // Legacy SMB1 multi-protocol Negotiate -> SMB2 Negotiate upgrade
                if (payload.size >= 32 &&
                    payload[0] == 0xFF.toByte() &&
                    payload[1] == 'S'.code.toByte() &&
                    payload[2] == 'M'.code.toByte() &&
                    payload[3] == 'B'.code.toByte()
                ) {
                    val negPkt = buildSmb2Packet(
                        command = SMB2_NEGOTIATE,
                        status = STATUS_SUCCESS,
                        messageId = 0L,
                        sessionId = 0L,
                        treeId = 0,
                        body = buildNegotiateBody(0x02FF)
                    )
                    writeNetbiosFrame(output, negPkt)
                    continue
                }

                if (payload.size < 64 ||
                    payload[0] != 0xFE.toByte() ||
                    payload[1] != 'S'.code.toByte() ||
                    payload[2] != 'M'.code.toByte() ||
                    payload[3] != 'B'.code.toByte()
                ) {
                    break
                }

                val pair = processCompoundFrame(payload, sessions, nextSessionId)
                nextSessionId = pair.second
                if (pair.first.isNotEmpty()) {
                    writeNetbiosFrame(output, pair.first)
                }
            }
        }
    }

    private fun writeNetbiosFrame(out: java.io.OutputStream, payload: ByteArray) {
        val len = payload.size
        val hdr = byteArrayOf(
            0x00,
            ((len ushr 16) and 0xFF).toByte(),
            ((len ushr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
        synchronized(out) {
            out.write(hdr)
            out.write(payload)
            out.flush()
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val r = input.read(buf, off, count - off)
            if (r <= 0) return null
            off += r
        }
        return buf
    }

    private data class SingleResult(
        val status: Int,
        val body: ByteArray,
        val sessionId: Long,
        val treeId: Int,
        val handleId: Long,
        val nextSessionId: Long
    )

    private fun processCompoundFrame(
        frame: ByteArray,
        sessions: HashMap<Long, SmbSession>,
        startNextSessionId: Long
    ): Pair<ByteArray, Long> {
        var nextSessionId = startNextSessionId
        val slices = ArrayList<ByteArray>()
        var offset = 0
        while (offset + 64 <= frame.size) {
            val nextCmd = getLeInt(frame, offset + 20)
            if (nextCmd <= 0 || offset + nextCmd > frame.size) {
                slices.add(frame.copyOfRange(offset, frame.size))
                break
            }
            slices.add(frame.copyOfRange(offset, offset + nextCmd))
            offset += nextCmd
        }

        val responses = ArrayList<Pair<ByteArray, ByteArray?>>()
        var lastSessionId = 0L
        var lastTreeId = 0
        var lastHandleId = 0L

        for (req in slices) {
            val cmd = getLeUShort(req, 12)
            val creditsReq = getLeUShort(req, 14).coerceAtLeast(16)
            val flags = getLeInt(req, 16)
            val msgId = getLeLong(req, 24)
            var treeId = getLeInt(req, 36)
            var sessId = getLeLong(req, 40)

            val isRelated = (flags and SMB2_FLAGS_RELATED_OPERATIONS) != 0
            if (isRelated) {
                if (sessId == 0L || sessId == -1L) sessId = lastSessionId
                if (treeId == 0 || treeId == -1) treeId = lastTreeId
            }

            val res = handleSingleCommand(
                cmd = cmd,
                req = req,
                sessId = sessId,
                treeId = treeId,
                inheritedHandleId = if (isRelated) lastHandleId else 0L,
                sessions = sessions,
                currentNextSessionId = nextSessionId
            )
            nextSessionId = res.nextSessionId
            if (res.sessionId != 0L) lastSessionId = res.sessionId
            if (res.treeId != 0) lastTreeId = res.treeId
            if (res.handleId != 0L) lastHandleId = res.handleId

            val sess = sessions[res.sessionId]
            val shouldSign = sess != null &&
                sess.authenticated &&
                !sess.isGuest &&
                cmd != SMB2_NEGOTIATE &&
                (((flags and SMB2_FLAGS_SIGNED) != 0) || (cmd == SMB2_SESSION_SETUP && res.status == STATUS_SUCCESS))

            val pkt = buildSmb2Packet(
                command = cmd,
                status = res.status,
                messageId = msgId,
                sessionId = res.sessionId,
                treeId = res.treeId,
                body = res.body,
                credits = creditsReq,
                extraFlags = flags and SMB2_FLAGS_RELATED_OPERATIONS,
                signKey = null
            )
            responses.add(pkt to (if (shouldSign) sess?.sessionKey else null))
        }

        val totalOut = ByteArrayOutputStream()
        for ((idx, item) in responses.withIndex()) {
            var pkt = item.first
            val signKey = item.second
            if (idx < responses.lastIndex) {
                val pad = (8 - (pkt.size % 8)) % 8
                if (pad > 0) pkt = pkt + ByteArray(pad)
                putLeInt(pkt, 20, pkt.size)
            }
            if (signKey != null) {
                val oldFlags = getLeInt(pkt, 16)
                putLeInt(pkt, 16, oldFlags or SMB2_FLAGS_SIGNED)
                val sig = hmacSha256(signKey.copyOf(16), pkt)
                System.arraycopy(sig, 0, pkt, 48, 16)
            }
            totalOut.write(pkt)
        }
        return totalOut.toByteArray() to nextSessionId
    }

    private fun handleSingleCommand(
        cmd: Int,
        req: ByteArray,
        sessId: Long,
        treeId: Int,
        inheritedHandleId: Long,
        sessions: HashMap<Long, SmbSession>,
        currentNextSessionId: Long
    ): SingleResult {
        var nextSessionId = currentNextSessionId
        val body = req.copyOfRange(64, req.size)

        if (cmd == SMB2_NEGOTIATE) {
            val dialectCount = getLeUShort(body, 2)
            val dialects = HashSet<Int>()
            for (i in 0 until dialectCount) {
                if (36 + i * 2 + 2 <= body.size) {
                    dialects.add(getLeUShort(body, 36 + i * 2))
                }
            }
            val chosen = if (0x0210 in dialects) 0x0210 else 0x0202
            return SingleResult(STATUS_SUCCESS, buildNegotiateBody(chosen), 0L, 0, 0L, nextSessionId)
        }

        if (cmd == SMB2_SESSION_SETUP) {
            var activeId = sessId
            if (activeId == 0L || !sessions.containsKey(activeId)) {
                activeId = nextSessionId++
                val challenge = ByteArray(8).also { rng.nextBytes(it) }
                sessions[activeId] = SmbSession(activeId, challenge)
            }
            val sess = sessions[activeId]!!
            val secOff = getLeUShort(body, 12)
            val secLen = getLeUShort(body, 14)
            val secBlob = if (secLen > 0 && secOff + secLen <= req.size) {
                req.copyOfRange(secOff, secOff + secLen)
            } else {
                ByteArray(0)
            }
            val ntlmToken = extractNtlmsspToken(secBlob)
            if (ntlmToken != null && ntlmToken.size >= 12 && getLeInt(ntlmToken, 8) == 1) {
                val type2 = buildNtlmType2Challenge(sess.serverChallenge)
                val respBlob = spnegoNegTokenRespChallenge(type2)
                val respBody = leBuffer(8 + respBlob.size) {
                    putShort(9)
                    putShort(0)
                    putShort((64 + 8).toShort())
                    putShort(respBlob.size.toShort())
                    put(respBlob)
                }
                return SingleResult(STATUS_MORE_PROCESSING_REQUIRED, respBody, activeId, 0, 0L, nextSessionId)
            }

            val authResult = verifyNtlmType3(
                token = ntlmToken ?: ByteArray(0),
                serverChallenge = sess.serverChallenge,
                authRequired = authEnabledProvider(),
                expectedUser = authUserProvider(),
                expectedPassword = authPasswordProvider()
            )
            if (!authResult.accepted) {
                sessions.remove(activeId)
                return SingleResult(STATUS_LOGON_FAILURE, errorBody(), activeId, 0, 0L, nextSessionId)
            }
            sess.authenticated = true
            sess.isGuest = authResult.isGuest
            sess.sessionKey = authResult.sessionKey
            val acceptBlob = spnegoNegTokenRespAccept()
            val sessFlags = if (authResult.isGuest) 0x0001 else 0x0000
            val respBody = leBuffer(8 + acceptBlob.size) {
                putShort(9)
                putShort(sessFlags.toShort())
                putShort((64 + 8).toShort())
                putShort(acceptBlob.size.toShort())
                put(acceptBlob)
            }
            return SingleResult(STATUS_SUCCESS, respBody, activeId, 0, 0L, nextSessionId)
        }

        if (cmd == SMB2_ECHO) {
            return SingleResult(STATUS_SUCCESS, leBuffer(4) { putShort(4); putShort(0) }, sessId, treeId, 0L, nextSessionId)
        }

        val sess = sessions[sessId]
        if (sess == null || !sess.authenticated) {
            return SingleResult(STATUS_USER_SESSION_DELETED, errorBody(), sessId, treeId, 0L, nextSessionId)
        }

        if (cmd == SMB2_LOGOFF) {
            sessions.remove(sessId)
            return SingleResult(STATUS_SUCCESS, leBuffer(4) { putShort(4); putShort(0) }, sessId, 0, 0L, nextSessionId)
        }

        if (cmd == SMB2_TREE_CONNECT) {
            val pathOff = getLeUShort(body, 4)
            val pathLen = getLeUShort(body, 6)
            val unc = if (pathLen > 0 && pathOff + pathLen <= req.size) {
                String(req.copyOfRange(pathOff, pathOff + pathLen), Charsets.UTF_16LE)
            } else {
                ""
            }
            val shareName = unc.replace('/', '\\').trimEnd('\\').substringAfterLast('\\').lowercase()
            val customShare = shareNameProvider().trim().ifBlank { "PixelNAS" }.lowercase()
            val mapped = when (shareName) {
                "ipc$" -> "ipc" to 0x02
                "pixelnas", customShare -> "root" to 0x01
                "incoming" -> "incoming" to 0x01
                "output", "googlephotos_output" -> "output" to 0x01
                "quarantine" -> "quarantine" to 0x01
                else -> null
            } ?: return SingleResult(STATUS_BAD_NETWORK_NAME, errorBody(), sessId, 0, 0L, nextSessionId)

            val tid = sess.nextTreeId++
            sess.trees[tid] = mapped.first
            val respBody = leBuffer(16) {
                putShort(16)
                put(mapped.second.toByte())
                put(0)
                putInt(0)
                putInt(0)
                putInt(0x001F01FF)
            }
            return SingleResult(STATUS_SUCCESS, respBody, sessId, tid, 0L, nextSessionId)
        }

        val shareKind = sess.trees[treeId]
            ?: return SingleResult(STATUS_NETWORK_NAME_DELETED, errorBody(), sessId, treeId, 0L, nextSessionId)

        if (cmd == SMB2_TREE_DISCONNECT) {
            sess.trees.remove(treeId)
            return SingleResult(STATUS_SUCCESS, leBuffer(4) { putShort(4); putShort(0) }, sessId, treeId, 0L, nextSessionId)
        }

        if (cmd == SMB2_IOCTL) {
            val ctlCode = getLeInt(body, 4)
            val st = if (ctlCode == 0x00060194) STATUS_FS_DRIVER_REQUIRED else STATUS_NOT_SUPPORTED
            return SingleResult(st, errorBody(), sessId, treeId, 0L, nextSessionId)
        }

        if (cmd == SMB2_CREATE) {
            val createDisp = getLeInt(body, 36)
            val createOpts = getLeInt(body, 40)
            val nameOff = getLeUShort(body, 44)
            val nameLen = getLeUShort(body, 46)
            val relName = if (nameLen > 0 && nameOff + nameLen <= req.size) {
                String(req.copyOfRange(nameOff, nameOff + nameLen), Charsets.UTF_16LE)
            } else {
                ""
            }.replace('/', '\\').trim('\\')

            if (shareKind == "ipc") {
                val hid = sess.nextHandleId++
                sess.handles[hid] = OpenHandle(hid, "ipc", relName, null, false)
                return SingleResult(STATUS_SUCCESS, buildCreateResp(hid, FILE_OPENED, null, false), sessId, treeId, hid, nextSessionId)
            }

            val resolved = resolveSharePath(shareKind, relName)
                ?: return SingleResult(STATUS_OBJECT_NAME_NOT_FOUND, errorBody(), sessId, treeId, 0L, nextSessionId)
            val fsTarget = resolved.first
            val isVirtualRoot = resolved.second

            if (isVirtualRoot) {
                if (createDisp == FILE_CREATE) {
                    return SingleResult(STATUS_OBJECT_NAME_COLLISION, errorBody(), sessId, treeId, 0L, nextSessionId)
                }
                val hid = sess.nextHandleId++
                sess.handles[hid] = OpenHandle(hid, "root", "", null, true)
                return SingleResult(STATUS_SUCCESS, buildCreateResp(hid, FILE_OPENED, null, true), sessId, treeId, hid, nextSessionId)
            }

            val target = fsTarget!!
            val wantDir = (createOpts and FILE_DIRECTORY_FILE) != 0
            val wantNonDir = (createOpts and FILE_NON_DIRECTORY_FILE) != 0
            val deleteOnClose = (createOpts and FILE_DELETE_ON_CLOSE) != 0
            val exists = target.exists()

            if (exists) {
                val isDir = target.isDirectory
                if (wantDir && !isDir) {
                    return SingleResult(STATUS_NOT_A_DIRECTORY, errorBody(), sessId, treeId, 0L, nextSessionId)
                }
                if (wantNonDir && isDir) {
                    return SingleResult(STATUS_FILE_IS_A_DIRECTORY, errorBody(), sessId, treeId, 0L, nextSessionId)
                }
                if (createDisp == FILE_CREATE) {
                    return SingleResult(STATUS_OBJECT_NAME_COLLISION, errorBody(), sessId, treeId, 0L, nextSessionId)
                }
                val createAction = if (!isDir && createDisp in setOf(FILE_SUPERSEDE, FILE_OVERWRITE, FILE_OVERWRITE_IF)) {
                    target.writeBytes(ByteArray(0))
                    if (createDisp == FILE_SUPERSEDE) FILE_SUPERSEDED else FILE_OVERWRITTEN
                } else {
                    FILE_OPENED
                }
                val hid = sess.nextHandleId++
                sess.handles[hid] = OpenHandle(
                    handleId = hid,
                    shareKind = shareKind,
                    relPath = relName,
                    fsFile = target,
                    isDir = isDir,
                    deleteOnClose = deleteOnClose,
                    wasWritten = createAction != FILE_OPENED
                )
                return SingleResult(STATUS_SUCCESS, buildCreateResp(hid, createAction, target, isDir), sessId, treeId, hid, nextSessionId)
            } else {
                if (createDisp == FILE_OPEN || createDisp == FILE_OVERWRITE) {
                    val parent = target.parentFile
                    val code = if (parent != null && !parent.exists()) STATUS_OBJECT_PATH_NOT_FOUND else STATUS_OBJECT_NAME_NOT_FOUND
                    return SingleResult(code, errorBody(), sessId, treeId, 0L, nextSessionId)
                }
                val parent = target.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                if (wantDir) {
                    target.mkdirs()
                    val hid = sess.nextHandleId++
                    sess.handles[hid] = OpenHandle(hid, shareKind, relName, target, true, deleteOnClose = deleteOnClose)
                    return SingleResult(STATUS_SUCCESS, buildCreateResp(hid, FILE_CREATED, target, true), sessId, treeId, hid, nextSessionId)
                } else {
                    target.writeBytes(ByteArray(0))
                    val hid = sess.nextHandleId++
                    sess.handles[hid] = OpenHandle(
                        handleId = hid,
                        shareKind = shareKind,
                        relPath = relName,
                        fsFile = target,
                        isDir = false,
                        deleteOnClose = deleteOnClose,
                        wasWritten = true
                    )
                    return SingleResult(STATUS_SUCCESS, buildCreateResp(hid, FILE_CREATED, target, false), sessId, treeId, hid, nextSessionId)
                }
            }
        }

        val hidOffset = when (cmd) {
            SMB2_CLOSE, SMB2_FLUSH, SMB2_QUERY_DIRECTORY -> 8
            SMB2_READ, SMB2_WRITE -> 16
            SMB2_QUERY_INFO -> 24
            SMB2_SET_INFO -> 16
            else -> -1
        }
        if (hidOffset < 0) {
            return SingleResult(STATUS_NOT_SUPPORTED, errorBody(), sessId, treeId, 0L, nextSessionId)
        }

        var hid = getLeLong(body, hidOffset)
        if (hid == -1L || hid == 0L) hid = inheritedHandleId
        val h = sess.handles[hid]
            ?: return SingleResult(STATUS_FILE_CLOSED, errorBody(), sessId, treeId, 0L, nextSessionId)

        if (cmd == SMB2_CLOSE) {
            sess.handles.remove(hid)
            val attrs = if (h.isDir) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_ARCHIVE
            var eof = 0L
            var mtimeMs = System.currentTimeMillis()
            val file = h.fsFile
            if (file != null && file.exists()) {
                eof = if (h.isDir) 0L else file.length()
                mtimeMs = file.lastModified()
                if (h.deleteOnClose) {
                    val protectedRoots = setOf(
                        incomingDirProvider().canonicalPath,
                        outputDirProvider().canonicalPath,
                        quarantineDirProvider().canonicalPath
                    )
                    if (file.canonicalPath !in protectedRoots) {
                        file.delete()
                    }
                } else if (h.wasWritten && !h.isDir && isInsideIncoming(file)) {
                    try {
                        onIncomingFileClosed(file)
                    } catch (_: Exception) {
                    }
                }
            }
            val ft = millisToFiletime(mtimeMs)
            val alloc = ((eof + 4095L) / 4096L) * 4096L
            val resp = leBuffer(60) {
                putShort(60)
                putShort(0x0001)
                putInt(0)
                putLong(ft)
                putLong(ft)
                putLong(ft)
                putLong(ft)
                putLong(alloc)
                putLong(eof)
                putInt(attrs)
            }
            return SingleResult(STATUS_SUCCESS, resp, sessId, treeId, 0L, nextSessionId)
        }

        if (cmd == SMB2_FLUSH) {
            return SingleResult(STATUS_SUCCESS, leBuffer(4) { putShort(4); putShort(0) }, sessId, treeId, hid, nextSessionId)
        }

        if (cmd == SMB2_READ) {
            val readLen = getLeInt(body, 4)
            val readOff = getLeLong(body, 8)
            val file = h.fsFile
            if (h.isDir || file == null || !file.exists()) {
                return SingleResult(STATUS_INVALID_PARAMETER, errorBody(), sessId, treeId, hid, nextSessionId)
            }
            val data = RandomAccessFile(file, "r").use { raf ->
                if (readOff >= raf.length()) {
                    ByteArray(0)
                } else {
                    val maxToRead = minOf(readLen.toLong(), raf.length() - readOff).toInt()
                    val buf = ByteArray(maxToRead)
                    raf.seek(readOff)
                    val got = raf.read(buf)
                    if (got <= 0) ByteArray(0) else if (got == buf.size) buf else buf.copyOf(got)
                }
            }
            if (data.isEmpty() && file.length() > 0L && readOff >= file.length()) {
                return SingleResult(STATUS_END_OF_FILE, errorBody(), sessId, treeId, hid, nextSessionId)
            }
            val payload = if (data.isEmpty()) ByteArray(1) else data
            val fixed = leBuffer(16 + payload.size) {
                putShort(17)
                put((64 + 16).toByte())
                put(0)
                putInt(data.size)
                putInt(0)
                putInt(0)
                put(payload)
            }
            return SingleResult(STATUS_SUCCESS, fixed, sessId, treeId, hid, nextSessionId)
        }

        if (cmd == SMB2_WRITE) {
            val dataOff = getLeUShort(body, 2)
            val writeLen = getLeInt(body, 4)
            val writeOff = getLeLong(body, 8)
            val file = h.fsFile
            if (h.isDir || file == null) {
                return SingleResult(STATUS_ACCESS_DENIED, errorBody(), sessId, treeId, hid, nextSessionId)
            }
            val safeLen = minOf(writeLen, (req.size - dataOff).coerceAtLeast(0))
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(writeOff)
                if (safeLen > 0) raf.write(req, dataOff, safeLen)
            }
            h.wasWritten = true
            val resp = leBuffer(16) {
                putShort(17)
                putShort(0)
                putInt(safeLen)
                putInt(0)
                putShort(0)
                putShort(0)
            }
            return SingleResult(STATUS_SUCCESS, resp, sessId, treeId, hid, nextSessionId)
        }

        if (cmd == SMB2_QUERY_DIRECTORY) {
            val infoClass = body[2].toInt() and 0xFF
            val qFlags = body[3].toInt() and 0xFF
            val outBufLen = getLeInt(body, 24)
            val patOff = getLeUShort(body, 16)
            val patLen = getLeUShort(body, 18)
            val pattern = if (patLen > 0 && patOff + patLen <= req.size) {
                String(req.copyOfRange(patOff, patOff + patLen), Charsets.UTF_16LE)
            } else {
                "*"
            }

            if ((qFlags and 0x01) != 0 || (qFlags and 0x04) != 0) {
                h.dirScanDone = false
                h.dirScanIndex = 0
            }
            if (h.dirScanDone) {
                return SingleResult(STATUS_NO_MORE_FILES, errorBody(), sessId, treeId, hid, nextSessionId)
            }

            val allEntries = listDirectoryEntries(h)
            val matched = allEntries.filter { wildcardMatch(pattern, it.name) }
            if (h.dirScanIndex >= matched.size) {
                h.dirScanDone = true
                return SingleResult(STATUS_NO_MORE_FILES, errorBody(), sessId, treeId, hid, nextSessionId)
            }

            val singleEntry = (qFlags and 0x02) != 0
            val slice = matched.subList(h.dirScanIndex, matched.size)
            val encoded = encodeDirectoryEntries(infoClass, slice, outBufLen, singleEntry)
            h.dirScanIndex += encoded.second
            if (h.dirScanIndex >= matched.size) h.dirScanDone = true
            val resp = leBuffer(8 + encoded.first.size) {
                putShort(9)
                putShort((64 + 8).toShort())
                putInt(encoded.first.size)
                put(encoded.first)
            }
            return SingleResult(STATUS_SUCCESS, resp, sessId, treeId, hid, nextSessionId)
        }

        if (cmd == SMB2_QUERY_INFO) {
            val infoType = body[2].toInt() and 0xFF
            val infoClass = body[3].toInt() and 0xFF
            val outMax = getLeInt(body, 4)
            val data = buildQueryInfo(h, infoType, infoClass)
                ?: return SingleResult(STATUS_NOT_SUPPORTED, errorBody(), sessId, treeId, hid, nextSessionId)
            val trimmed = if (outMax > 0 && data.size > outMax) data.copyOf(outMax) else data
            val resp = leBuffer(8 + trimmed.size) {
                putShort(9)
                putShort((64 + 8).toShort())
                putInt(trimmed.size)
                put(trimmed)
            }
            return SingleResult(STATUS_SUCCESS, resp, sessId, treeId, hid, nextSessionId)
        }

        if (cmd == SMB2_SET_INFO) {
            val infoType = body[2].toInt() and 0xFF
            val infoClass = body[3].toInt() and 0xFF
            val bufLen = getLeInt(body, 4)
            val bufOff = getLeUShort(body, 8)
            val inBuf = if (bufLen > 0 && bufOff + bufLen <= req.size) {
                req.copyOfRange(bufOff, bufOff + bufLen)
            } else {
                ByteArray(0)
            }
            val st = applySetInfo(h, infoType, infoClass, inBuf)
            if (st != STATUS_SUCCESS) {
                return SingleResult(st, errorBody(), sessId, treeId, hid, nextSessionId)
            }
            return SingleResult(STATUS_SUCCESS, leBuffer(2) { putShort(2) }, sessId, treeId, hid, nextSessionId)
        }

        return SingleResult(STATUS_NOT_SUPPORTED, errorBody(), sessId, treeId, hid, nextSessionId)
    }

    private fun resolveSharePath(shareKind: String, relPath: String): Pair<File?, Boolean>? {
        val incDir = incomingDirProvider().also { it.mkdirs() }
        val outDir = outputDirProvider().also { it.mkdirs() }
        val quarDir = quarantineDirProvider().also { it.mkdirs() }
        val clean = relPath.replace('/', '\\').trim('\\')

        val base: File = when (shareKind) {
            "root" -> {
                if (clean.isEmpty()) return null to true
                val first = clean.substringBefore('\\')
                val rest = clean.substringAfter('\\', "")
                val rootBase = when (first.lowercase()) {
                    "incoming" -> incDir
                    "output", "googlephotos_output" -> outDir
                    "quarantine" -> quarDir
                    else -> return null
                }
                return safeResolve(rootBase, rest)?.let { it to false }
            }
            "incoming" -> incDir
            "output" -> outDir
            "quarantine" -> quarDir
            else -> return null
        }
        return safeResolve(base, clean)?.let { it to false }
    }

    private fun safeResolve(baseDir: File, subPath: String): File? {
        val baseCanonical = baseDir.canonicalFile
        if (subPath.isEmpty()) return baseCanonical
        val sub = subPath.replace('\\', File.separatorChar)
        val candidate = File(baseCanonical, sub).canonicalFile
        return if (candidate.path == baseCanonical.path ||
            candidate.path.startsWith(baseCanonical.path + File.separator)
        ) {
            candidate
        } else {
            null
        }
    }

    private fun isInsideIncoming(file: File): Boolean {
        val incCanonical = incomingDirProvider().canonicalFile
        val targetCanonical = file.canonicalFile
        return targetCanonical.path.startsWith(incCanonical.path + File.separator)
    }

    private data class DirEntry(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val mtimeMs: Long
    )

    private fun listDirectoryEntries(h: OpenHandle): List<DirEntry> {
        val now = System.currentTimeMillis()
        val list = ArrayList<DirEntry>()
        list.add(DirEntry(".", true, 0L, now))
        list.add(DirEntry("..", true, 0L, now))
        if (h.shareKind == "root" && h.fsFile == null) {
            val incDir = incomingDirProvider().also { it.mkdirs() }
            val outDir = outputDirProvider().also { it.mkdirs() }
            val quarDir = quarantineDirProvider().also { it.mkdirs() }
            list.add(DirEntry("Incoming", true, 0L, incDir.lastModified().takeIf { it > 0 } ?: now))
            list.add(DirEntry("Output", true, 0L, outDir.lastModified().takeIf { it > 0 } ?: now))
            list.add(DirEntry("Quarantine", true, 0L, quarDir.lastModified().takeIf { it > 0 } ?: now))
            return list
        }
        val dir = h.fsFile
        if (dir != null && dir.isDirectory) {
            dir.listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { child ->
                    val childIsDir = child.isDirectory
                    list.add(
                        DirEntry(
                            name = child.name,
                            isDir = childIsDir,
                            size = if (childIsDir) 0L else child.length(),
                            mtimeMs = child.lastModified().takeIf { it > 0 } ?: now
                        )
                    )
                }
        }
        return list
    }

    private fun encodeDirectoryEntries(
        infoClass: Int,
        entries: List<DirEntry>,
        maxBytes: Int,
        singleEntry: Boolean
    ): Pair<ByteArray, Int> {
        val records = ArrayList<ByteArray>()
        var total = 0
        for ((i, entry) in entries.withIndex()) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_16LE)
            val attrs = if (entry.isDir) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_ARCHIVE
            val ft = millisToFiletime(entry.mtimeMs)
            val alloc = ((entry.size + 4095L) / 4096L) * 4096L
            val fileId = (i + 1).toLong()

            val rec = when (infoClass) {
                0x25 -> leBuffer(104 + nameBytes.size) { // FileIdBothDirectoryInformation
                    putInt(0); putInt(0)
                    putLong(ft); putLong(ft); putLong(ft); putLong(ft)
                    putLong(entry.size); putLong(alloc)
                    putInt(attrs); putInt(nameBytes.size); putInt(0)
                    put(0); put(0)
                    put(ByteArray(24))
                    putShort(0)
                    putLong(fileId)
                    put(nameBytes)
                }
                0x03 -> leBuffer(94 + nameBytes.size) { // FileBothDirectoryInformation
                    putInt(0); putInt(0)
                    putLong(ft); putLong(ft); putLong(ft); putLong(ft)
                    putLong(entry.size); putLong(alloc)
                    putInt(attrs); putInt(nameBytes.size); putInt(0)
                    put(0); put(0)
                    put(ByteArray(24))
                    put(nameBytes)
                }
                0x26 -> leBuffer(80 + nameBytes.size) { // FileIdFullDirectoryInformation
                    putInt(0); putInt(0)
                    putLong(ft); putLong(ft); putLong(ft); putLong(ft)
                    putLong(entry.size); putLong(alloc)
                    putInt(attrs); putInt(nameBytes.size); putInt(0); putInt(0)
                    putLong(fileId)
                    put(nameBytes)
                }
                0x02 -> leBuffer(68 + nameBytes.size) { // FileFullDirectoryInformation
                    putInt(0); putInt(0)
                    putLong(ft); putLong(ft); putLong(ft); putLong(ft)
                    putLong(entry.size); putLong(alloc)
                    putInt(attrs); putInt(nameBytes.size); putInt(0)
                    put(nameBytes)
                }
                0x0C -> leBuffer(12 + nameBytes.size) { // FileNamesInformation
                    putInt(0); putInt(0); putInt(nameBytes.size)
                    put(nameBytes)
                }
                else -> leBuffer(64 + nameBytes.size) { // 0x01 FileDirectoryInformation
                    putInt(0); putInt(0)
                    putLong(ft); putLong(ft); putLong(ft); putLong(ft)
                    putLong(entry.size); putLong(alloc)
                    putInt(attrs); putInt(nameBytes.size)
                    put(nameBytes)
                }
            }
            val pad = (8 - (rec.size % 8)) % 8
            if (records.isNotEmpty() && total + rec.size + pad > maxBytes) break
            records.add(rec)
            total += rec.size + pad
            if (singleEntry) break
        }

        val out = ByteArrayOutputStream()
        for ((idx, rec) in records.withIndex()) {
            var item = rec
            if (idx < records.lastIndex) {
                val pad = (8 - (item.size % 8)) % 8
                if (pad > 0) item = item + ByteArray(pad)
                putLeInt(item, 0, item.size)
            }
            out.write(item)
        }
        return out.toByteArray() to records.size
    }

    private fun buildQueryInfo(h: OpenHandle, infoType: Int, infoClass: Int): ByteArray? {
        val file = h.fsFile
        val mtimeMs = if (file != null && file.exists()) file.lastModified() else System.currentTimeMillis()
        val eof = if (file != null && file.exists() && !h.isDir) file.length() else 0L
        val ft = millisToFiletime(mtimeMs)
        val alloc = ((eof + 4095L) / 4096L) * 4096L
        val attrs = if (h.isDir) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_ARCHIVE

        if (infoType == 0x01) {
            return when (infoClass) {
                4 -> leBuffer(40) { putLong(ft); putLong(ft); putLong(ft); putLong(ft); putInt(attrs); putInt(0) }
                5 -> leBuffer(24) {
                    putLong(alloc); putLong(eof); putInt(1)
                    put((if (h.deleteOnClose) 1 else 0).toByte())
                    put((if (h.isDir) 1 else 0).toByte())
                    putShort(0)
                }
                6 -> leBuffer(8) { putLong(h.handleId) }
                7 -> leBuffer(4) { putInt(0) }
                8 -> leBuffer(4) { putInt(0x001F01FF) }
                14 -> leBuffer(8) { putLong(0L) }
                18 -> {
                    val nameBytes = ("\\" + h.relPath.trim('\\')).toByteArray(Charsets.UTF_16LE)
                    leBuffer(100 + nameBytes.size) {
                        putLong(ft); putLong(ft); putLong(ft); putLong(ft); putInt(attrs); putInt(0)
                        putLong(alloc); putLong(eof); putInt(1)
                        put((if (h.deleteOnClose) 1 else 0).toByte())
                        put((if (h.isDir) 1 else 0).toByte())
                        putShort(0)
                        putLong(h.handleId)
                        putInt(0); putInt(0x001F01FF); putLong(0L); putInt(0)
                        putInt(nameBytes.size)
                        put(nameBytes)
                    }
                }
                21 -> {
                    val nameBytes = ("\\" + h.relPath.trim('\\')).toByteArray(Charsets.UTF_16LE)
                    leBuffer(4 + nameBytes.size) { putInt(nameBytes.size); put(nameBytes) }
                }
                22 -> {
                    val streamName = "::\$DATA".toByteArray(Charsets.UTF_16LE)
                    if (h.isDir) ByteArray(0) else leBuffer(24 + streamName.size) {
                        putInt(0); putInt(streamName.size); putLong(eof); putLong(alloc); put(streamName)
                    }
                }
                35 -> leBuffer(8) { putInt(attrs); putInt(0) }
                else -> null
            }
        }

        if (infoType == 0x02) {
            val outDir = outputDirProvider().also { it.mkdirs() }
            val (totalBytes, freeBytes) = queryVolumeBytes(outDir)
            val unitsTotal = (totalBytes / 4096L).coerceAtLeast(1L)
            val unitsFree = (freeBytes / 4096L).coerceAtLeast(0L)
            return when (infoClass) {
                3 -> leBuffer(24) { putLong(unitsTotal); putLong(unitsFree); putInt(8); putInt(512) }
                7 -> leBuffer(32) { putLong(unitsTotal); putLong(unitsFree); putLong(unitsFree); putInt(8); putInt(512) }
                1 -> {
                    val vol = "PixelPhotoNAS".toByteArray(Charsets.UTF_16LE)
                    leBuffer(18 + vol.size) {
                        putLong(ft); putInt(0x504E4153); putInt(vol.size); put(0); put(0); put(vol)
                    }
                }
                5 -> {
                    val fsName = "NTFS".toByteArray(Charsets.UTF_16LE)
                    leBuffer(12 + fsName.size) { putInt(0x00000003); putInt(255); putInt(fsName.size); put(fsName) }
                }
                4 -> leBuffer(8) { putInt(0x00000007); putInt(0) }
                else -> null
            }
        }
        return null
    }

    private fun queryVolumeBytes(dir: File): Pair<Long, Long> {
        // Pure-JVM File.totalSpace / usableSpace works on BOTH Android devices AND local JVM unit tests!
        val jvmTotal = dir.totalSpace
        val jvmFree = dir.usableSpace
        if (jvmTotal > 0L) return jvmTotal to jvmFree
        return try {
            val st = StatFs(dir.absolutePath)
            st.totalBytes to st.availableBytes
        } catch (_: Throwable) {
            (64L * 1024 * 1024 * 1024) to (32L * 1024 * 1024 * 1024)
        }
    }

    private fun applySetInfo(h: OpenHandle, infoType: Int, infoClass: Int, inBuf: ByteArray): Int {
        val file = h.fsFile ?: return STATUS_ACCESS_DENIED
        if (infoType != 0x01) return STATUS_ACCESS_DENIED

        if (infoClass == 4 && inBuf.size >= 36) { // FileBasicInformation (preserve original capture time!)
            val lastWriteFt = getLeLong(inBuf, 16)
            if (lastWriteFt > 0L && file.exists()) {
                file.setLastModified(filetimeToMillis(lastWriteFt))
            }
            return STATUS_SUCCESS
        }

        if (infoClass == 13 && inBuf.isNotEmpty()) { // FileDispositionInformation
            h.deleteOnClose = inBuf[0].toInt() != 0
            return STATUS_SUCCESS
        }

        if (infoClass == 20 && inBuf.size >= 8) { // FileEndOfFileInformation
            val eof = getLeLong(inBuf, 0)
            if (!h.isDir) {
                RandomAccessFile(file, "rw").use { it.setLength(eof) }
                h.wasWritten = true
            }
            return STATUS_SUCCESS
        }

        if (infoClass == 10 && inBuf.size >= 20) { // FileRenameInformation
            val replaceExisting = inBuf[0].toInt() != 0
            val fnLen = getLeInt(inBuf, 16)
            if (20 + fnLen > inBuf.size) return STATUS_INVALID_PARAMETER
            val newRel = String(inBuf.copyOfRange(20, 20 + fnLen), Charsets.UTF_16LE).trimStart('\\')
            val resolved = resolveSharePath(h.shareKind, newRel) ?: return STATUS_OBJECT_NAME_INVALID
            val dest = resolved.first ?: return STATUS_OBJECT_NAME_INVALID
            if (dest.exists() && !replaceExisting) return STATUS_OBJECT_NAME_COLLISION
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
                file.delete()
            }
            h.fsFile = dest
            h.relPath = newRel
            h.wasWritten = true
            return STATUS_SUCCESS
        }

        return STATUS_SUCCESS
    }

    private fun buildNegotiateBody(dialect: Int): ByteArray {
        val secBlob = spnegoNegTokenInitNtlm()
        val nowFt = millisToFiletime(System.currentTimeMillis())
        return leBuffer(64 + secBlob.size) {
            putShort(65)
            putShort(0x0001) // SMB2_NEGOTIATE_SIGNING_ENABLED
            putShort(dialect.toShort())
            putShort(0)
            put(serverGuid)
            putInt(0x00000007)
            putInt(1048576)
            putInt(1048576)
            putInt(1048576)
            putLong(nowFt)
            putLong(nowFt - 36_000_000_000L)
            putShort((64 + 64).toShort())
            putShort(secBlob.size.toShort())
            putInt(0)
            put(secBlob)
        }
    }

    private fun buildCreateResp(handleId: Long, action: Int, file: File?, isDir: Boolean): ByteArray {
        val mtimeMs = if (file != null && file.exists()) file.lastModified() else System.currentTimeMillis()
        val eof = if (file != null && file.exists() && !isDir) file.length() else 0L
        val alloc = ((eof + 4095L) / 4096L) * 4096L
        val ft = millisToFiletime(mtimeMs)
        val attrs = if (isDir) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_ARCHIVE
        return leBuffer(88) {
            putShort(89)
            put(0)
            put(0)
            putInt(action)
            putLong(ft); putLong(ft); putLong(ft); putLong(ft)
            putLong(alloc)
            putLong(eof)
            putInt(attrs)
            putInt(0)
            putLong(handleId)
            putLong(handleId)
            putInt(0)
            putInt(0)
        }
    }

    private fun buildSmb2Packet(
        command: Int,
        status: Int,
        messageId: Long,
        sessionId: Long,
        treeId: Int,
        body: ByteArray,
        credits: Int = 32,
        extraFlags: Int = 0,
        signKey: ByteArray? = null
    ): ByteArray {
        var flags = SMB2_FLAGS_SERVER_TO_REDIR or extraFlags
        if (signKey != null) flags = flags or SMB2_FLAGS_SIGNED
        val pkt = leBuffer(64 + body.size) {
            put(byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
            putShort(64)
            putShort(1)
            putInt(status)
            putShort(command.toShort())
            putShort(credits.coerceAtLeast(1).toShort())
            putInt(flags)
            putInt(0)
            putLong(messageId)
            putInt(0)
            putInt(treeId)
            putLong(sessionId)
            put(ByteArray(16))
            put(body)
        }
        if (signKey != null) {
            val sig = hmacSha256(signKey.copyOf(16), pkt)
            System.arraycopy(sig, 0, pkt, 48, 16)
        }
        return pkt
    }

    private fun errorBody(): ByteArray = leBuffer(9) {
        putShort(9); put(0); put(0); putInt(0); put(0)
    }

    companion object {
        private const val EPOCH_DIFF_100NS = 116_444_736_000_000_000L

        private const val SMB2_NEGOTIATE = 0x0000
        private const val SMB2_SESSION_SETUP = 0x0001
        private const val SMB2_LOGOFF = 0x0002
        private const val SMB2_TREE_CONNECT = 0x0003
        private const val SMB2_TREE_DISCONNECT = 0x0004
        private const val SMB2_CREATE = 0x0005
        private const val SMB2_CLOSE = 0x0006
        private const val SMB2_FLUSH = 0x0007
        private const val SMB2_READ = 0x0008
        private const val SMB2_WRITE = 0x0009
        private const val SMB2_IOCTL = 0x000B
        private const val SMB2_ECHO = 0x000D
        private const val SMB2_QUERY_DIRECTORY = 0x000E
        private const val SMB2_QUERY_INFO = 0x0010
        private const val SMB2_SET_INFO = 0x0011

        private const val SMB2_FLAGS_SERVER_TO_REDIR = 0x00000001
        private const val SMB2_FLAGS_RELATED_OPERATIONS = 0x00000004
        private const val SMB2_FLAGS_SIGNED = 0x00000008

        private const val STATUS_SUCCESS = 0x00000000
        private const val STATUS_MORE_PROCESSING_REQUIRED = 0xC0000016.toInt()
        private const val STATUS_NO_MORE_FILES = 0x80000006.toInt()
        private const val STATUS_INVALID_PARAMETER = 0xC000000D.toInt()
        private const val STATUS_END_OF_FILE = 0xC0000011.toInt()
        private const val STATUS_ACCESS_DENIED = 0xC0000022.toInt()
        private const val STATUS_OBJECT_NAME_INVALID = 0xC0000033.toInt()
        private const val STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034.toInt()
        private const val STATUS_OBJECT_NAME_COLLISION = 0xC0000035.toInt()
        private const val STATUS_OBJECT_PATH_NOT_FOUND = 0xC000003A.toInt()
        private const val STATUS_LOGON_FAILURE = 0xC000006D.toInt()
        private const val STATUS_FILE_IS_A_DIRECTORY = 0xC00000BA.toInt()
        private const val STATUS_NOT_SUPPORTED = 0xC00000BB.toInt()
        private const val STATUS_BAD_NETWORK_NAME = 0xC00000CC.toInt()
        private const val STATUS_NETWORK_NAME_DELETED = 0xC00000C9.toInt()
        private const val STATUS_FILE_CLOSED = 0xC0000128.toInt()
        private const val STATUS_NOT_A_DIRECTORY = 0xC0000103.toInt()
        private const val STATUS_USER_SESSION_DELETED = 0xC0000203.toInt()
        private const val STATUS_FS_DRIVER_REQUIRED = 0xC000019C.toInt()

        private const val FILE_SUPERSEDE = 0
        private const val FILE_OPEN = 1
        private const val FILE_CREATE = 2
        private const val FILE_OVERWRITE = 4
        private const val FILE_OVERWRITE_IF = 5

        private const val FILE_SUPERSEDED = 0
        private const val FILE_OPENED = 1
        private const val FILE_CREATED = 2
        private const val FILE_OVERWRITTEN = 3

        private const val FILE_DIRECTORY_FILE = 0x00000001
        private const val FILE_NON_DIRECTORY_FILE = 0x00000040
        private const val FILE_DELETE_ON_CLOSE = 0x00001000

        private const val FILE_ATTRIBUTE_DIRECTORY = 0x00000010
        private const val FILE_ATTRIBUTE_ARCHIVE = 0x00000020

        private val SPNEGO_OID = byteArrayOf(0x06, 0x06, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x02)
        private val NTLMSSP_OID = byteArrayOf(0x06, 0x0A, 0x2B, 0x06, 0x01, 0x04, 0x01, 0x82.toByte(), 0x37, 0x02, 0x02, 0x0A)
        private val NTLMSSP_SIG = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)

        fun millisToFiletime(ms: Long): Long = ms * 10_000L + EPOCH_DIFF_100NS
        fun filetimeToMillis(ft: Long): Long = ((ft - EPOCH_DIFF_100NS) / 10_000L).coerceAtLeast(0L)

        private inline fun leBuffer(size: Int, block: ByteBuffer.() -> Unit): ByteArray {
            val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            buf.block()
            return buf.array()
        }

        private fun getLeUShort(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8))

        private fun getLeInt(b: ByteArray, off: Int): Int =
            ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

        private fun getLeLong(b: ByteArray, off: Int): Long =
            ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).long

        private fun putLeInt(b: ByteArray, off: Int, value: Int) {
            ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        }

        private fun derLen(n: Int): ByteArray {
            if (n < 0x80) return byteArrayOf(n.toByte())
            val bytes = ArrayList<Byte>()
            var rem = n
            while (rem > 0) {
                bytes.add(0, (rem and 0xFF).toByte())
                rem = rem ushr 8
            }
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
        }

        private fun derTlv(tag: Int, body: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + derLen(body.size) + body

        private fun spnegoNegTokenInitNtlm(): ByteArray {
            val mechTypes = derTlv(0xA0, derTlv(0x30, NTLMSSP_OID))
            val negInit = derTlv(0xA0, derTlv(0x30, mechTypes))
            return derTlv(0x60, SPNEGO_OID + negInit)
        }

        private fun spnegoNegTokenRespChallenge(ntlmType2: ByteArray): ByteArray {
            val state = derTlv(0xA0, derTlv(0x0A, byteArrayOf(0x01)))
            val mech = derTlv(0xA1, NTLMSSP_OID)
            val resp = derTlv(0xA2, derTlv(0x04, ntlmType2))
            return derTlv(0xA1, derTlv(0x30, state + mech + resp))
        }

        private fun spnegoNegTokenRespAccept(): ByteArray {
            val state = derTlv(0xA0, derTlv(0x0A, byteArrayOf(0x00)))
            return derTlv(0xA1, derTlv(0x30, state))
        }

        private fun extractNtlmsspToken(secBlob: ByteArray): ByteArray? {
            for (i in 0..(secBlob.size - NTLMSSP_SIG.size)) {
                var ok = true
                for (j in NTLMSSP_SIG.indices) {
                    if (secBlob[i + j] != NTLMSSP_SIG[j]) {
                        ok = false
                        break
                    }
                }
                if (ok) return secBlob.copyOfRange(i, secBlob.size)
            }
            return null
        }

        private fun buildNtlmType2Challenge(serverChallenge: ByteArray, serverName: String = "PIXELNAS"): ByteArray {
            val nbBytes = serverName.toByteArray(Charsets.UTF_16LE)
            val avOut = ByteArrayOutputStream()
            fun writeAv(id: Int, data: ByteArray) {
                avOut.write(leBuffer(4) { putShort(id.toShort()); putShort(data.size.toShort()) })
                avOut.write(data)
            }
            writeAv(0x0002, nbBytes)
            writeAv(0x0001, nbBytes)
            writeAv(0x0004, nbBytes)
            writeAv(0x0003, nbBytes)
            writeAv(0x0007, leBuffer(8) { putLong(millisToFiletime(System.currentTimeMillis())) })
            writeAv(0x0000, ByteArray(0))
            val avPairs = avOut.toByteArray()

            val flags = 0x00000001 or
                0x00000200 or
                0x00008000 or
                0x00010000 or
                0x00080000 or
                0x00800000 or
                0x02000000 or
                0x20000000 or
                0x40000000 or
                0x80000000.toInt()

            val headerLen = 56
            val targetNameOff = headerLen
            val targetInfoOff = targetNameOff + nbBytes.size

            return leBuffer(headerLen + nbBytes.size + avPairs.size) {
                put(NTLMSSP_SIG)
                putInt(2)
                putShort(nbBytes.size.toShort()); putShort(nbBytes.size.toShort()); putInt(targetNameOff)
                putInt(flags)
                put(serverChallenge)
                put(ByteArray(8))
                putShort(avPairs.size.toShort()); putShort(avPairs.size.toShort()); putInt(targetInfoOff)
                put(byteArrayOf(10, 0, 0x61, 0x4A, 0, 0, 0, 15))
                put(nbBytes)
                put(avPairs)
            }
        }

        private data class AuthResult(
            val accepted: Boolean,
            val isGuest: Boolean,
            val sessionKey: ByteArray
        )

        private fun verifyNtlmType3(
            token: ByteArray,
            serverChallenge: ByteArray,
            authRequired: Boolean,
            expectedUser: String,
            expectedPassword: String
        ): AuthResult {
            if (token.size < 64) {
                return AuthResult(!authRequired, true, ByteArray(16))
            }
            if (getLeInt(token, 8) != 3) {
                return AuthResult(false, true, ByteArray(16))
            }
            fun secBuf(pos: Int): ByteArray {
                val ln = getLeUShort(token, pos)
                val off = getLeInt(token, pos + 4)
                return if (ln <= 0 || off < 0 || off + ln > token.size) ByteArray(0) else token.copyOfRange(off, off + ln)
            }
            val ntResp = secBuf(20)
            val domainB = secBuf(28)
            val userB = secBuf(36)
            val encRandSessionKey = secBuf(52)
            val flags = getLeInt(token, 60)

            val username = String(userB, Charsets.UTF_16LE)
            val domain = String(domainB, Charsets.UTF_16LE)

            if (username.isEmpty() && ntResp.isEmpty()) {
                return AuthResult(!authRequired, true, ByteArray(16))
            }

            fun tryPassword(pw: String): ByteArray? {
                if (ntResp.size < 16) return null
                val proof = ntResp.copyOfRange(0, 16)
                val clientBlob = ntResp.copyOfRange(16, ntResp.size)
                val ntHash = md4(pw.toByteArray(Charsets.UTF_16LE))
                for (domCandidate in listOf(domain, "", domain.uppercase())) {
                    val identity = (username.uppercase() + domCandidate).toByteArray(Charsets.UTF_16LE)
                    val respKeyNt = hmacMd5(ntHash, identity)
                    val expectedProof = hmacMd5(respKeyNt, serverChallenge + clientBlob)
                    if (proof.contentEquals(expectedProof)) {
                        val keyExchKey = hmacMd5(respKeyNt, proof)
                        return if ((flags and 0x40000000) != 0 && encRandSessionKey.size == 16) {
                            rc4(keyExchKey, encRandSessionKey)
                        } else {
                            keyExchKey
                        }
                    }
                }
                return null
            }

            if (authRequired) {
                if (!username.equals(expectedUser, ignoreCase = true)) {
                    return AuthResult(false, false, ByteArray(16))
                }
                val sk = tryPassword(expectedPassword) ?: return AuthResult(false, false, ByteArray(16))
                return AuthResult(true, false, sk)
            }

            for (cand in listOf(expectedPassword, "pixel", "")) {
                val sk = tryPassword(cand)
                if (sk != null) return AuthResult(true, false, sk)
            }
            return AuthResult(true, true, ByteArray(16))
        }

        private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacMD5"))
            return mac.doFinal(data)
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            }
            var i = 0
            j = 0
            val out = ByteArray(data.size)
            for (k in data.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val tmp = s[i]; s[i] = s[j]; s[j] = tmp
                val ks = s[(s[i] + s[j]) and 0xFF]
                out[k] = ((data[k].toInt() and 0xFF) xor ks).toByte()
            }
            return out
        }

        /** Pure-Kotlin RFC 1320 MD4 digest (zero external provider required on Android API 26–34). */
        fun md4(message: ByteArray): ByteArray {
            val msgLen = message.size
            val bitLen = msgLen.toLong() * 8L
            val padLen = ((56 - (msgLen + 1) % 64) + 64) % 64
            val padded = ByteArray(msgLen + 1 + padLen + 8)
            System.arraycopy(message, 0, padded, 0, msgLen)
            padded[msgLen] = 0x80.toByte()
            ByteBuffer.wrap(padded, msgLen + 1 + padLen, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(bitLen)

            var a = 0x67452301
            var b = -0x10325477
            var c = -0x67452302
            var d = 0x10325476
            val x = IntArray(16)

            fun rotl(v: Int, s: Int): Int = (v shl s) or (v ushr (32 - s))

            var off = 0
            while (off < padded.size) {
                val bb = ByteBuffer.wrap(padded, off, 64).order(ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until 16) x[k] = bb.int
                val aa = a; val bPrev = b; val cc = c; val dd = d

                val s1 = intArrayOf(3, 7, 11, 19)
                for (i in 0 until 16) {
                    val f = (b and c) or (b.inv() and d)
                    val t = rotl(a + f + x[i], s1[i % 4])
                    a = d; d = c; c = b; b = t
                }
                val idx2 = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
                val s2 = intArrayOf(3, 5, 9, 13)
                for (i in 0 until 16) {
                    val f = (b and c) or (b and d) or (c and d)
                    val t = rotl(a + f + x[idx2[i]] + 0x5A827999, s2[i % 4])
                    a = d; d = c; c = b; b = t
                }
                val idx3 = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
                val s3 = intArrayOf(3, 9, 11, 15)
                for (i in 0 until 16) {
                    val f = b xor c xor d
                    val t = rotl(a + f + x[idx3[i]] + 0x6ED9EBA1, s3[i % 4])
                    a = d; d = c; c = b; b = t
                }
                a += aa; b += bPrev; c += cc; d += dd
                off += 64
            }
            return leBuffer(16) { putInt(a); putInt(b); putInt(c); putInt(d) }
        }

        private fun wildcardMatch(pattern: String, name: String): Boolean {
            if (pattern.isEmpty() || pattern == "*" || pattern == "*.*") return true
            val regex = buildString {
                append("^")
                for (ch in pattern.lowercase()) {
                    when (ch) {
                        '*' -> append(".*")
                        '?' -> append(".")
                        '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> append("\\").append(ch)
                        else -> append(ch)
                    }
                }
                append("$")
            }
            return Regex(regex).matches(name.lowercase())
        }
    }
}
