package com.pixelnas.household.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.pixelnas.household.MainActivity
import com.pixelnas.household.data.HouseholdPreferences
import com.pixelnas.household.sync.SmbUploadService

/**
 * Handles 1-tap Home Screen Samba Widget uploads AND Android System Share Sheet (`ACTION_SEND` /
 * `ACTION_SEND_MULTIPLE`) transfers directly over SMB2/SMB3.
 */
class QuickSmbUploadActivity : ComponentActivity() {

    private var requestedProfile: String? = null

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            SmbUploadService.enqueueBatch(this, uris, requestedProfile)
            Toast.makeText(
                this,
                "Uploading ${uris.size} item(s) to NAS over Samba...",
                Toast.LENGTH_SHORT
            ).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // AppWidget configuration handshake -> immediately confirm widget placement
        if (intent?.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            val widgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            HouseholdUploadWidgetProvider.refreshAllWidgets(this)
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            )
            finish()
            return
        }

        val prefs = HouseholdPreferences(this)
        if (prefs.current.host.isBlank()) {
            Toast.makeText(
                this,
                "Pair your home NAS Samba share first!",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val single = extractSingleStreamUri(intent)
                if (single != null) {
                    SmbUploadService.enqueueBatch(this, listOf(single))
                    Toast.makeText(this, "Sending to NAS over Samba...", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = extractMultiStreamUris(intent)
                if (list.isNotEmpty()) {
                    SmbUploadService.enqueueBatch(this, list)
                    Toast.makeText(
                        this,
                        "Sending ${list.size} files to NAS over Samba...",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                finish()
            }
            else -> {
                requestedProfile = intent?.getStringExtra(EXTRA_PROFILE_FOLDER)
                pickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
        }
    }

    private fun extractSingleStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractMultiStreamUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }

    companion object {
        const val ACTION_PICK_AND_UPLOAD = "com.pixelnas.household.ACTION_PICK_AND_UPLOAD"
        const val EXTRA_PROFILE_FOLDER = "extra_profile_folder"
    }
}
