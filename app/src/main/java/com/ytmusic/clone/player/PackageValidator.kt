package com.ytmusic.clone.player

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.media3.session.MediaSession
import com.ytmusic.clone.data.network.DiagnosticLogger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface allowing mock / fake implementations in unit tests without Robolectric.
 */
interface PackageVerification {
    fun isTrusted(controller: MediaSession.ControllerInfo): Boolean
}

/**
 * Task C2: Package validator following Universal Android Music Player (UAMP) security pattern:
 *  - Own app (packageName) is always trusted.
 *  - SYSTEM_UID (1000) or Media3 controller.isTrusted is trusted.
 *  - System apps with explicit allowlist (System UI, Android Auto, Google Assistant, Bluetooth, Car Media).
 *  - Android Auto accepted as system app or updated system app (FLAG_UPDATED_SYSTEM_APP).
 *  - Certificates matched by SHA-256 fingerprint from allowlisted constant list.
 *  - Apps holding MEDIA_CONTENT_CONTROL permission.
 *  - Rejected controllers logged to DiagnosticLogger.
 *  - Cached results per package + uid for zero performance impact.
 */
class PackageValidator(private val context: Context) : PackageVerification {
    companion object {
        private const val TAG = "PackageValidator"

        // Explicit allowlist of system packages authorized for full library browsing
        val ALLOWLISTED_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.projection.gearhead",    // Android Auto
            "com.google.android.googlequicksearchbox",    // Google Assistant
            "com.android.bluetooth",                      // Bluetooth AVRCP / Headphone stack
            "com.google.android.bluetooth",               // Google / Pixel Bluetooth stack
            "com.android.car.media",                      // Android Automotive OS Media Center
            "com.google.android.carassistant"             // Google Assistant for Automotive
        )

        /**
         * Allowlisted Certificate SHA-256 Fingerprints.
         *
         * TODO(owner): To allowlist a custom companion or car app:
         * 1. Extract signing cert: apksigner verify --print-certs <app.apk>
         * 2. Copy the SHA-256 fingerprint (uppercase without colons) and add below.
         */
        val ALLOWLISTED_CERTIFICATE_HASHES = setOf<String>(
            // Owner can supply partner/car hashes here
        )
    }

    private val cache = ConcurrentHashMap<Pair<String, Int>, Boolean>()

    override fun isTrusted(controller: MediaSession.ControllerInfo): Boolean {
        val packageName = controller.packageName
        val uid = controller.uid

        // 1. Own app process is always trusted
        if (packageName == context.packageName) {
            return true
        }

        // 2. System UID (1000) or privileged system controller is always trusted
        if (uid == Process.SYSTEM_UID || controller.isTrusted) {
            return true
        }

        val cacheKey = Pair(packageName, uid)
        cache[cacheKey]?.let { return it }

        val trusted = checkTrust(controller)
        cache[cacheKey] = trusted

        if (!trusted) {
            DiagnosticLogger.log("media", "REJECTED", "Rejected untrusted controller: $packageName")
        }

        return trusted
    }

    private fun checkTrust(controller: MediaSession.ControllerInfo): Boolean {
        val packageName = controller.packageName
        val packageManager = context.packageManager

        try {
            // Check if caller holds MEDIA_CONTENT_CONTROL permission
            if (packageManager.checkPermission(
                    android.Manifest.permission.MEDIA_CONTENT_CONTROL,
                    packageName
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }

            // Check if app is an authentic system app on the explicit system allowlist
            val appInfo = packageInfo.applicationInfo
            if (appInfo != null) {
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)

                // Android Auto: accept system app OR updated system app
                if (packageName == "com.google.android.projection.gearhead" && (isSystem || isUpdatedSystem)) {
                    return true
                }

                // Allow Media Simulator only in debug builds
                val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (isDebug && packageName == "com.google.android.autosimulator") {
                    return true
                }

                if (isSystem && ALLOWLISTED_SYSTEM_PACKAGES.contains(packageName)) {
                    return true
                }
            }

            // Check certificate SHA-256 fingerprints
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures != null) {
                for (sig in signatures) {
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(sig.toByteArray())
                    val hex = digest.joinToString("") { "%02X".format(it) }
                    if (ALLOWLISTED_CERTIFICATE_HASHES.contains(hex)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Package validation exception for $packageName: ${e.javaClass.simpleName}")
        }

        return false
    }
}
