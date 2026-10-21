package com.pixelnas.photosaver.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pixelnas.photosaver.PixelNasApp
import com.pixelnas.photosaver.R

class PixelNasUploadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidgetInstance(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        const val EXTRA_PROFILE = "com.pixelnas.photosaver.EXTRA_PROFILE"
        const val EXTRA_CONFIGURE_ONLY = "com.pixelnas.photosaver.EXTRA_CONFIGURE_ONLY"

        fun refreshAllWidgets(context: Context) {
            val appCtx = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appCtx)
            val ids = mgr.getAppWidgetIds(
                ComponentName(appCtx, PixelNasUploadWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidgetInstance(appCtx, mgr, id)
            }
        }

        fun requestPinWidget(context: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(context)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(context, PixelNasUploadWidgetProvider::class.java)
            return mgr.requestPinAppWidget(provider, null, null)
        }

        private fun buildActionPendingIntent(
            context: Context,
            requestCode: Int,
            profile: String?,
            configureOnly: Boolean
        ): PendingIntent {
            val intent = Intent(context, WidgetUploadActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (profile != null) {
                    putExtra(EXTRA_PROFILE, profile)
                }
                putExtra(EXTRA_CONFIGURE_ONLY, configureOnly)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun updateWidgetInstance(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val app = context.applicationContext as? PixelNasApp
            val cfg = app?.preferences?.current
            val remoteUrl = cfg?.remoteNasUrl.orEmpty()
            val statusLine = cfg?.widgetStatusLine.orEmpty()

            val targetDesc = if (remoteUrl.isNotBlank()) {
                "Wi-Fi NAS: ${remoteUrl.removePrefix("http://").removePrefix("https://")}"
            } else {
                "Target: Local Device Folder"
            }
            val subText = if (statusLine.isNotBlank()) {
                "$statusLine · $targetDesc"
            } else {
                "$targetDesc · Tap profile to pick media"
            }

            val views = RemoteViews(context.packageName, R.layout.widget_pixel_nas).apply {
                setTextViewText(R.id.widget_title, "Pixel Photo NAS Upload")
                setTextViewText(R.id.widget_subtitle, subText)
                setOnClickPendingIntent(
                    R.id.widget_btn_saver,
                    buildActionPendingIntent(context, 4101, "StorageSaver", false)
                )
                setOnClickPendingIntent(
                    R.id.widget_btn_original,
                    buildActionPendingIntent(context, 4102, "Original", false)
                )
                setOnClickPendingIntent(
                    R.id.widget_btn_family,
                    buildActionPendingIntent(context, 4103, "Family", false)
                )
                val cfgPi = buildActionPendingIntent(context, 4104, null, true)
                setOnClickPendingIntent(R.id.widget_config_btn, cfgPi)
                setOnClickPendingIntent(R.id.widget_header_row, cfgPi)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
