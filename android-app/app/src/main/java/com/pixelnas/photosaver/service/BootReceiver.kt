package com.pixelnas.photosaver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pixelnas.photosaver.data.NasPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = NasPreferences(context)
            if (prefs.current.startOnBoot && prefs.current.serverEnabled) {
                PixelNasService.start(context)
            }
        }
    }
}
