package com.pixelnas.photosaver

import android.app.Application
import com.pixelnas.photosaver.data.HistoryStore
import com.pixelnas.photosaver.data.NasPreferences
import com.pixelnas.photosaver.network.NasFtpServer
import com.pixelnas.photosaver.network.NasHttpWebDavServer
import com.pixelnas.photosaver.network.NasSmbServer
import com.pixelnas.photosaver.watcher.IncomingFolderWatcher

class PixelNasApp : Application() {

    lateinit var preferences: NasPreferences
        private set

    lateinit var historyStore: HistoryStore
        private set

    lateinit var watcher: IncomingFolderWatcher
        private set

    lateinit var smbServer: NasSmbServer
        private set

    lateinit var ftpServer: NasFtpServer
        private set

    lateinit var httpWebDavServer: NasHttpWebDavServer
        private set

    var onServiceNotificationRefresh: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        preferences = NasPreferences(this)
        historyStore = HistoryStore(this)
        watcher = IncomingFolderWatcher(
            context = this,
            prefs = preferences,
            historyStore = historyStore,
            onStatsChanged = { onServiceNotificationRefresh?.invoke() }
        )
        smbServer = NasSmbServer(preferences, watcher)
        ftpServer = NasFtpServer(preferences, watcher)
        httpWebDavServer = NasHttpWebDavServer(
            context = this,
            prefs = preferences,
            historyStore = historyStore,
            watcher = watcher,
            ftpServer = ftpServer,
            smbServer = smbServer
        )
    }
}
