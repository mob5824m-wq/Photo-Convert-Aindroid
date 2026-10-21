package com.pixelnas.household.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.pixelnas.household.model.DiscoveredSmbHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers home NAS Samba shares on Wi-Fi using:
 *  1. Android mDNS / DNS-SD (`_smb._tcp.` for Samba servers & `_http._tcp.` for PixelPhotoNAS)
 *  2. Fast parallel LAN `/24` TCP port scan on port `445` (standard Samba) & port `4445` (unrooted Pixel NAS)
 */
object NsdSmbDiscovery {

    suspend fun scanLanForSambaShares(context: Context): List<DiscoveredSmbHost> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredSmbHost>()
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        if (nsd != null) {
            for (serviceType in listOf("_smb._tcp.", "_http._tcp.")) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val sName = serviceInfo.serviceName ?: ""
                        if (serviceType == "_http._tcp." && !sName.contains("PixelPhotoNAS", ignoreCase = true)) {
                            return
                        }
                        @Suppress("DEPRECATION")
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val hostAddr = resolved.host?.hostAddress ?: return
                                val candidatePort = if (serviceType == "_smb._tcp.") {
                                    resolved.port
                                } else {
                                    if (isTcpPortOpen(hostAddr, 4445, 250)) 4445 else 445
                                }
                                found["$hostAddr:$candidatePort"] = DiscoveredSmbHost(
                                    label = resolved.serviceName ?: "Home Samba NAS",
                                    host = hostAddr,
                                    port = candidatePort,
                                    suggestedShare = if (sName.contains("PixelPhotoNAS", true)) "PixelNAS" else "PixelNAS",
                                    source = if (candidatePort == 4445) "Pixel Photo NAS (mDNS)" else "Samba Server (mDNS)"
                                )
                            }
                        })
                    }
                }
                runCatching {
                    nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    listeners.add(listener)
                }
            }
        }

        // Parallel subnet port scan across local IPv4 /24 for both port 445 and port 4445
        val localIp = findLocalLanIpv4()
        if (localIp != null) {
            val prefix = localIp.substringBeforeLast('.')
            coroutineScope {
                (1..254).chunked(48).forEach { batch ->
                    batch.map { hostOctet ->
                        async {
                            val ip = "$prefix.$hostOctet"
                            if (isTcpPortOpen(ip, 4445, 160)) {
                                found.putIfAbsent(
                                    "$ip:4445",
                                    DiscoveredSmbHost(
                                        label = "Pixel Photo NAS ($ip)",
                                        host = ip,
                                        port = 4445,
                                        suggestedShare = "PixelNAS",
                                        source = "Wi-Fi Scan (:4445)"
                                    )
                                )
                            } else if (isTcpPortOpen(ip, 445, 160)) {
                                found.putIfAbsent(
                                    "$ip:445",
                                    DiscoveredSmbHost(
                                        label = "Home Samba NAS ($ip)",
                                        host = ip,
                                        port = 445,
                                        suggestedShare = "PixelNAS",
                                        source = "Wi-Fi Samba (:445)"
                                    )
                                )
                            }
                            Unit
                        }
                    }.awaitAll()
                }
            }
        } else {
            delay(1200L)
        }

        if (nsd != null) {
            listeners.forEach { l -> runCatching { nsd.stopServiceDiscovery(l) } }
        }
        found.values.sortedWith(compareBy({ it.port != 4445 }, { it.host }))
    }

    private fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun findLocalLanIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) return ip
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
