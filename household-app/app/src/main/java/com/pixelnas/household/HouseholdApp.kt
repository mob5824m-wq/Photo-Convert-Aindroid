package com.pixelnas.household

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pixelnas.household.data.HouseholdPreferences
import com.pixelnas.household.model.TransferBatchState
import com.pixelnas.household.smb.SmbShareClient
import com.pixelnas.household.sync.CameraRollSmbWorker
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

class HouseholdApp : Application() {

    lateinit var preferences: HouseholdPreferences
        private set

    val smbClient: SmbShareClient = SmbShareClient()

    val transferState = MutableStateFlow(TransferBatchState())

    override fun onCreate() {
        super.onCreate()
        preferences = HouseholdPreferences(this)
        syncAutoBackupSchedule(preferences.current.autoCameraSyncWifiOnly)
    }

    fun syncAutoBackupSchedule(enabled: Boolean) {
        val wm = WorkManager.getInstance(this)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME_CAMERA_SYNC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<CameraRollSmbWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK_NAME_CAMERA_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    companion object {
        const val WORK_NAME_CAMERA_SYNC = "household_samba_camera_sync"
    }
}
