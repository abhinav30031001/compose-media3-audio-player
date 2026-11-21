package com.ytmusic.clone.data.network

import com.google.gson.JsonObject
import com.ytmusic.clone.data.local.ExtractorPrefs
import java.util.Locale
import java.util.TimeZone

/**
 * YouTube InnerTube client profile for visionOS.
 * Sourced directly from yt-dlp master branch:
 * https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py
 *
 * Sourced constants:
 *  - clientName: 'VISIONOS'
 *  - clientVersion: '1.02'
 *  - INNERTUBE_CONTEXT_CLIENT_NAME: 101
 *  - deviceMake: 'Apple'
 *  - deviceModel: 'RealityDevice17,1'
 *  - osName: 'visionOS'
 *  - osVersion: '26.5.23O471'
 *  - userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15'
 */
object VisionOsProfile {
    const val CLIENT_NAME = "VISIONOS"
    const val DEFAULT_CLIENT_VERSION = "1.02"
    const val CLIENT_ID = "101" // INNERTUBE_CONTEXT_CLIENT_NAME
    const val DEVICE_MAKE = "Apple"
    const val DEVICE_MODEL = "RealityDevice17,1"
    const val OS_NAME = "visionOS"
    const val OS_VERSION = "26.5.23O471"

    // Single unified User-Agent used across InnerTube, probes, HLS, progressive playback, and downloads
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    val clientVersion: String
        get() = ExtractorPrefs.getVisionOsVersion()

    /**
     * Builds the Innertube context client JSON object dynamically using
     * genuine device locale and timezone.
     */
    fun buildClientContext(visitorData: String? = null): JsonObject = JsonObject().apply {
        addProperty("clientName", CLIENT_NAME)
        addProperty("clientVersion", clientVersion)
        if (!visitorData.isNullOrBlank()) {
            addProperty("visitorData", visitorData)
        }
        addProperty("deviceMake", DEVICE_MAKE)
        addProperty("deviceModel", DEVICE_MODEL)
        addProperty("osName", OS_NAME)
        addProperty("osVersion", OS_VERSION)
        addProperty("hl", getDeviceHl())
        addProperty("gl", getDeviceGl())
        addProperty("utcOffsetMinutes", getDeviceUtcOffsetMinutes())
    }

    /**
     * Builds official request headers for visionOS InnerTube calls.
     */
    fun buildHeaders(visitorData: String? = null): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept", "*/*")
        put("Accept-Language", "${getDeviceHl()}, ${getDeviceLanguage()};q=0.9")
        put("X-YouTube-Client-Name", CLIENT_ID)
        put("X-YouTube-Client-Version", clientVersion)
        put("Origin", "https://www.youtube.com")
        if (!visitorData.isNullOrBlank()) {
            put("X-Goog-Visitor-Id", visitorData)
        }
    }

    fun getDeviceCountry(): String =
        Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "US"

    fun getDeviceLanguage(): String =
        Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"

    // Use toLanguageTag() to avoid legacy obsolete codes like 'in' or 'iw'
    fun getDeviceHl(): String =
        Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "${getDeviceLanguage()}-${getDeviceCountry()}"

    fun getDeviceGl(): String = getDeviceCountry()

    fun getDeviceUtcOffsetMinutes(): Int =
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
}
