package com.pixelnas.household

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixelnas.household.ui.HouseholdMainScreen
import com.pixelnas.household.ui.HouseholdViewModel
import com.pixelnas.household.ui.theme.HouseholdTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions are optional and only used for background Camera Roll Wi-Fi sync */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        setContent {
            HouseholdTheme {
                val vm: HouseholdViewModel = viewModel()
                HouseholdMainScreen(
                    viewModel = vm,
                    onRequestMediaPermissions = { requestCameraRollPermissions() }
                )
            }
        }
    }

    private fun requestCameraRollPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }
}
