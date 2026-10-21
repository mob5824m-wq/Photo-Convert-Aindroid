package com.pixelnas.household.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pixelnas.household.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SmbUploadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawUris = intent?.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val profileOverride = intent?.getStringExtra(EXTRA_PROFILE_FOLDER)
        val uris = rawUris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (uris.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val initial = buildProgressNotification(0, uris.size, "Connecting to Samba share...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        scope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val (ok, fail) = SmbUploadHelper.uploadUriBatch(
                context = applicationContext,
                uris = uris,
                overrideProfileFolder = profileOverride,
                onProgressNotification = { done, total, name ->
                    nm.notify(NOTIFICATION_ID, buildProgressNotification(done, total, name))
                }
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            val doneNote = NotificationCompat.Builder(this@SmbUploadService, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    if (fail == 0) "Samba NAS Upload Complete ($ok sent)"
                    else "Samba NAS Upload Finished ($ok sent, $fail failed)"
                )
                .setContentText("Delivered directly to your home NAS Samba share")
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID + 1, doneNote)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildProgressNotification(done: Int, total: Int, filename: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sending to NAS over Samba (${done + 1}/$total)")
            .setContentText(filename)
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Household Samba Transfers",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "household_samba_uploads"
        private const val NOTIFICATION_ID = 4401
        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_PROFILE_FOLDER = "extra_profile_folder"

        fun enqueueBatch(context: Context, uris: List<Uri>, profileFolder: String? = null) {
            if (uris.isEmpty()) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, SmbUploadService::class.java).apply {
                putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
                if (!profileFolder.isNullOrBlank()) {
                    putExtra(EXTRA_PROFILE_FOLDER, profileFolder)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
        }
    }
}
