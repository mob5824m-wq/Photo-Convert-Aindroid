package com.pixelnas.photosaver.network

import com.pixelnas.photosaver.data.NasPreferences
import com.pixelnas.photosaver.watcher.IncomingFolderWatcher
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

class NasFtpServer(
    private val prefs: NasPreferences,
    private val watcher: IncomingFolderWatcher
) {
    private var server: FtpServer? = null
    var activePort: Int = 0
        private set

    @Synchronized
    fun startOrReload() {
        stop()
        val cfg = prefs.current
        if (!cfg.ftpEnabled) return

        val (incDir, _) = prefs.ensureFolders()
        val homeRoot = incDir.parentFile ?: incDir

        val factory = FtpServerFactory()
        val listenerFactory = ListenerFactory().apply {
            port = cfg.ftpPort
            serverAddress = "0.0.0.0"
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                passivePorts = "50000-50030"
            }.createDataConnectionConfiguration()
        }
        factory.addListener("default", listenerFactory.createListener())

        factory.userManager = object : UserManager {
            private fun buildUser(name: String, password: String? = null): BaseUser {
                return BaseUser().apply {
                    this.name = name
                    this.password = password
                    this.homeDirectory = homeRoot.absolutePath
                    this.enabled = true
                    this.authorities = listOf<Authority>(WritePermission())
                }
            }

            override fun getUserByName(username: String): User? = buildUser(username, cfg.authPassword)
            override fun getAllUserNames(): Array<String> = arrayOf(cfg.authUsername, "anonymous")
            override fun delete(username: String) = Unit
            override fun save(user: User) = Unit
            override fun doesExist(username: String): Boolean = true
            override fun getAdminName(): String = cfg.authUsername
            override fun isAdmin(username: String): Boolean = true

            override fun authenticate(authentication: org.apache.ftpserver.ftplet.Authentication): User {
                if (!cfg.authEnabled || cfg.authPassword.isBlank()) {
                    if (authentication is AnonymousAuthentication) {
                        return buildUser("anonymous")
                    }
                    if (authentication is UsernamePasswordAuthentication) {
                        return buildUser(authentication.username ?: "pixel")
                    }
                } else if (authentication is UsernamePasswordAuthentication) {
                    if (authentication.username == cfg.authUsername &&
                        authentication.password == cfg.authPassword
                    ) {
                        return buildUser(cfg.authUsername, cfg.authPassword)
                    }
                }
                throw org.apache.ftpserver.ftplet.AuthenticationFailedException("Invalid NAS credentials")
            }
        }

        factory.ftplets = mapOf(
            "pixelnas_upload_hook" to object : DefaultFtplet() {
                override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
                    val arg = request.argument ?: return FtpletResult.DEFAULT
                    val currDir = session.fileSystemView.workingDirectory.absolutePath
                    val target = File(homeRoot, "$currDir/$arg".replace("//", "/"))
                    if (target.isFile && target.absolutePath.startsWith(incDir.absolutePath)) {
                        watcher.enqueueIfAuto(target)
                    }
                    return FtpletResult.DEFAULT
                }
            }
        )

        try {
            val instance = factory.createServer()
            instance.start()
            server = instance
            activePort = cfg.ftpPort
        } catch (_: Exception) {
            server = null
            activePort = 0
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        activePort = 0
    }
}
