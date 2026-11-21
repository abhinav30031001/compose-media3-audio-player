package com.ytmusic.clone.data.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ytmusic.clone.data.local.ExtractorPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * Phase 4 & Task B1: Hardened Remote Config Client.
 *
 * Enforces cryptographic integrity and anti-rollback defenses:
 *  1. Constant URL only (no customUrl parameter).
 *  2. 16 KB maximum response body cap enforced via readCapped.
 *  3. Ed25519 digital signature verification supporting platform (API 33+) with BouncyCastle fallback (API 26+).
 *  4. Key rotation support (EMBEDDED_PUBLIC_KEYS_BASE64 list).
 *  5. Strict anti-rollback and replay protection (last_config_ts < ts <= now + 30 days).
 *  6. Strict version bounds (0-99999 or 8-digit date component).
 */
object RemoteConfigFetcher {
    private const val TAG = "RemoteConfigFetcher"
    const val DEFAULT_CONFIG_URL =
        "https://raw.githubusercontent.com/ytmusic-android/config/main/client_versions.json"

    private const val MAX_BODY_BYTES = 16 * 1024L // 16 KB cap

    // TODO(owner): replace with YOUR key
    val EMBEDDED_PUBLIC_KEYS_BASE64: List<String> = listOf(
        "0zOI00iV02uqlZVEdKR+b6zqzdKqyVRqfDzr/1m6bGE="
    )

    // RFC 8410 DER prefix for Ed25519 SubjectPublicKeyInfo (12 bytes)
    private val ED25519_DER_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )

    private val httpClient = SecureHttp.newBuilder(PolicyScope.CONFIG)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    val VERSION_REGEX = Regex("^[0-9]+(\\.[0-9]+){0,4}$")

    data class ConfigResult(
        val success: Boolean,
        val message: String,
        val visionOsVersion: String? = null,
        val androidVrVersion: String? = null,
        val remixVersion: String? = null
    )

    fun isValidVersion(version: String?): Boolean {
        if (version.isNullOrBlank()) return false
        if (!VERSION_REGEX.matches(version)) return false
        val parts = version.split(".")
        if (parts.size > 5) return false
        for (p in parts) {
            val num = p.toLongOrNull() ?: return false
            val isDateComponent = (p.length == 8 && p.startsWith("20") && num in 20000101L..20991231L)
            if (!(num in 0L..99999L || isDateComponent)) return false
        }
        return true
    }

    fun buildCanonicalPayload(vos: String, avr: String, rmx: String, ts: Long): ByteArray {
        return "visionos=$vos&android_vr=$avr&remix=$rmx&ts=$ts".toByteArray(Charsets.UTF_8)
    }

    private fun decodeBase64Safe(input: String): ByteArray? {
        return try {
            java.util.Base64.getDecoder().decode(input.trim())
        } catch (_: Exception) {
            try {
                android.util.Base64.decode(input.trim(), android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Verifies an Ed25519 digital signature over the payload.
     * Tries Java platform provider first (API 33+), falling back to BouncyCastle Ed25519Signer for API 26-32.
     */
    fun verifySignature(
        payload: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String = EMBEDDED_PUBLIC_KEYS_BASE64.first()
    ): Boolean {
        val sigBytes = decodeBase64Safe(signatureBase64) ?: return false
        val rawPub = decodeBase64Safe(publicKeyBase64) ?: return false
        if (sigBytes.size != 64 || rawPub.size != 32) return false

        // 1. Try Java platform provider first (API 33+)
        try {
            val keySpec = X509EncodedKeySpec(ED25519_DER_PREFIX + rawPub)
            val kf = KeyFactory.getInstance("Ed25519")
            val pubKey = kf.generatePublic(keySpec)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(pubKey)
            verifier.update(payload)
            if (verifier.verify(sigBytes)) {
                return true
            }
        } catch (_: Exception) {
            // Platform provider not available on API 26-32; fall through to BouncyCastle
        }

        // 2. BouncyCastle fallback (supports minSdk 26+)
        return try {
            val pubParams = Ed25519PublicKeyParameters(rawPub, 0)
            val signer = Ed25519Signer()
            signer.init(false, pubParams)
            signer.update(payload, 0, payload.size)
            signer.verifySignature(sigBytes)
        } catch (e: Exception) {
            Log.w(TAG, e.javaClass.simpleName)
            false
        }
    }

    /**
     * Checks for updated client version strings and applies them if valid,
     * cryptographically signed under any of the embedded public keys, and newer than last applied.
     */
    suspend fun fetchAndApply(): ConfigResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(DEFAULT_CONFIG_URL)
                .header("User-Agent", "YTMusic-Config/1.0")
                .get()
                .build()

            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ConfigResult(
                        success = false,
                        message = "HTTP ${response.code} from config endpoint"
                    )
                }

                // Problem 2 fix: Enforce 16 KB body limit via readCapped
                val body = response.readCapped(MAX_BODY_BYTES)

                val json = gson.fromJson(body, JsonObject::class.java) ?: return@withContext ConfigResult(
                    success = false,
                    message = "Invalid JSON in configuration"
                )

                val vOs = json.get("visionos")?.asString?.trim() ?: ""
                val aVr = json.get("android_vr")?.asString?.trim() ?: ""
                val rmx = json.get("remix")?.asString?.trim() ?: ""
                val ts = json.get("ts")?.asLong ?: 0L
                val sig = json.get("sig")?.asString?.trim()

                // 3. Verify digital signature
                if (sig.isNullOrBlank()) {
                    return@withContext ConfigResult(
                        success = false,
                        message = "Configuration rejected: Missing digital signature ('sig')"
                    )
                }

                val canonical = buildCanonicalPayload(vOs, aVr, rmx, ts)
                // Problem 4 fix: accept signature valid under any embedded key
                val validSig = EMBEDDED_PUBLIC_KEYS_BASE64.any { key ->
                    verifySignature(canonical, sig, key)
                }
                if (!validSig) {
                    return@withContext ConfigResult(
                        success = false,
                        message = "Configuration rejected: Invalid Ed25519 digital signature"
                    )
                }

                // Problem 3 fix: Upper bound and non-positive check on timestamp
                val nowSec = System.currentTimeMillis() / 1000L
                val maxAllowedTs = nowSec + (30L * 24L * 60L * 60L) // max 30 days in future
                if (ts <= 0L || ts > maxAllowedTs) {
                    return@withContext ConfigResult(
                        success = false,
                        message = "Configuration rejected: Invalid timestamp ($ts)"
                    )
                }

                // 4. Anti-rollback check: timestamp must be newer than last applied
                val lastTs = ExtractorPrefs.getLastConfigTs()
                if (ts <= lastTs) {
                    return@withContext ConfigResult(
                        success = false,
                        message = "Configuration rejected: Timestamp ($ts) <= last applied ($lastTs)"
                    )
                }

                // 5. Sanity bounds check on version components
                if (vOs.isNotEmpty() && !isValidVersion(vOs)) {
                    return@withContext ConfigResult(
                        success = false,
                        message = "Invalid visionOS version format: '$vOs'"
                    )
                }
                if (aVr.isNotEmpty() && !isValidVersion(aVr)) {
                    return@withContext ConfigResult(
                        success = false,
                        message = "Invalid Android VR version format: '$aVr'"
                    )
                }
                if (rmx.isNotEmpty() && !isValidVersion(rmx)) {
                    return@withContext ConfigResult(
                        success = false,
                        message = "Invalid remix version format: '$rmx'"
                    )
                }

                val currentVos = ExtractorPrefs.getVisionOsVersion()
                val currentAvr = ExtractorPrefs.getAndroidVrVersion()
                val currentRmx = ExtractorPrefs.getRemixVersion()

                val newVos = if (vOs.isNotEmpty()) vOs else currentVos
                val newAvr = if (aVr.isNotEmpty()) aVr else currentAvr
                val newRmx = if (rmx.isNotEmpty()) rmx else currentRmx

                ExtractorPrefs.setVersions(newVos, newAvr, newRmx)
                ExtractorPrefs.setLastConfigTs(ts)

                VisionOsPlayerClient.invalidateCache()
                AndroidVrPlayerClient.invalidateCache()

                DiagnosticLogger.log("config", "APPLIED", "Remote config applied (ts=$ts)")

                ConfigResult(
                    success = true,
                    message = "Successfully applied remote configuration (ts=$ts)",
                    visionOsVersion = newVos,
                    androidVrVersion = newAvr,
                    remixVersion = newRmx
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, e.javaClass.simpleName)
            ConfigResult(
                success = false,
                message = "Config update failed: ${e.javaClass.simpleName}"
            )
        }
    }
}
