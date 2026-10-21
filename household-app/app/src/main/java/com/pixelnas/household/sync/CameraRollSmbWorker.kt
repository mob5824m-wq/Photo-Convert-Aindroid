package com.pixelnas.household.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pixelnas.household.HouseholdApp

/**
 * Periodically scans new Camera Roll media on unmetered Wi-Fi and backs up unsynced photos & videos
 * directly to the household NAS Samba share (`\\<host>\PixelNAS\Incoming\<Profile>`).
 */
class CameraRollSmbWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as HouseholdApp
        val cfg = app.preferences.current
        if (!cfg.autoCameraSyncWifiOnly || cfg.host.isBlank()) {
            return Result.success()
        }

        val candidates = ArrayList<Uri>()
        val collections = buildList {
            add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            if (cfg.syncVideosToo) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }

        for (collection in collections) {
            runCatching {
                applicationContext.contentResolver.query(
                    collection,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
                    ),
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    var scanned = 0
                    while (cursor.moveToNext() && scanned < 200) {
                        scanned++
                        val bucket = if (bucketCol >= 0 && !cursor.isNull(bucketCol)) cursor.getString(bucketCol) else ""
                        if (bucket.isNotBlank() && !bucket.contains("Camera", ignoreCase = true)) {
                            continue
                        }
                        val name = cursor.getString(nameCol) ?: continue
                        val size = cursor.getLong(sizeCol)
                        if (!app.preferences.hasSyncedFingerprint("$name:$size")) {
                            val id = cursor.getLong(idCol)
                            candidates.add(ContentUris.withAppendedId(collection, id))
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) return Result.success()
        val (_, failed) = SmbUploadHelper.uploadUriBatch(applicationContext, candidates)
        return if (failed > 0) Result.retry() else Result.success()
    }
}
