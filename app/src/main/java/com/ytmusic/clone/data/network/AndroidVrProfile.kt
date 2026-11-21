package com.ytmusic.clone.data.network

import com.google.gson.JsonObject
import com.ytmusic.clone.data.local.ExtractorPrefs
import java.util.Locale
import java.util.TimeZone

/**
 * YouTube InnerTube client profile for Android VR (Meta Quest / Oculus Quest).
 * Verified against yt-dlp & InnerTube specifications:
 *
 *  - clientName: 'ANDROID_VR'
 *  - clientVersion: '1.71.26'
 *  - INNERTUBE_CONTEXT_CLIENT_NAME: 28
 *  - deviceMake: 'Oculus'
 *  - deviceModel: 'Quest 3'
 *  - androidSdkVersion: 32
 *  - osName: 'Android'
 *  - osVersion: '12L'
 *  - userAgent: 'com.google.android.apps.youtube.vr.oculus/1.71.26 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip'
 *
 * This client profile serves as a completely independent, non-cipher fallback tier.
 * It does NOT share the visionOS player endpoint or visitor data, ensuring that an issue
 * with visionOS will never bring down the Android VR tier.
 */
object AndroidVrProfile {
    const val CLIENT_NAME = "ANDROID_VR"
    const val DEFAULT_CLIENT_VERSION = "1.71.26"
    const val CLIENT_ID = "28" // INNERTUBE_CONTEXT_CLIENT_NAME for ANDROID_VR
    const val DEVICE_MAKE = "Oculus"
    const val DEVICE_MODEL = "Quest 3"
    const val ANDROID_SDK_VERSION = 32
    const val OS_NAME = "Android"
    const val OS_VERSION = "12L"

    val clientVersion: String
        get() = ExtractorPrefs.getAndroidVrVersion()

    fun getUserAgent(version: String = clientVersion): String =
        "com.google.android.apps.youtube.vr.oculus/$version (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    val USER_AGENT: String
        get() = getUserAgent(clientVersion)

    /**
     * Builds the InnerTube context client JSON object for ANDROID_VR.
     */
    fun buildClientContext(visitorData: String? = null): JsonObject = JsonObject().apply {
        addProperty("clientName", CLIENT_NAME)
        addProperty("clientVersion", clientVersion)
        if (!visitorData.isNullOrBlank()) {
            addProperty("visitorData", visitorData)
        }
        addProperty("deviceMake", DEVICE_MAKE)
        addProperty("deviceModel", DEVICE_MODEL)
        addProperty("androidSdkVersion", ANDROID_SDK_VERSION)
        addProperty("osName", OS_NAME)
        addProperty("osVersion", OS_VERSION)
        addProperty("userAgent", USER_AGENT)
        addProperty("hl", getDeviceHl())
        addProperty("gl", getDeviceGl())
        addProperty("utcOffsetMinutes", getDeviceUtcOffsetMinutes())
    }

    /**
     * Builds official request headers for ANDROID_VR InnerTube calls.
     */
    fun buildHeaders(visitorData: String? = null): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept", "*/*")
        put("Accept-Language", "${getDeviceHl()}, ${getDeviceLanguage()};q=0.9")
        put("X-YouTube-Client-Name", CLIENT_ID)
        put("X-YouTube-Client-Version", clientVersion)
        if (!visitorData.isNullOrBlank()) {
            put("X-Goog-Visitor-Id", visitorData)
        }
    }

    fun getDeviceCountry(): String =
        Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "US"

    fun getDeviceLanguage(): String =
        Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"

    fun getDeviceHl(): String =
        Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "${getDeviceLanguage()}-${getDeviceCountry()}"

    fun getDeviceGl(): String = getDeviceCountry()

    fun getDeviceUtcOffsetMinutes(): Int =
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
}
