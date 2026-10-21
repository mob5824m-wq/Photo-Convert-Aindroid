package com.pixelnas.household.sync

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.pixelnas.household.HouseholdApp
import com.pixelnas.household.model.TransferBatchState
import com.pixelnas.household.model.UploadLedgerEntry
import com.pixelnas.household.widget.HouseholdUploadWidgetProvider
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object SmbUploadHelper {

    data class UriMetadata(
        val displayName: String,
        val sizeBytes: Long,
        val capturedAtMillis: Long?
    )

    fun inspectUri(context: Context, uri: Uri): UriMetadata {
        var name = "IMG_${System.currentTimeMillis()}.jpg"
        var size = 0L
        var dateTakenMs: Long? = null

        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_MODIFIED
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        name = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                    val takenIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    if (takenIdx >= 0 && !cursor.isNull(takenIdx)) {
                        val v = cursor.getLong(takenIdx)
                        if (v > 0L) dateTakenMs = v
                    }
                    if (dateTakenMs == null) {
                        val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                        if (modIdx >= 0 && !cursor.isNull(modIdx)) {
                            val sec = cursor.getLong(modIdx)
                            if (sec > 0L) dateTakenMs = sec * 1000L
                        }
                    }
                }
            }
        }

        if (dateTakenMs == null && name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".heic") }) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (!dt.isNullOrBlank()) {
                        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                            timeZone = TimeZone.getDefault()
                        }
                        dateTakenMs = fmt.parse(dt)?.time
                    }
                }
            }
        }

        return UriMetadata(displayName = name, sizeBytes = size, capturedAtMillis = dateTakenMs)
    }

    fun uploadUriBatch(
        context: Context,
        uris: List<Uri>,
        overrideProfileFolder: String? = null,
        onProgressNotification: ((completed: Int, total: Int, currentName: String) -> Unit)? = null
    ): Pair<Int, Int> {
        val app = context.applicationContext as HouseholdApp
        val cfg = app.preferences.current
        val targetFolder = overrideProfileFolder?.takeIf { it.isNotBlank() } ?: cfg.targetProfileFolder

        var succeeded = 0
        var failed = 0

        if (cfg.host.isBlank()) {
            app.transferState.value = TransferBatchState(
                active = false,
                statusMessage = "Set your NAS Samba IP address first in Samba Setup"
            )
            return 0 to uris.size
        }

        for ((index, uri) in uris.withIndex()) {
            val meta = inspectUri(context, uri)
            onProgressNotification?.invoke(index, uris.size, meta.displayName)
            app.transferState.value = TransferBatchState(
                active = true,
                currentFilename = meta.displayName,
                completedCount = index,
                totalCount = uris.size,
                bytesSentCurrentFile = 0L,
                totalBytesCurrentFile = meta.sizeBytes,
                statusMessage = "Uploading ${index + 1}/${uris.size} over Samba (${meta.displayName})..."
            )

            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: error("Unable to open local media stream")
                val result = stream.use { input ->
                    app.smbClient.uploadStreamAtomically(
                        cfg = cfg,
                        filename = meta.displayName,
                        inputStream = input,
                        capturedAtMillis = meta.capturedAtMillis,
                        overrideProfileFolder = targetFolder,
                        onBytesSent = { sent ->
                            app.transferState.value = app.transferState.value.copy(
                                bytesSentCurrentFile = sent
                            )
                        }
                    )
                }
                val fingerprint = "${meta.displayName}:${result.second}"
                app.preferences.markSyncedFingerprint(fingerprint)
                app.preferences.recordUpload(
                    UploadLedgerEntry(
                        filename = meta.displayName,
                        remoteUncPath = result.first,
                        sizeBytes = result.second,
                        profileFolder = targetFolder,
                        uploadedAtMillis = System.currentTimeMillis(),
                        success = true,
                        message = "Uploaded via Samba SMB2/3"
                    )
                )
                succeeded++
            } catch (e: Throwable) {
                failed++
                app.preferences.recordUpload(
                    UploadLedgerEntry(
                        filename = meta.displayName,
                        remoteUncPath = cfg.displayUnc,
                        sizeBytes = meta.sizeBytes,
                        profileFolder = targetFolder,
                        uploadedAtMillis = System.currentTimeMillis(),
                        success = false,
                        message = e.message ?: "Samba transfer error"
                    )
                )
            }
        }

        val summary = if (failed == 0) {
            "Uploaded $succeeded/${uris.size} file(s) via Samba → $targetFolder"
        } else {
            "Uploaded $succeeded/${uris.size} file(s) ($failed failed — check NAS IP/auth)"
        }
        app.transferState.value = TransferBatchState(
            active = false,
            completedCount = succeeded,
            totalCount = uris.size,
            statusMessage = summary
        )
        HouseholdUploadWidgetProvider.refreshAllWidgets(context)
        return succeeded to failed
    }
}
