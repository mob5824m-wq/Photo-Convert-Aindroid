package com.pixelnas.photosaver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pixelnas.photosaver.MainActivity
import com.pixelnas.photosaver.PixelNasApp
import com.pixelnas.photosaver.R
import com.pixelnas.photosaver.util.PixelDeviceDetector

class PixelNasService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var smbRegistrationListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val app = application as PixelNasApp
        app.onServiceNotificationRefresh = { updateNotification() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PixelNasApp
        if (intent?.action == ACTION_STOP) {
            app.preferences.update { it.copy(serverEnabled = false) }
            stopAllDaemons()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireLocks()
        app.watcher.start()
        app.smbServer.startOrReload()
        app.ftpServer.startOrReload()
        app.httpWebDavServer.start()
        registerMdnsService(app.preferences.current.httpPort, app.smbServer.activePort)
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as PixelNasApp
        app.onServiceNotificationRefresh = null
        stopAllDaemons()
        super.onDestroy()
    }

    private fun stopAllDaemons() {
        val app = application as PixelNasApp
        unregisterMdnsService()
        app.httpWebDavServer.stop()
        app.ftpServer.stop()
        app.smbServer.stop()
        app.watcher.stop()
        releaseLocks()
    }

    private fun registerMdnsService(httpPort: Int, smbPort: Int) {
        try {
            val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
            if (registrationListener == null) {
                val info = NsdServiceInfo().apply {
                    serviceName = "PixelPhotoNAS"
                    serviceType = "_http._tcp."
                    setPort(httpPort)
                }
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                }
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                registrationListener = listener
            }
            if (smbRegistrationListener == null && smbPort > 0) {
                val smbInfo = NsdServiceInfo().apply {
                    serviceName = "PixelPhotoNAS"
                    serviceType = "_smb._tcp."
                    setPort(smbPort)
                }
                val smbListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                }
                nsd.registerService(smbInfo, NsdManager.PROTOCOL_DNS_SD, smbListener)
                smbRegistrationListener = smbListener
            }
        } catch (_: Exception) {
        }
    }

    private fun unregisterMdnsService() {
        try {
            val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
            registrationListener?.let { nsd.unregisterService(it) }
            smbRegistrationListener?.let { nsd.unregisterService(it) }
        } catch (_: Exception) {
        }
        registrationListener = null
        smbRegistrationListener = null
    }

    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "PixelPhotoNAS::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelPhotoNAS::CpuWake").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        wakeLock = null
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val app = application as PixelNasApp
        val cfg = app.preferences.current
        val summary = app.historyStore.summary()
        val ip = PixelDeviceDetector.getLanIpv4Addresses().firstOrNull() ?: "127.0.0.1"
        val mode = if (cfg.storageSaverEnabled) "Storage Saver (≤16MP)" else "Original Pass-Through"

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PixelNasService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val savedMb = summary.totalSavedBytes / (1024 * 1024)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pixel Photo NAS · http://$ip:${cfg.httpPort}")
            .setContentText("$mode · ${summary.totalProcessed} converted · ${savedMb} MB freed")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop NAS", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pixel Photo NAS Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps LAN photo upload server and Google Photos Storage Saver converter running"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "pixel_nas_daemon"
        const val NOTIFICATION_ID = 1042
        const val ACTION_STOP = "com.pixelnas.photosaver.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PixelNasService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PixelNasService::class.java))
        }
    }
}
