package com.ytmusic.clone.data.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds requests for googlevideo "videoplayback" URLs.
 * Mobile streams (&c=VISIONOS) require standard GET requests with byte ranges.
 * Unified User-Agent is VisionOsProfile.USER_AGENT across all playback and probe requests.
 */
object YoutubeRequests {
    const val FIREFOX_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    private val POST_BODY = byteArrayOf(0x78, 0x00)
    private val formType = "application/x-www-form-urlencoded".toMediaType()
    private val requestNumber = AtomicInteger(0)

    fun isVideoPlayback(url: String): Boolean {
        val http = url.toHttpUrlOrNull() ?: return false
        val host = http.host
        return (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) && http.encodedPath.startsWith("/videoplayback")
    }

    /** length = -1 means "until the end". */
    fun build(
        url: String,
        position: Long = 0L,
        length: Long = -1L,
        userAgent: String? = null
    ): Request {
        UrlPolicy.verifyOrThrow(url, PolicyScope.STREAM)
        val playback = isVideoPlayback(url)
        val finalUrl = if (playback && !url.contains("&rn=")) {
            "$url&rn=${requestNumber.getAndIncrement()}"
        } else url

        val builder = Request.Builder().url(finalUrl)

        if (position > 0L || length != -1L) {
            val end = if (length != -1L) (position + length - 1).toString() else ""
            builder.header("Range", "bytes=$position-$end")
        }

        // Multi-client User-Agent: uses AndroidVrProfile for VR streams, VisionOsProfile for visionOS
        val defaultUa = if (finalUrl.contains("&c=ANDROID_VR")) {
            AndroidVrProfile.USER_AGENT
        } else {
            VisionOsProfile.USER_AGENT
        }
        val ua = userAgent ?: defaultUa
        builder.header("User-Agent", ua)

        if (!playback) {
            return builder.build()
        }

        builder.header("TE", "trailers")

        val isMobileStream = finalUrl.contains("&c=VISIONOS") ||
                finalUrl.contains("&c=ANDROID") ||
                finalUrl.contains("&c=IOS")

        return if (isMobileStream || !finalUrl.contains("&c=WEB_REMIX")) {
            builder.get().build()
        } else {
            builder.post(POST_BODY.toRequestBody(formType)).build()
        }
    }
}
