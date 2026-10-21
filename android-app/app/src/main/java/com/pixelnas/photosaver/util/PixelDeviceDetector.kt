package com.pixelnas.photosaver.util

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.pixelnas.photosaver.model.PixelHardwareInfo
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

object PixelDeviceDetector {

    fun detectCurrentDevice(): PixelHardwareInfo {
        return detectByCodename(
            codename = (Build.DEVICE ?: "").lowercase(),
            model = Build.MODEL ?: "Android Device"
        )
    }

    fun detectByCodename(codename: String, model: String = "Google Pixel"): PixelHardwareInfo {
        return when (codename.trim().lowercase()) {
            "sailfish", "marlin" -> PixelHardwareInfo(
                profileId = "pixel_1",
                displayName = "Google Pixel / Pixel XL (Gen 1)",
                codename = codename,
                googlePhotosTier = "Unlimited Original Quality",
                recommendedSaver = false,
                notes = "Pixel 1 backs up unlimited Original Quality forever. Enable Storage Saver only if you want smaller local files before Google Photos finishes syncing."
            )
            "walleye", "taimen" -> PixelHardwareInfo(
                profileId = "pixel_2",
                displayName = "Google Pixel 2 / 2 XL",
                codename = codename,
                googlePhotosTier = "Unlimited Storage Saver",
                recommendedSaver = true,
                notes = "Unlimited free Storage Saver backups (16MP photos / 1080p video). Pre-converting frees up to 90% of local storage."
            )
            "blueline", "crosshatch", "sargo", "bonito" -> PixelHardwareInfo(
                profileId = "pixel_3",
                displayName = "Google Pixel 3 / 3a Series",
                codename = codename,
                googlePhotosTier = "Unlimited Storage Saver",
                recommendedSaver = true,
                notes = "Unlimited free Storage Saver backups. Pre-converting keeps 64GB flash free during large camera bursts."
            )
            "flame", "coral", "sunfish" -> PixelHardwareInfo(
                profileId = "pixel_4",
                displayName = "Google Pixel 4 / 4a Series",
                codename = codename,
                googlePhotosTier = "Unlimited Storage Saver",
                recommendedSaver = true,
                notes = "6GB RAM & hardware MediaCodec make 48MP photo and 4K->1080p video conversion fast and cool."
            )
            "bramble", "redfin" -> PixelHardwareInfo(
                profileId = "pixel_5",
                displayName = "Google Pixel 4a (5G) / Pixel 5",
                codename = codename,
                googlePhotosTier = "Unlimited Storage Saver",
                recommendedSaver = true,
                notes = "Last Pixel generation with unlimited free Storage Saver backups. Ideal 24/7 home photo NAS."
            )
            else -> PixelHardwareInfo(
                profileId = "pixel_5",
                displayName = model.ifBlank { "Google Pixel NAS Device" },
                codename = codename,
                googlePhotosTier = "Google Photos Storage Saver Relay",
                recommendedSaver = true,
                notes = "Converts incoming >16MP photos & >1080p videos to Storage Saver quality in DCIM/PixelNAS and deletes full-res originals."
            )
        }
    }

    fun getLanIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf("127.0.0.1")
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!result.contains(ip)) {
                            // Prefer Wi-Fi (wlan) interfaces first
                            if (intf.name.startsWith("wlan") || intf.name.startsWith("eth")) {
                                result.add(0, ip)
                            } else {
                                result.add(ip)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return if (result.isEmpty()) listOf("127.0.0.1") else result
    }

    fun renderQrBitmap(content: String, sizePx: Int = 360): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.rgb(15, 23, 42) else Color.WHITE
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }

    fun renderQrPngBytes(content: String, sizePx: Int = 320): ByteArray {
        val bmp = renderQrBitmap(content, sizePx)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
