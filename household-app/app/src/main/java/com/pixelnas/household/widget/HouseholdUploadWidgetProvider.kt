package com.pixelnas.household.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.pixelnas.household.MainActivity
import com.pixelnas.household.R
import com.pixelnas.household.data.HouseholdPreferences

class HouseholdUploadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, widgetId))
        }
    }

    companion object {
        private fun buildRemoteViews(context: Context, widgetId: Int): RemoteViews {
            val prefs = HouseholdPreferences(context)
            val cfg = prefs.current
            val subtitle = if (cfg.host.isBlank()) {
                "Tap Open to pair your NAS Samba share"
            } else {
                cfg.displayUnc
            }

            val views = RemoteViews(context.packageName, R.layout.widget_household_samba)
            views.setTextViewText(R.id.widget_subtitle, subtitle)

            val openIntent = PendingIntent.getActivity(
                context,
                widgetId * 10 + 1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_open_app, openIntent)
            views.setOnClickPendingIntent(R.id.widget_title, openIntent)

            views.setOnClickPendingIntent(
                R.id.btn_upload_saver,
                pickerPendingIntent(context, widgetId * 10 + 2, "StorageSaver")
            )
            views.setOnClickPendingIntent(
                R.id.btn_upload_original,
                pickerPendingIntent(context, widgetId * 10 + 3, "Original")
            )
            views.setOnClickPendingIntent(
                R.id.btn_upload_family,
                pickerPendingIntent(context, widgetId * 10 + 4, "Family")
            )
            return views
        }

        private fun pickerPendingIntent(
            context: Context,
            reqCode: Int,
            profileFolder: String
        ): PendingIntent {
            val intent = Intent(context, QuickSmbUploadActivity::class.java).apply {
                action = QuickSmbUploadActivity.ACTION_PICK_AND_UPLOAD
                putExtra(QuickSmbUploadActivity.EXTRA_PROFILE_FOLDER, profileFolder)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun refreshAllWidgets(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, HouseholdUploadWidgetProvider::class.java)
                )
                for (id in ids) {
                    mgr.updateAppWidget(id, buildRemoteViews(context, id))
                }
            }
        }

        fun requestPinWidget(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                if (!mgr.isRequestPinAppWidgetSupported) return false
                mgr.requestPinAppWidget(
                    ComponentName(context, HouseholdUploadWidgetProvider::class.java),
                    null,
                    null
                )
            }.getOrDefault(false)
        }
    }
}
