package com.ytmusic.clone.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Task C6: Stores user settings and client version numbers in private SharedPreferences,
 * with visitorData encrypted at rest via LocalCrypto (AES-256-GCM, AAD visitor_data).
 */
object ExtractorPrefs {
    private const val TAG = "ExtractorPrefs"
    private const val PREFS_NAME = "extractor_prefs"
    private const val AAD_VISITOR_DATA = "visitor_data"
    private const val AAD_YT_COOKIE = "yt_cookie"

    // Default constants - known working baselines
    const val DEFAULT_VISIONOS_VERSION = "1.02"
    const val DEFAULT_ANDROID_VR_VERSION = "1.71.26"
    const val DEFAULT_REMIX_VERSION = "1.20260804.16.00"

    private const val KEY_VISIONOS = "version_visionos"
    private const val KEY_ANDROID_VR = "version_android_vr"
    private const val KEY_REMIX = "version_remix"
    private const val KEY_VISITOR_DATA = "visitor_data"
    private const val KEY_ANDROID_VR_VISITOR_DATA = "visitor_data_android_vr"
    private const val KEY_MIGRATION_V717 = "migration_v717_applied"
    private const val KEY_MIGRATION_V724 = "migration_v724_applied"
    private const val KEY_LAST_CONFIG_TS = "last_config_ts"
    private const val KEY_LYRICS_LOOKUP_ENABLED = "lyrics_lookup_enabled"
    private const val KEY_YT_COOKIE = "yt_session_cookie"
    private const val KEY_YT_COOKIE_EXPIRED = "yt_cookie_expired"
    private val cookieLock = Any()
    private const val KEY_OFFICIAL_VIDEO_MAP = "official_video_id_map"

    private var prefs: SharedPreferences? = null

    // In-memory fallbacks when hardware crypto is temporarily unavailable
    @Volatile private var inMemoryVisitorData: String? = null
    @Volatile private var inMemoryVrVisitorData: String? = null
    @Volatile private var inMemoryYoutubeCookie: String? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            migrateIfNeeded()
        }
    }

    private fun migrateIfNeeded() {
        val p = prefs ?: return
        val currentVisionOs = p.getString(KEY_VISIONOS, null)
        if (currentVisionOs == "1.04" || !p.getBoolean(KEY_MIGRATION_V717, false)) {
            p.edit()
                .putString(KEY_VISIONOS, DEFAULT_VISIONOS_VERSION)
                .putBoolean(KEY_MIGRATION_V717, true)
                .apply()
        }
        val currentVr = p.getString(KEY_ANDROID_VR, null)
        if (currentVr == "1.60.19" || !p.getBoolean(KEY_MIGRATION_V724, false)) {
            p.edit()
                .putString(KEY_ANDROID_VR, DEFAULT_ANDROID_VR_VERSION)
                .putBoolean(KEY_MIGRATION_V724, true)
                .apply()
        }
    }

    private fun decodeOrMigratePref(key: String, inMemoryFallback: String?): String? {
        val raw = prefs?.getString(key, null)?.takeIf { it.isNotBlank() } ?: return inMemoryFallback
        val bytes = try {
            java.util.Base64.getDecoder().decode(raw)
        } catch (_: Exception) {
            try {
                android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                raw.toByteArray(Charsets.UTF_8)
            }
        }
        val decrypted = LocalCrypto.decryptOrMigrate(bytes, AAD_VISITOR_DATA)
        if (decrypted != null) {
            val hasEncHeader = bytes.size >= 4 && bytes[0] == 'E'.code.toByte() && bytes[1] == 'N'.code.toByte() && bytes[2] == 'C'.code.toByte() && (bytes[3] == '1'.code.toByte() || bytes[3] == '2'.code.toByte())
            if (!hasEncHeader) {
                try {
                    val enc = LocalCrypto.encrypt(decrypted, AAD_VISITOR_DATA)
                    val b64 = java.util.Base64.getEncoder().encodeToString(enc)
                    prefs?.edit()?.putString(key, b64)?.apply()
                } catch (_: Exception) {}
            }
            return decrypted
        }
        return inMemoryFallback
    }

    private fun saveEncryptedPref(key: String, token: String, setInMemory: (String) -> Unit) {
        val trimmed = token.trim()
        setInMemory(trimmed)
        try {
            val enc = LocalCrypto.encrypt(trimmed, AAD_VISITOR_DATA)
            val b64 = java.util.Base64.getEncoder().encodeToString(enc)
            prefs?.edit()?.putString(key, b64)?.apply()
        } catch (e: CryptoUnavailableException) {
            Log.w(TAG, e.javaClass.simpleName)
        } catch (e: Exception) {
            Log.w(TAG, e.javaClass.simpleName)
        }
    }

    fun getVisitorData(): String? =
        decodeOrMigratePref(KEY_VISITOR_DATA, inMemoryVisitorData)

    fun setVisitorData(token: String) {
        saveEncryptedPref(KEY_VISITOR_DATA, token) { inMemoryVisitorData = it }
    }

    fun clearVisitorData() {
        inMemoryVisitorData = null
        prefs?.edit()?.remove(KEY_VISITOR_DATA)?.apply()
    }

    fun getVisionOsVersion(): String =
        prefs?.getString(KEY_VISIONOS, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_VISIONOS_VERSION

    fun getAndroidVrVersion(): String =
        prefs?.getString(KEY_ANDROID_VR, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ANDROID_VR_VERSION

    fun getRemixVersion(): String =
        prefs?.getString(KEY_REMIX, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_REMIX_VERSION

    fun getAndroidVrVisitorData(): String? =
        decodeOrMigratePref(KEY_ANDROID_VR_VISITOR_DATA, inMemoryVrVisitorData)

    fun setAndroidVrVisitorData(token: String) {
        saveEncryptedPref(KEY_ANDROID_VR_VISITOR_DATA, token) { inMemoryVrVisitorData = it }
    }

    fun clearAndroidVrVisitorData() {
        inMemoryVrVisitorData = null
        prefs?.edit()?.remove(KEY_ANDROID_VR_VISITOR_DATA)?.apply()
    }

    fun setVersions(
        visionOs: String,
        androidVr: String,
        remix: String
    ) {
        prefs?.edit()
            ?.putString(KEY_VISIONOS, visionOs.trim())
            ?.putString(KEY_ANDROID_VR, androidVr.trim())
            ?.putString(KEY_REMIX, remix.trim())
            ?.apply()
    }

    fun getLastConfigTs(): Long =
        prefs?.getLong(KEY_LAST_CONFIG_TS, 0L) ?: 0L

    fun setLastConfigTs(ts: Long) {
        prefs?.edit()?.putLong(KEY_LAST_CONFIG_TS, ts)?.apply()
    }

    fun resetToDefaults() {
        clearYoutubeCookie()
        val savedVisitor = getVisitorData()
        val savedVrVisitor = getAndroidVrVisitorData()
        prefs?.edit()?.clear()?.apply()
        if (!savedVisitor.isNullOrBlank()) {
            setVisitorData(savedVisitor)
        }
        if (!savedVrVisitor.isNullOrBlank()) {
            setAndroidVrVisitorData(savedVrVisitor)
        }
        migrateIfNeeded()
    }

    fun resetDeviceIdentity() {
        clearVisitorData()
        clearAndroidVrVisitorData()
    }

    fun isLyricsLookupEnabled(): Boolean =
        prefs?.getBoolean(KEY_LYRICS_LOOKUP_ENABLED, true) ?: true

    fun setLyricsLookupEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_LYRICS_LOOKUP_ENABLED, enabled)?.apply()
    }

    fun getYoutubeCookie(): String? = synchronized(cookieLock) {
        val raw = prefs?.getString(KEY_YT_COOKIE, null)?.takeIf { it.isNotBlank() }
            ?: return inMemoryYoutubeCookie?.takeIf { it.isNotBlank() }
        val bytes = try {
            java.util.Base64.getDecoder().decode(raw)
        } catch (_: Exception) {
            return inMemoryYoutubeCookie?.takeIf { it.isNotBlank() }
        }
        val hasEncHeader = bytes.size >= 4 && bytes[0] == 'E'.code.toByte() && bytes[1] == 'N'.code.toByte() &&
            bytes[2] == 'C'.code.toByte() && (bytes[3] == '1'.code.toByte() || bytes[3] == '2'.code.toByte())
        if (!hasEncHeader) return null
        LocalCrypto.decryptOrMigrate(bytes, AAD_YT_COOKIE)?.takeIf { it.isNotBlank() }
            ?: inMemoryYoutubeCookie?.takeIf { it.isNotBlank() }
    }

    fun setYoutubeCookie(cookie: String) = synchronized(cookieLock) {
        inMemoryYoutubeCookie = cookie
        prefs?.edit()?.putBoolean(KEY_YT_COOKIE_EXPIRED, false)?.apply()
        try {
            val enc = LocalCrypto.encrypt(cookie, AAD_YT_COOKIE)
            val b64 = java.util.Base64.getEncoder().encodeToString(enc)
            prefs?.edit()?.putString(KEY_YT_COOKIE, b64)?.apply()
        } catch (_: Throwable) {}
    }

    fun clearYoutubeCookie() = synchronized(cookieLock) {
        inMemoryYoutubeCookie = null
        prefs?.edit()?.remove(KEY_YT_COOKIE)?.remove(KEY_YT_COOKIE_EXPIRED)?.apply()
    }

    fun hasYoutubeCookie(): Boolean = !getYoutubeCookie().isNullOrBlank()

    fun isYoutubeCookieExpired(): Boolean = prefs?.getBoolean(KEY_YT_COOKIE_EXPIRED, false) ?: false

    fun markYoutubeCookieExpired(expired: Boolean) {
        prefs?.edit()?.putBoolean(KEY_YT_COOKIE_EXPIRED, expired)?.apply()
    }

    private val validVideoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")
    private val videoMapLock = Any()
    private const val VIDEO_CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000L
    private const val KEY_VIDEO_CACHE_MIGRATED_V8 = "video_cache_migrated_v8"

    data class CachedVideoEntry(
        val videoId: String,
        val matcherVersion: Int,
        val ts: Long,
        val durationSec: Long
    )

    private fun ensureVideoCacheMigrated() {
        val p = prefs ?: return
        if (!p.getBoolean(KEY_VIDEO_CACHE_MIGRATED_V8, false)) {
            p.edit()
                .remove(KEY_OFFICIAL_VIDEO_MAP)
                .putBoolean(KEY_VIDEO_CACHE_MIGRATED_V8, true)
                .apply()
        }
    }

    fun getCachedMusicVideoId(songId: String, songDurationSec: Long = 0L): String? {
        if (!validVideoIdRegex.matches(songId)) return null
        ensureVideoCacheMigrated()
        val raw = prefs?.getString(KEY_OFFICIAL_VIDEO_MAP, null) ?: return null
        synchronized(videoMapLock) {
            return try {
                val rootObj = com.google.gson.JsonParser.parseString(raw).asJsonObject
                val el = rootObj.get(songId) ?: return null
                if (!el.isJsonObject) {
                    rootObj.remove(songId)
                    prefs?.edit()?.putString(KEY_OFFICIAL_VIDEO_MAP, rootObj.toString())?.apply()
                    return null
                }
                val entry = el.asJsonObject
                val vid = entry.get("videoId")?.asString?.takeIf { validVideoIdRegex.matches(it) } ?: return null
                val ver = entry.get("matcherVersion")?.asInt ?: 0
                val ts = entry.get("ts")?.asLong ?: 0L
                val dur = entry.get("durationSec")?.asLong ?: 0L
                val expired = (System.currentTimeMillis() - ts) > VIDEO_CACHE_TTL_MS
                val wrongVer = ver != com.ytmusic.clone.data.network.OfficialVideoMatcher.MATCHER_VERSION
                val badDur = songDurationSec > 0L && dur > 0L &&
                    !com.ytmusic.clone.data.network.OfficialVideoMatcher.durationClose(songDurationSec, dur, official = true)
                if (expired || wrongVer || badDur) {
                    rootObj.remove(songId)
                    prefs?.edit()?.putString(KEY_OFFICIAL_VIDEO_MAP, rootObj.toString())?.apply()
                    return null
                }
                vid
            } catch (_: Exception) {
                null
            }
        }
    }

    fun putCachedMusicVideoId(songId: String, videoId: String, durationSec: Long = 0L) {
        if (!validVideoIdRegex.matches(songId) || !validVideoIdRegex.matches(videoId)) return
        ensureVideoCacheMigrated()
        val p = prefs ?: return
        synchronized(videoMapLock) {
            try {
                val raw = p.getString(KEY_OFFICIAL_VIDEO_MAP, null)
                val obj = if (!raw.isNullOrBlank()) {
                    try { com.google.gson.JsonParser.parseString(raw).asJsonObject } catch (_: Exception) { com.google.gson.JsonObject() }
                } else {
                    com.google.gson.JsonObject()
                }
                val entry = com.google.gson.JsonObject().apply {
                    addProperty("videoId", videoId)
                    addProperty("matcherVersion", com.ytmusic.clone.data.network.OfficialVideoMatcher.MATCHER_VERSION)
                    addProperty("ts", System.currentTimeMillis())
                    addProperty("durationSec", durationSec)
                }
                obj.remove(songId)
                obj.add(songId, entry)
                while (obj.size() > 200) {
                    val oldestKey = obj.keySet().firstOrNull() ?: break
                    obj.remove(oldestKey)
                }
                p.edit().putString(KEY_OFFICIAL_VIDEO_MAP, obj.toString()).apply()
            } catch (_: Exception) {}
        }
    }

    fun removeCachedMusicVideoId(songId: String) {
        val p = prefs ?: return
        synchronized(videoMapLock) {
            try {
                val raw = p.getString(KEY_OFFICIAL_VIDEO_MAP, null) ?: return
                val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
                if (obj.has(songId)) {
                    obj.remove(songId)
                    p.edit().putString(KEY_OFFICIAL_VIDEO_MAP, obj.toString()).apply()
                }
            } catch (_: Exception) {}
        }
    }
}
