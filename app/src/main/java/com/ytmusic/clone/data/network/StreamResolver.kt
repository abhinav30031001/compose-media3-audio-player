package com.ytmusic.clone.data.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ytmusic.clone.data.local.ExtractorPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Resolved stream representation for ExoPlayer / Media3.
 */
sealed interface Resolved {
    val url: String
    val format: String
    val userAgent: String
    val tierId: String
    val loudnessDb: Float? get() = null

    data class Progressive(
        override val url: String,
        val mime: String,
        override val format: String,
        override val userAgent: String = VisionOsProfile.USER_AGENT,
        override val tierId: String = VisionOsProgressiveResolver.TIER_ID,
        override val loudnessDb: Float? = null
    ) : Resolved

    data class Hls(
        override val url: String,
        override val format: String,
        override val userAgent: String = VisionOsProfile.USER_AGENT,
        override val tierId: String = VisionOsHlsResolver.TIER_ID,
        override val loudnessDb: Float? = null
    ) : Resolved
}

/**
 * Typed resolution result to isolate tier health from video-specific or transient issues.
 */
sealed interface ResolveResult {
    data class Success(val resolved: Resolved) : ResolveResult
    data class VideoSpecific(val reason: String) : ResolveResult // Age-restricted, private, unplayable - DO NOT hit breaker
    data class TierWide(val reason: String) : ResolveResult      // 403, 429, 3MB cliff probe fail - COUNTS against breaker
    data class Transient(val reason: String) : ResolveResult     // Network drop/timeout - DO NOT hit breaker
}

/**
 * Typed player query result from InnerTube API.
 */
sealed interface PlayerQueryResult {
    data class Success(val json: JsonObject, val cpn: String) : PlayerQueryResult
    data class VideoSpecific(val reason: String) : PlayerQueryResult
    data class TierWide(val reason: String) : PlayerQueryResult
    data class Transient(val reason: String) : PlayerQueryResult
}

/**
 * Base interface for on-device YouTube stream resolvers.
 */
interface StreamResolver {
    val id: String
    val requiresSharedPlayer: Boolean get() = true
    val isProgressive: Boolean get() = this is VisionOsProgressiveResolver || this is AndroidVrProgressiveResolver || id.contains("progressive")
    suspend fun resolve(
        videoId: String,
        sharedPlayerJson: JsonObject? = null,
        cpn: String? = null
    ): ResolveResult
}

/**
 * Shared helper for visionOS player calls, visitorData rotation, and caching.
 */
internal object VisionOsPlayerClient {
    private const val TAG = "VisionOsPlayer"
    private const val GAPIS_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
    private const val YT_VISITOR_URL = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    // Dedicated resolver/probe OkHttp client with strict 8s callTimeout
    @androidx.annotation.VisibleForTesting
    internal var httpClient: OkHttpClient = SecureHttp.newBuilder(PolicyScope.STREAM)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // Bounded 20-entry cache (P1 #11)
    private data class CachedPlayer(val timestampMs: Long, val json: JsonObject, val cpn: String)
    private val playerCache = object : LinkedHashMap<String, CachedPlayer>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPlayer>?): Boolean {
            return size > 20
        }
    }

    @Volatile
    private var inMemoryVisitorData: String? = null

    @Volatile
    private var consecutiveTierWideFailures = 0

    fun getOrFetchVisitorData(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            val persisted = ExtractorPrefs.getVisitorData()
            if (!persisted.isNullOrBlank()) {
                inMemoryVisitorData = persisted
                return persisted
            }
            if (!inMemoryVisitorData.isNullOrBlank()) {
                return inMemoryVisitorData
            }
        }

        return try {
            val headers = VisionOsProfile.buildHeaders(null)
            val body = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", VisionOsProfile.buildClientContext(null))
                })
            }
            val request = Request.Builder()
                .url(YT_VISITOR_URL)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val root = gson.fromJson(response.readCapped(128 * 1024), JsonObject::class.java)
                val token = root?.getAsJsonObject("responseContext")?.get("visitorData")?.asString
                if (!token.isNullOrBlank()) {
                    inMemoryVisitorData = token
                    ExtractorPrefs.setVisitorData(token)
                    Log.d(TAG, "Persisted visitorData successfully")
                    token
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Visitor data fetch failed: ${e.javaClass.simpleName}")
            null
        }
    }

    fun recordTierWideFailure() {
        consecutiveTierWideFailures++
        if (consecutiveTierWideFailures >= 3) {
            Log.w(TAG, "Rotating visitorData after 3 consecutive tier-wide failures")
            ExtractorPrefs.clearVisitorData()
            inMemoryVisitorData = null
            consecutiveTierWideFailures = 0
        }
    }

    fun recordTierSuccess() {
        consecutiveTierWideFailures = 0
    }

    suspend fun getPlayerResponse(
        videoId: String,
        forceFresh: Boolean = false
    ): PlayerQueryResult = withContext(Dispatchers.IO) {
        synchronized(playerCache) {
            val cached = playerCache[videoId]
            val now = System.currentTimeMillis()
            if (!forceFresh && cached != null && (now - cached.timestampMs) < 15_000L) {
                return@withContext PlayerQueryResult.Success(cached.json, cached.cpn)
            }
        }

        val visitorData = getOrFetchVisitorData()
        val result = queryPlayer(videoId, visitorData, retryDepth = 0)

        if (result is PlayerQueryResult.Success) {
            synchronized(playerCache) {
                playerCache[videoId] = CachedPlayer(System.currentTimeMillis(), result.json, result.cpn)
            }
        }
        result
    }

    fun invalidateCache(videoId: String? = null) {
        synchronized(playerCache) {
            if (videoId != null) {
                playerCache.remove(videoId)
            } else {
                playerCache.clear()
            }
        }
    }

    private fun queryPlayer(
        videoId: String,
        visitorData: String?,
        retryDepth: Int = 0
    ): PlayerQueryResult {
        return try {
            val cpn = randomString(16)
            val body = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", VisionOsProfile.buildClientContext(visitorData))
                })
                addProperty("videoId", videoId)
                addProperty("cpn", cpn)
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }

            val headers = VisionOsProfile.buildHeaders(visitorData)
            val url = "$GAPIS_PLAYER_URL?prettyPrint=false&t=${randomString(12)}&id=$videoId"
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { res ->
                val code = res.code
                if (code == 403 || code == 429) {
                    if (retryDepth < 1) {
                        ExtractorPrefs.clearVisitorData()
                        inMemoryVisitorData = null
                        val freshVisitor = getOrFetchVisitorData(forceRefresh = true)
                        if (!freshVisitor.isNullOrBlank() && freshVisitor != visitorData) {
                            return queryPlayer(videoId, freshVisitor, retryDepth + 1)
                        }
                    }
                    recordTierWideFailure()
                    return PlayerQueryResult.TierWide("Player HTTP $code")
                }
                if (!res.isSuccessful) {
                    return PlayerQueryResult.Transient("Player HTTP $code")
                }

                val raw = res.readCapped(8 * 1024 * 1024)
                val root = gson.fromJson(raw, JsonObject::class.java) ?: return PlayerQueryResult.Transient("Malformed JSON from player API")

                // Store harvested token only when none currently exists
                val respVisitor = root.getAsJsonObject("responseContext")?.get("visitorData")?.asString
                if (!respVisitor.isNullOrBlank() && ExtractorPrefs.getVisitorData().isNullOrBlank()) {
                    inMemoryVisitorData = respVisitor
                    ExtractorPrefs.setVisitorData(respVisitor)
                }

                val playability = root.getAsJsonObject("playabilityStatus")
                val status = playability?.get("status")?.asString
                val reason = playability?.get("reason")?.asString ?: ""

                if (status != "OK") {
                    Log.w(TAG, "playabilityStatus $status: $reason")

                    // v7.19: whole-word classification (see PlayabilityClassifier)
                    when (PlayabilityClassifier.classify(status, reason)) {
                        PlayabilityClassifier.Kind.BOT_CHECK -> {
                            if (retryDepth < 1 && !respVisitor.isNullOrBlank() && visitorData != respVisitor) {
                                Log.i(TAG, "Bootstrapping visitorData from player response and retrying once...")
                                inMemoryVisitorData = respVisitor
                                ExtractorPrefs.setVisitorData(respVisitor)
                                return queryPlayer(videoId, respVisitor, retryDepth = retryDepth + 1)
                            }
                            recordTierWideFailure()
                            return PlayerQueryResult.TierWide("$status: $reason")
                        }
                        PlayabilityClassifier.Kind.VIDEO_SPECIFIC ->
                            return PlayerQueryResult.VideoSpecific("$status: $reason")
                        PlayabilityClassifier.Kind.TIER_WIDE ->
                            return PlayerQueryResult.TierWide("$status: $reason")
                    }
                }

                PlayerQueryResult.Success(root, cpn)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "queryPlayer exception: ${e.javaClass.simpleName}")
            PlayerQueryResult.Transient(e.javaClass.simpleName)
        }
    }

    private fun randomString(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString(length) {
            repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
    }
}

/**
 * Tier 1: visionOS Progressive Audio Resolver (Opus 251 > AAC 140).
 * Deep range probe at ~3,000,000 bytes with strict byte count verification.
 */
object VisionOsProgressiveResolver : StreamResolver {
    const val TIER_ID = "visionos_progressive"
    override val id: String = TIER_ID
    private const val TAG = "Tier1Progressive"

    internal data class AudioCandidate(
        val url: String,
        val mime: String,
        val label: String,
        val bitrate: Long,
        val contentLength: Long?
    )

    override suspend fun resolve(
        videoId: String,
        sharedPlayerJson: JsonObject?,
        cpn: String?
    ): ResolveResult = withContext(Dispatchers.IO) {
        val playerResult = if (sharedPlayerJson != null && cpn != null) {
            PlayerQueryResult.Success(sharedPlayerJson, cpn)
        } else {
            VisionOsPlayerClient.getPlayerResponse(videoId)
        }

        val (root, activeCpn) = when (playerResult) {
            is PlayerQueryResult.Success -> Pair(playerResult.json, playerResult.cpn)
            is PlayerQueryResult.VideoSpecific -> return@withContext ResolveResult.VideoSpecific(playerResult.reason)
            is PlayerQueryResult.TierWide -> return@withContext ResolveResult.TierWide(playerResult.reason)
            is PlayerQueryResult.Transient -> return@withContext ResolveResult.Transient(playerResult.reason)
        }

        val playability = root.getAsJsonObject("playabilityStatus")
        val status = playability?.get("status")?.asString
        val reason = playability?.get("reason")?.asString ?: "Video unavailable"

        if (status != "OK") {
            return@withContext when (PlayabilityClassifier.classify(status, reason)) {
                PlayabilityClassifier.Kind.BOT_CHECK -> ResolveResult.TierWide("$status: $reason")
                PlayabilityClassifier.Kind.VIDEO_SPECIFIC -> ResolveResult.VideoSpecific("$status: $reason")
                PlayabilityClassifier.Kind.TIER_WIDE -> ResolveResult.TierWide("$status: $reason")
            }
        }

        val streamingData = root.getAsJsonObject("streamingData")
            ?: return@withContext ResolveResult.VideoSpecific("No streamingData in player response")
        val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
            ?: return@withContext ResolveResult.VideoSpecific("No adaptiveFormats in player response")

        val candidate = selectBestAudioCandidate(adaptiveFormats)
            ?: return@withContext ResolveResult.VideoSpecific("No playable audio formats found")

        val finalUrl = candidate.url + "&cpn=" + activeCpn

        // Strict 3MB progressive probe with byte count verification
        val probeResult = probeProgressiveStream(finalUrl, candidate.contentLength)
        if (probeResult is ResolveResult.Success) {
            Log.i(TAG, "Deep range probe PASSED for $videoId (${candidate.label})")
            val loudnessDb = try {
                root.getAsJsonObject("playerConfig")
                    ?.getAsJsonObject("audioConfig")
                    ?.get("loudnessDb")
                    ?.asFloat
            } catch (_: Exception) {
                null
            }
            ResolveResult.Success(
                Resolved.Progressive(
                    url = finalUrl,
                    mime = candidate.mime,
                    format = candidate.label,
                    userAgent = VisionOsProfile.USER_AGENT,
                    tierId = id,
                    loudnessDb = loudnessDb
                )
            )
        } else {
            Log.w(TAG, "Deep range probe FAILED for $videoId: $probeResult")
            probeResult
        }
    }

    internal fun selectBestAudioCandidate(formats: JsonArray): AudioCandidate? {
        val f = AudioFormatPicker.pick(formats) { UrlPolicy.isAllowed(it, PolicyScope.STREAM) }
            ?: return null
        val mime = f.get("mimeType").asString
        val url = f.get("url").asString
        val bitrate = AudioFormatPicker.longOf(f, "bitrate")
        val isOpus = mime.contains("opus")
        val cleanMime = mime.substringBefore(";")
        val label = if (isOpus) "Opus (${bitrate / 1000}kbps)" else "AAC (${bitrate / 1000}kbps)"
        val len = f.get("contentLength")?.asString?.toLongOrNull()
            ?: f.get("contentLength")?.runCatching { asLong }?.getOrNull()
        return AudioCandidate(url, cleanMime, label, bitrate, len)
    }

    /**
     * Stricter Probe (Amendment 3):
     * - Requires clen / contentLength.
     * - Probes range at byte ~3,000,000 (bytes=3000000-3001023).
     * - Reads exact byte stream.
     * - Verifies received bytes == requested range size (1024).
     * - Only accepts 200 if the full file was not dumped (receivedBytes == requestedSize).
     */
    fun probeProgressiveStream(streamUrl: String, contentLength: Long?): ResolveResult {
        val total = contentLength ?: streamUrl.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
        if (total == null || total <= 0L) {
            return ResolveResult.TierWide("Progressive stream missing Content-Length/clen")
        }

        val requestedSize = 1024L
        val start: Long
        val end: Long

        if (total > 3_001_024L) {
            start = 3_000_000L
            end = 3_001_023L
        } else {
            start = (total - requestedSize).coerceAtLeast(0L)
            end = total - 1L
        }

        val expectedBytes = (end - start + 1L).toInt()

        return try {
            val req = YoutubeRequests.build(
                url = streamUrl,
                position = start,
                length = expectedBytes.toLong(),
                userAgent = VisionOsProfile.USER_AGENT
            )

            VisionOsPlayerClient.httpClient.newCall(req).execute().use { response ->
                val code = response.code
                if (code != 200 && code != 206) {
                    return ResolveResult.TierWide("HTTP $code from range probe")
                }

                val body = response.body ?: return ResolveResult.TierWide("Empty response body from probe")

                // If 200 OK, reject if server ignored Range and returned the entire file
                if (code == 200) {
                    val fullLen = body.contentLength()
                    if (fullLen > expectedBytes * 2) {
                        return ResolveResult.TierWide("Server ignored Range and returned full file ($fullLen bytes)")
                    }
                }

                // Verify exact byte count received
                val stream = body.byteStream()
                val buffer = ByteArray(expectedBytes)
                var totalRead = 0
                while (totalRead < expectedBytes) {
                    val read = stream.read(buffer, totalRead, expectedBytes - totalRead)
                    if (read == -1) break
                    totalRead += read
                }

                if (totalRead == expectedBytes) {
                    ResolveResult.Success(
                        Resolved.Progressive(
                            url = streamUrl,
                            mime = "audio/webm",
                            format = "Opus (160kbps)",
                            userAgent = VisionOsProfile.USER_AGENT,
                            tierId = TIER_ID
                        )
                    )
                } else {
                    ResolveResult.TierWide("Truncated range probe: expected $expectedBytes bytes, got $totalRead")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResolveResult.Transient("Range probe network exception: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * Tier 2: visionOS HLS Resolver (.m3u8, AAC Backup Tier).
 * Verifies playlist manifest (#EXTM3U) and downloads first audio segment with byte verification.
 */
object VisionOsHlsResolver : StreamResolver {
    const val TIER_ID = "visionos_hls"
    override val id: String = TIER_ID
    private const val TAG = "Tier2HLS"

    override suspend fun resolve(
        videoId: String,
        sharedPlayerJson: JsonObject?,
        cpn: String?
    ): ResolveResult = withContext(Dispatchers.IO) {
        val playerResult = if (sharedPlayerJson != null && cpn != null) {
            PlayerQueryResult.Success(sharedPlayerJson, cpn)
        } else {
            VisionOsPlayerClient.getPlayerResponse(videoId)
        }

        val (root, _) = when (playerResult) {
            is PlayerQueryResult.Success -> Pair(playerResult.json, playerResult.cpn)
            is PlayerQueryResult.VideoSpecific -> return@withContext ResolveResult.VideoSpecific(playerResult.reason)
            is PlayerQueryResult.TierWide -> return@withContext ResolveResult.TierWide(playerResult.reason)
            is PlayerQueryResult.Transient -> return@withContext ResolveResult.Transient(playerResult.reason)
        }

        val playability = root.getAsJsonObject("playabilityStatus")
        val status = playability?.get("status")?.asString
        val reason = playability?.get("reason")?.asString ?: "Video unavailable"

        if (status != "OK") {
            return@withContext when (PlayabilityClassifier.classify(status, reason)) {
                PlayabilityClassifier.Kind.BOT_CHECK -> ResolveResult.TierWide("$status: $reason")
                PlayabilityClassifier.Kind.VIDEO_SPECIFIC -> ResolveResult.VideoSpecific("$status: $reason")
                PlayabilityClassifier.Kind.TIER_WIDE -> ResolveResult.TierWide("$status: $reason")
            }
        }

        val streamingData = root.getAsJsonObject("streamingData")
            ?: return@withContext ResolveResult.VideoSpecific("No streamingData in player response")
        val hlsManifestUrl = streamingData.get("hlsManifestUrl")?.asString?.takeIf { it.isNotBlank() }
            ?: return@withContext ResolveResult.VideoSpecific("No hlsManifestUrl in player response")
        if (!UrlPolicy.isAllowed(hlsManifestUrl, PolicyScope.STREAM)) {
            return@withContext ResolveResult.VideoSpecific("Blocked HLS manifest URL violating STREAM policy")
        }

        val probeResult = probeHlsStream(hlsManifestUrl)
        if (probeResult is ResolveResult.Success) {
            Log.i(TAG, "HLS manifest and segment probe PASSED for $videoId")
            val loudnessDb = try {
                root.getAsJsonObject("playerConfig")
                    ?.getAsJsonObject("audioConfig")
                    ?.get("loudnessDb")
                    ?.asFloat
            } catch (_: Exception) {
                null
            }
            ResolveResult.Success(
                Resolved.Hls(
                    url = hlsManifestUrl,
                    format = "HLS (AAC)",
                    userAgent = VisionOsProfile.USER_AGENT,
                    tierId = id,
                    loudnessDb = loudnessDb
                )
            )
        } else {
            Log.w(TAG, "HLS probe FAILED for $videoId: $probeResult")
            probeResult
        }
    }

    /**
     * Stricter HLS Probe:
     * - Fetches playlist manifest (verifying 200 OK + #EXTM3U)
     * - Locates first audio segment
     * - Downloads segment chunk and verifies bytes received > 0
     */
    fun probeHlsStream(hlsManifestUrl: String): ResolveResult {
        return try {
            // 1. Fetch playlist
            val playlistReq = Request.Builder()
                .url(hlsManifestUrl)
                .header("User-Agent", VisionOsProfile.USER_AGENT)
                .header("Accept", "*/*")
                .get()
                .build()

            val playlistBody: String
            VisionOsPlayerClient.httpClient.newCall(playlistReq).execute().use { res ->
                if (res.code != 200) {
                    return ResolveResult.TierWide("HLS playlist HTTP ${res.code}")
                }
                playlistBody = res.readCapped(1024 * 1024)
            }

            val trimmedPlaylist = playlistBody.trim()
            if (!trimmedPlaylist.startsWith("#EXTM3U")) {
                return ResolveResult.TierWide("HLS playlist does not start with #EXTM3U")
            }

            // 2. Locate first audio segment
            var mediaPlaylistUrl = hlsManifestUrl
            var mediaPlaylistContent = playlistBody

            if (trimmedPlaylist.contains("#EXT-X-STREAM-INF") || trimmedPlaylist.contains("#EXT-X-MEDIA")) {
                val subUri = extractAudioSubPlaylistUri(trimmedPlaylist)
                    ?: return ResolveResult.TierWide("No audio subplaylist found in HLS master manifest")
                mediaPlaylistUrl = resolveUrl(hlsManifestUrl, subUri)
                if (!UrlPolicy.isAllowed(mediaPlaylistUrl, PolicyScope.STREAM)) {
                    return ResolveResult.TierWide("Blocked HLS subplaylist URL violating STREAM policy")
                }

                val subReq = Request.Builder()
                    .url(mediaPlaylistUrl)
                    .header("User-Agent", VisionOsProfile.USER_AGENT)
                    .header("Accept", "*/*")
                    .get()
                    .build()

                VisionOsPlayerClient.httpClient.newCall(subReq).execute().use { subRes ->
                    if (subRes.code != 200) {
                        return ResolveResult.TierWide("HLS subplaylist HTTP ${subRes.code}")
                    }
                    mediaPlaylistContent = subRes.readCapped(1024 * 1024)
                }
            }

            val firstSegmentUri = extractFirstSegmentUri(mediaPlaylistContent)
                ?: return ResolveResult.TierWide("No segment URI found in HLS media playlist")
            val firstSegmentUrl = resolveUrl(mediaPlaylistUrl, firstSegmentUri)
            if (!UrlPolicy.isAllowed(firstSegmentUrl, PolicyScope.STREAM)) {
                return ResolveResult.TierWide("Blocked HLS segment URL violating STREAM policy")
            }

            // 3. Download first segment chunk and verify bytes
            val segmentReq = Request.Builder()
                .url(firstSegmentUrl)
                .header("User-Agent", VisionOsProfile.USER_AGENT)
                .header("Range", "bytes=0-1024")
                .get()
                .build()

            VisionOsPlayerClient.httpClient.newCall(segmentReq).execute().use { segRes ->
                val code = segRes.code
                if (code != 200 && code != 206) {
                    return ResolveResult.TierWide("HLS segment HTTP $code")
                }
                val body = segRes.body ?: return ResolveResult.TierWide("Empty HLS segment body")
                val bytesRead = body.byteStream().readBytes()
                if (bytesRead.isNotEmpty()) {
                    ResolveResult.Success(
                        Resolved.Hls(
                            url = hlsManifestUrl,
                            format = "HLS (AAC)",
                            userAgent = VisionOsProfile.USER_AGENT,
                            tierId = TIER_ID
                        )
                    )
                } else {
                    ResolveResult.TierWide("HLS segment returned 0 bytes")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResolveResult.Transient("HLS probe exception: ${e.javaClass.simpleName}")
        }
    }

    private fun extractAudioSubPlaylistUri(content: String): String? {
        val lines = content.lines().map { it.trim() }
        // Prefer audio-specific track tag
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA") && line.contains("TYPE=AUDIO") && line.contains("URI=\"")) {
                val uri = line.substringAfter("URI=\"").substringBefore("\"")
                if (uri.isNotBlank()) return uri
            }
        }
        // Fall back to first STREAM-INF URI
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val nextLine = lines.getOrNull(i + 1)
                if (!nextLine.isNullOrBlank() && !nextLine.startsWith("#")) {
                    return nextLine
                }
            }
        }
        return lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun extractFirstSegmentUri(content: String): String? {
        val lines = content.lines().map { it.trim() }
        for (line in lines) {
            if (line.isNotEmpty() && !line.startsWith("#")) {
                return line
            }
        }
        return null
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            URI(baseUrl).resolve(relativeOrAbsolute).toString()
        } catch (e: Exception) {
            relativeOrAbsolute
        }
    }
}

/**
 * Shared helper for Android VR InnerTube player calls, visitorData rotation, and caching.
 * Provides 100% independent client execution isolated from visionOS.
 */
internal object AndroidVrPlayerClient {
    private const val TAG = "AndroidVrPlayer"
    private const val GAPIS_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
    private const val YT_VISITOR_URL = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    @androidx.annotation.VisibleForTesting
    internal var httpClient: OkHttpClient = SecureHttp.newBuilder(PolicyScope.STREAM)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class CachedPlayer(val timestampMs: Long, val json: JsonObject, val cpn: String)
    private val playerCache = object : LinkedHashMap<String, CachedPlayer>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPlayer>?): Boolean {
            return size > 20
        }
    }

    @Volatile
    private var inMemoryVisitorData: String? = null

    @Volatile
    private var consecutiveTierWideFailures = 0

    fun getOrFetchVisitorData(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            val persisted = ExtractorPrefs.getAndroidVrVisitorData()
            if (!persisted.isNullOrBlank()) {
                inMemoryVisitorData = persisted
                return persisted
            }
            if (!inMemoryVisitorData.isNullOrBlank()) {
                return inMemoryVisitorData
            }
        }

        return try {
            val headers = AndroidVrProfile.buildHeaders(null)
            val body = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", AndroidVrProfile.buildClientContext(null))
                })
            }
            val request = Request.Builder()
                .url(YT_VISITOR_URL)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val root = gson.fromJson(response.readCapped(128 * 1024), JsonObject::class.java)
                val token = root?.getAsJsonObject("responseContext")?.get("visitorData")?.asString
                if (!token.isNullOrBlank()) {
                    inMemoryVisitorData = token
                    ExtractorPrefs.setAndroidVrVisitorData(token)
                    Log.d(TAG, "Persisted Android VR visitorData successfully")
                    token
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android VR visitor data fetch failed: ${e.javaClass.simpleName}")
            null
        }
    }

    fun recordTierWideFailure() {
        consecutiveTierWideFailures++
        if (consecutiveTierWideFailures >= 3) {
            Log.w(TAG, "Rotating Android VR visitorData after 3 consecutive tier-wide failures")
            ExtractorPrefs.clearAndroidVrVisitorData()
            inMemoryVisitorData = null
            consecutiveTierWideFailures = 0
        }
    }

    fun recordTierSuccess() {
        consecutiveTierWideFailures = 0
    }

    suspend fun getPlayerResponse(
        videoId: String,
        forceFresh: Boolean = false
    ): PlayerQueryResult = withContext(Dispatchers.IO) {
        synchronized(playerCache) {
            val cached = playerCache[videoId]
            val now = System.currentTimeMillis()
            if (!forceFresh && cached != null && (now - cached.timestampMs) < 15_000L) {
                return@withContext PlayerQueryResult.Success(cached.json, cached.cpn)
            }
        }

        val visitorData = getOrFetchVisitorData()
        val result = queryPlayer(videoId, visitorData, retryDepth = 0)

        if (result is PlayerQueryResult.Success) {
            synchronized(playerCache) {
                playerCache[videoId] = CachedPlayer(System.currentTimeMillis(), result.json, result.cpn)
            }
        }
        result
    }

    fun invalidateCache(videoId: String? = null) {
        synchronized(playerCache) {
            if (videoId != null) {
                playerCache.remove(videoId)
            } else {
                playerCache.clear()
            }
        }
    }

    private fun queryPlayer(
        videoId: String,
        visitorData: String?,
        retryDepth: Int = 0
    ): PlayerQueryResult {
        return try {
            val cpn = randomString(16)
            val body = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", AndroidVrProfile.buildClientContext(visitorData))
                })
                addProperty("videoId", videoId)
                addProperty("cpn", cpn)
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }

            val headers = AndroidVrProfile.buildHeaders(visitorData)
            val url = "$GAPIS_PLAYER_URL?prettyPrint=false&t=${randomString(12)}&id=$videoId"
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { res ->
                val code = res.code
                if (code == 403 || code == 429) {
                    recordTierWideFailure()
                    return PlayerQueryResult.TierWide("Player HTTP $code")
                }
                if (!res.isSuccessful) {
                    return PlayerQueryResult.Transient("Player HTTP $code")
                }

                val raw = res.readCapped(8 * 1024 * 1024)
                val root = gson.fromJson(raw, JsonObject::class.java) ?: return PlayerQueryResult.Transient("Malformed JSON from player API")

                val respVisitor = root.getAsJsonObject("responseContext")?.get("visitorData")?.asString
                if (!respVisitor.isNullOrBlank() && ExtractorPrefs.getAndroidVrVisitorData().isNullOrBlank()) {
                    inMemoryVisitorData = respVisitor
                    ExtractorPrefs.setAndroidVrVisitorData(respVisitor)
                }

                val playability = root.getAsJsonObject("playabilityStatus")
                val status = playability?.get("status")?.asString
                val reason = playability?.get("reason")?.asString ?: ""

                if (status != "OK") {
                    Log.w(TAG, "Android VR playabilityStatus $status: $reason")
                    when (PlayabilityClassifier.classify(status, reason)) {
                        PlayabilityClassifier.Kind.BOT_CHECK -> {
                            if (retryDepth < 1 && !respVisitor.isNullOrBlank() && visitorData != respVisitor) {
                                inMemoryVisitorData = respVisitor
                                ExtractorPrefs.setAndroidVrVisitorData(respVisitor)
                                return queryPlayer(videoId, respVisitor, retryDepth = retryDepth + 1)
                            }
                            recordTierWideFailure()
                            return PlayerQueryResult.TierWide("$status: $reason")
                        }
                        PlayabilityClassifier.Kind.VIDEO_SPECIFIC ->
                            return PlayerQueryResult.VideoSpecific("$status: $reason")
                        PlayabilityClassifier.Kind.TIER_WIDE ->
                            return PlayerQueryResult.TierWide("$status: $reason")
                    }
                }

                PlayerQueryResult.Success(root, cpn)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Android VR queryPlayer exception: ${e.javaClass.simpleName}")
            PlayerQueryResult.Transient(e.javaClass.simpleName)
        }
    }

    private fun randomString(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString(length) {
            repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
    }
}

/**
 * Tier 3: Android VR Progressive Audio Resolver (Meta Quest / Oculus Quest).
 * Completely independent client endpoint, User-Agent, context, and visitor token.
 * Extracts Opus 251 > AAC 140 unthrottled direct googlevideo streams with 3MB range probe.
 */
object AndroidVrProgressiveResolver : StreamResolver {
    const val TIER_ID = "android_vr_progressive"
    override val id: String = TIER_ID
    override val requiresSharedPlayer: Boolean = false
    private const val TAG = "Tier3AndroidVr"

    private data class AudioCandidate(
        val url: String,
        val mime: String,
        val label: String,
        val bitrate: Long,
        val contentLength: Long?
    )

    override suspend fun resolve(
        videoId: String,
        sharedPlayerJson: JsonObject?,
        cpn: String?
    ): ResolveResult = withContext(Dispatchers.IO) {
        val playerResult = if (sharedPlayerJson != null && cpn != null) {
            PlayerQueryResult.Success(sharedPlayerJson, cpn)
        } else {
            AndroidVrPlayerClient.getPlayerResponse(videoId)
        }

        val (root, activeCpn) = when (playerResult) {
            is PlayerQueryResult.Success -> Pair(playerResult.json, playerResult.cpn)
            is PlayerQueryResult.VideoSpecific -> return@withContext ResolveResult.VideoSpecific(playerResult.reason)
            is PlayerQueryResult.TierWide -> return@withContext ResolveResult.TierWide(playerResult.reason)
            is PlayerQueryResult.Transient -> return@withContext ResolveResult.Transient(playerResult.reason)
        }

        val playability = root.getAsJsonObject("playabilityStatus")
        val status = playability?.get("status")?.asString
        val reason = playability?.get("reason")?.asString ?: "Video unavailable"

        if (status != "OK") {
            return@withContext when (PlayabilityClassifier.classify(status, reason)) {
                PlayabilityClassifier.Kind.BOT_CHECK -> ResolveResult.TierWide("$status: $reason")
                PlayabilityClassifier.Kind.VIDEO_SPECIFIC -> ResolveResult.VideoSpecific("$status: $reason")
                PlayabilityClassifier.Kind.TIER_WIDE -> ResolveResult.TierWide("$status: $reason")
            }
        }

        val streamingData = root.getAsJsonObject("streamingData")
            ?: return@withContext ResolveResult.TierWide("No streamingData in Android VR response")
        val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
            ?: return@withContext ResolveResult.TierWide("No adaptiveFormats in Android VR response")

        val candidate = selectBestAudioCandidate(adaptiveFormats)
            ?: return@withContext ResolveResult.TierWide("No playable audio formats found in Android VR")

        val finalUrl = candidate.url + "&cpn=" + activeCpn

        val probeResult = probeProgressiveStream(finalUrl, candidate.contentLength)
        if (probeResult is ResolveResult.Success) {
            Log.i(TAG, "Android VR deep range probe PASSED for $videoId (${candidate.label})")
            val loudnessDb = try {
                root.getAsJsonObject("playerConfig")
                    ?.getAsJsonObject("audioConfig")
                    ?.get("loudnessDb")
                    ?.asFloat
            } catch (_: Exception) {
                null
            }
            ResolveResult.Success(
                Resolved.Progressive(
                    url = finalUrl,
                    mime = candidate.mime,
                    format = candidate.label,
                    userAgent = AndroidVrProfile.USER_AGENT,
                    tierId = id,
                    loudnessDb = loudnessDb
                )
            )
        } else {
            Log.w(TAG, "Android VR deep range probe FAILED for $videoId: $probeResult")
            probeResult
        }
    }

    private fun selectBestAudioCandidate(formats: JsonArray): AudioCandidate? {
        val f = AudioFormatPicker.pick(formats) { UrlPolicy.isAllowed(it, PolicyScope.STREAM) }
            ?: return null
        val mime = f.get("mimeType").asString
        val url = f.get("url").asString
        val bitrate = AudioFormatPicker.longOf(f, "bitrate")
        val isOpus = mime.contains("opus")
        val cleanMime = mime.substringBefore(";")
        val label = if (isOpus) "VR Opus (${bitrate / 1000}kbps)" else "VR AAC (${bitrate / 1000}kbps)"
        val len = f.get("contentLength")?.asString?.toLongOrNull()
            ?: f.get("contentLength")?.runCatching { asLong }?.getOrNull()
        return AudioCandidate(url, cleanMime, label, bitrate, len)
    }

    fun probeProgressiveStream(streamUrl: String, contentLength: Long?): ResolveResult {
        val total = contentLength ?: streamUrl.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
        if (total == null || total <= 0L) {
            return ResolveResult.TierWide("Android VR progressive stream missing Content-Length/clen")
        }

        val requestedSize = 1024L
        val start: Long
        val end: Long

        if (total > 3_001_024L) {
            start = 3_000_000L
            end = 3_001_023L
        } else {
            start = (total - requestedSize).coerceAtLeast(0L)
            end = total - 1L
        }

        val expectedBytes = (end - start + 1L).toInt()

        return try {
            val req = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", AndroidVrProfile.USER_AGENT)
                .header("Range", "bytes=$start-$end")
                .get()
                .build()

            AndroidVrPlayerClient.httpClient.newCall(req).execute().use { response ->
                val code = response.code
                if (code != 200 && code != 206) {
                    return ResolveResult.TierWide("HTTP $code from Android VR range probe")
                }

                val body = response.body ?: return ResolveResult.TierWide("Empty response body from Android VR probe")

                if (code == 200) {
                    val fullLen = body.contentLength()
                    if (fullLen > expectedBytes * 2) {
                        return ResolveResult.TierWide("Server ignored Range and returned full file ($fullLen bytes)")
                    }
                }

                val stream = body.byteStream()
                val buffer = ByteArray(expectedBytes)
                var totalRead = 0
                while (totalRead < expectedBytes) {
                    val read = stream.read(buffer, totalRead, expectedBytes - totalRead)
                    if (read == -1) break
                    totalRead += read
                }

                if (totalRead == expectedBytes) {
                    ResolveResult.Success(
                        Resolved.Progressive(
                            url = streamUrl,
                            mime = "audio/webm",
                            format = "Opus (160kbps)",
                            userAgent = AndroidVrProfile.USER_AGENT,
                            tierId = TIER_ID
                        )
                    )
                } else {
                    ResolveResult.TierWide("Truncated Android VR range probe: expected $expectedBytes bytes, got $totalRead")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResolveResult.Transient("Android VR range probe network exception: ${e.javaClass.simpleName}")
        }
    }
}


data class VideoFallbackPair(
    val videoUrl: String,
    val audioUrl: String?,
    val formatLabel: String,
    val userAgent: String,
    val client: String,
    val loudnessDb: Float?
)

internal suspend fun resolveAdaptiveOrProgressiveVideo(
    videoId: String,
    maxHeight: Int = 1080,
    forceFresh: Boolean = false
): VideoFallbackPair? = withContext(Dispatchers.IO) {
    val result = VisionOsPlayerClient.getPlayerResponse(videoId, forceFresh = forceFresh)
    val root = (result as? PlayerQueryResult.Success)?.json ?: return@withContext null
    val streamingData = root.getAsJsonObject("streamingData") ?: return@withContext null
    val loudnessDb = try {
        root.getAsJsonObject("playerConfig")?.getAsJsonObject("audioConfig")?.get("loudnessDb")?.asFloat
    } catch (_: Exception) { null }

    // 1. Adaptive Video (<= maxHeight) + Opus 160kbps Audio via AudioFormatPicker
    val adaptive = streamingData.getAsJsonArray("adaptiveFormats")
    if (adaptive != null) {
        var bestVideoUrl: String? = null
        var bestVideoHeight = 0
        var bestVideoBitrate = 0
        var bestVideoRank = -1

        var fallbackAvcUrl: String? = null
        var fallbackAvcHeight = 0
        var fallbackAvcBitrate = 0

        for (el in adaptive) {
            val obj = el.asJsonObject
            val mime = obj.get("mimeType")?.asString ?: continue
            if (!mime.startsWith("video/")) continue
            val url = obj.get("url")?.asString?.takeIf { it.isNotBlank() } ?: continue
            if (!UrlPolicy.isAllowed(url, PolicyScope.STREAM)) continue
            val h = obj.get("height")?.asInt ?: 0
            val w = obj.get("width")?.asInt?.takeIf { it > 0 } ?: (h * 16 / 9)
            val br = obj.get("bitrate")?.asInt ?: 0
            if (h !in 1..maxHeight) continue

            val rank = VideoCodecPolicy.rank(mime)
            if (rank == 2 && (h > fallbackAvcHeight || (h == fallbackAvcHeight && br > fallbackAvcBitrate))) {
                fallbackAvcHeight = h
                fallbackAvcBitrate = br
                fallbackAvcUrl = url
            }

            // Prefer streams verified as decodable by MediaCodecList
            if (!VideoCodecPolicy.canDecode(mime, w, h)) continue
            val better = (h > bestVideoHeight) ||
                (h == bestVideoHeight && rank > bestVideoRank) ||
                (h == bestVideoHeight && rank == bestVideoRank && br > bestVideoBitrate)
            if (better) {
                bestVideoHeight = h
                bestVideoRank = rank
                bestVideoBitrate = br
                bestVideoUrl = url
            }
        }
        val chosenVideoUrl = bestVideoUrl ?: fallbackAvcUrl
        val chosenVideoHeight = if (bestVideoUrl != null) bestVideoHeight else fallbackAvcHeight
        val audioCandidate = VisionOsProgressiveResolver.selectBestAudioCandidate(adaptive)
        if (chosenVideoUrl != null && audioCandidate != null && UrlPolicy.isAllowed(audioCandidate.url, PolicyScope.STREAM)) {
            return@withContext VideoFallbackPair(
                videoUrl = chosenVideoUrl,
                audioUrl = audioCandidate.url,
                formatLabel = "${chosenVideoHeight}p • ${audioCandidate.label}",
                userAgent = VisionOsProfile.USER_AGENT,
                client = VisionOsProfile.CLIENT_NAME,
                loudnessDb = loudnessDb
            )
        }
    }

    // 2. Fallback to muxed progressive formats (respecting maxHeight when possible)
    val formats = streamingData.getAsJsonArray("formats")
    if (formats != null) {
        var bestUrl: String? = null
        var bestH = 0
        for (el in formats) {
            val obj = el.asJsonObject
            val mime = obj.get("mimeType")?.asString ?: continue
            if (!mime.startsWith("video/")) continue
            val url = obj.get("url")?.asString?.takeIf { it.isNotBlank() } ?: continue
            if (!UrlPolicy.isAllowed(url, PolicyScope.STREAM)) continue
            val h = obj.get("height")?.asInt ?: 360
            if (h <= maxHeight && h >= bestH) {
                bestH = h
                bestUrl = url
            } else if (bestUrl == null) {
                bestH = h
                bestUrl = url
            }
        }
        if (bestUrl != null) {
            return@withContext VideoFallbackPair(
                videoUrl = bestUrl,
                audioUrl = null,
                formatLabel = "Video (${bestH}p)",
                userAgent = VisionOsProfile.USER_AGENT,
                client = VisionOsProfile.CLIENT_NAME,
                loudnessDb = loudnessDb
            )
        }
    }
    null
}


/** Which video codecs this phone can decode, and what to avoid after a decoder failure. */
object VideoCodecPolicy {
    /** Set after a video decoder failure: only H.264 from then on. */
    @Volatile var avcOnly = false
    private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun family(mime: String): String? {
        val m = mime.lowercase()
        return when {
            m.contains("avc1") -> "video/avc"
            m.contains("vp09") || m.contains("vp9") -> "video/x-vnd.on2.vp9"
            m.contains("av01") -> "video/av01"
            else -> null
        }
    }

    /** H.264 first (hardware everywhere, easy on battery), then VP9, then AV1. */
    fun rank(mime: String): Int = when (family(mime)) {
        "video/avc" -> 2
        "video/x-vnd.on2.vp9" -> 1
        else -> 0
    }

    fun canDecode(mime: String, width: Int, height: Int): Boolean {
        val fam = family(mime) ?: return false
        if (avcOnly && fam != "video/avc") return false
        return sizeCache.getOrPut("$fam:$width:$height") {
            try {
                android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                    !ci.isEncoder && ci.supportedTypes.any { it.equals(fam, ignoreCase = true) } &&
                        try {
                            ci.getCapabilitiesForType(fam).videoCapabilities?.isSizeSupported(width, height) == true
                        } catch (_: Exception) { false }
                }
            } catch (_: Exception) {
                fam == "video/avc"
            }
        }
    }
}


/**
 * Tier 3: Independent iOS HLS Resolver (clientName = IOS, clientId = 5).
 * Used only when both visionOS Progressive and visionOS HLS fail.
 */
object IosHlsResolver : StreamResolver {
    const val TIER_ID = "ios_hls"
    const val USER_AGENT = "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)"
    override val id: String = TIER_ID
    override val isProgressive: Boolean = false
    override val requiresSharedPlayer: Boolean = false

    override suspend fun resolve(
        videoId: String,
        sharedPlayerJson: JsonObject?,
        cpn: String?
    ): ResolveResult = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", JsonObject().apply {
                        addProperty("clientName", "IOS")
                        addProperty("clientVersion", "20.03.02")
                        addProperty("deviceMake", "Apple")
                        addProperty("deviceModel", "iPhone16,2")
                        addProperty("osName", "iPhone")
                        addProperty("osVersion", "18.2.0.22C152")
                        addProperty("hl", VisionOsProfile.getDeviceHl())
                        addProperty("gl", VisionOsProfile.getDeviceGl())
                    })
                })
                addProperty("videoId", videoId)
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", USER_AGENT)
                .header("X-YouTube-Client-Name", "5")
                .header("X-YouTube-Client-Version", "20.03.02")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val body = VisionOsPlayerClient.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ResolveResult.TierWide("iOS HTTP ${resp.code}")
                }
                resp.body?.string() ?: return@withContext ResolveResult.Transient("Empty iOS response")
            }

            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            val playability = root.getAsJsonObject("playabilityStatus")
            val status = playability?.get("status")?.asString
            val reason = playability?.get("reason")?.asString ?: "Video unavailable"
            if (status != "OK") {
                return@withContext when (PlayabilityClassifier.classify(status, reason)) {
                    PlayabilityClassifier.Kind.BOT_CHECK -> ResolveResult.TierWide("$status: $reason")
                    PlayabilityClassifier.Kind.VIDEO_SPECIFIC -> ResolveResult.VideoSpecific("$status: $reason")
                    PlayabilityClassifier.Kind.TIER_WIDE -> ResolveResult.TierWide("$status: $reason")
                }
            }

            val hlsUrl = root.getAsJsonObject("streamingData")
                ?.get("hlsManifestUrl")?.asString?.takeIf { it.isNotBlank() }
                ?: return@withContext ResolveResult.VideoSpecific("No hlsManifestUrl in iOS response")
            if (!UrlPolicy.isAllowed(hlsUrl, PolicyScope.STREAM)) {
                return@withContext ResolveResult.VideoSpecific("Blocked iOS HLS URL")
            }

            val manifestReq = Request.Builder()
                .url(hlsUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val ok = VisionOsPlayerClient.httpClient.newCall(manifestReq).execute().use { r ->
                r.code == 200 && (r.body?.string()?.contains("#EXTM3U") == true)
            }
            if (!ok) {
                return@withContext ResolveResult.TierWide("iOS HLS manifest probe failed")
            }

            val loudnessDb = try {
                root.getAsJsonObject("playerConfig")
                    ?.getAsJsonObject("audioConfig")
                    ?.get("loudnessDb")
                    ?.asFloat
            } catch (_: Exception) {
                null
            }

            ResolveResult.Success(
                Resolved.Hls(
                    url = hlsUrl,
                    format = "iOS HLS (AAC)",
                    userAgent = USER_AGENT,
                    tierId = id,
                    loudnessDb = loudnessDb
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResolveResult.Transient(e.javaClass.simpleName)
        }
    }
}


/**
 * Tier 3: Authenticated Web Safari HLS Resolver (active when user signs in via Settings).
 * Resolves LOGIN_REQUIRED / bot-gated tracks using the user's encrypted YouTube cookies + SAPISIDHASH.
 */
object WebSafariAuthHlsResolver : StreamResolver {
    const val TIER_ID = "web_safari_auth_hls"
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15"
    override val id: String = TIER_ID
    override val isProgressive: Boolean = false
    override val requiresSharedPlayer: Boolean = false

    private fun extractCookieValue(cookie: String, name: String): String? {
        return cookie.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildSapisidHash(cookie: String, origin: String = "https://www.youtube.com"): String? {
        val sapisid = extractCookieValue(cookie, "__Secure-3PAPISID")
            ?: extractCookieValue(cookie, "SAPISID")
            ?: return null
        val ts = System.currentTimeMillis() / 1000L
        val input = "$ts $sapisid $origin"
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_${hex}"
    }

    override suspend fun resolve(
        videoId: String,
        sharedPlayerJson: JsonObject?,
        cpn: String?
    ): ResolveResult = withContext(Dispatchers.IO) {
        val cookie = ExtractorPrefs.getYoutubeCookie()
            ?: return@withContext ResolveResult.VideoSpecific("Sign in via Settings to play login-required tracks")

        try {
            val payload = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", JsonObject().apply {
                        addProperty("clientName", "WEB")
                        addProperty("clientVersion", "2.20260114.08.00")
                        addProperty("hl", VisionOsProfile.getDeviceHl())
                        addProperty("gl", VisionOsProfile.getDeviceGl())
                    })
                })
                addProperty("videoId", videoId)
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", USER_AGENT)
                .header("Cookie", cookie)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=$videoId")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", "2.20260114.08.00")
                .header("X-Goog-AuthUser", "0")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            buildSapisidHash(cookie)?.let { reqBuilder.header("Authorization", it) }

            val authClient = VisionOsPlayerClient.httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val body = authClient.newCall(reqBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    in 300..399 -> return@withContext ResolveResult.TierWide("Auth challenge redirect (${resp.code})")
                    401, 403 -> {
                        ExtractorPrefs.markYoutubeCookieExpired(true)
                        return@withContext ResolveResult.TierWide("Auth session expired (HTTP ${resp.code})")
                    }
                    429 -> return@withContext ResolveResult.TierWide("Auth rate limited (HTTP 429)")
                    in 500..599 -> return@withContext ResolveResult.Transient("Auth server error (HTTP ${resp.code})")
                    else -> if (!resp.isSuccessful) {
                        return@withContext ResolveResult.Transient("Auth Web HTTP ${resp.code}")
                    }
                }
                resp.body?.string() ?: return@withContext ResolveResult.Transient("Empty Auth Web response")
            }

            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            val playability = root.getAsJsonObject("playabilityStatus")
            val status = playability?.get("status")?.asString
            val reason = playability?.get("reason")?.asString ?: "Video unavailable"
            if (status != "OK") {
                if (status == "LOGIN_REQUIRED") {
                    ExtractorPrefs.markYoutubeCookieExpired(true)
                    return@withContext ResolveResult.VideoSpecific("LOGIN_REQUIRED: Session expired")
                }
                return@withContext ResolveResult.VideoSpecific("$status: $reason")
            }

            val hlsUrl = root.getAsJsonObject("streamingData")
                ?.get("hlsManifestUrl")?.asString?.takeIf { it.isNotBlank() }
                ?: return@withContext ResolveResult.VideoSpecific("No hlsManifestUrl in Auth Web response")
            if (!UrlPolicy.isAllowed(hlsUrl, PolicyScope.STREAM)) {
                return@withContext ResolveResult.VideoSpecific("Blocked Auth Web HLS URL")
            }

            val manifestReq = Request.Builder()
                .url(hlsUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val ok = VisionOsPlayerClient.httpClient.newCall(manifestReq).execute().use { r ->
                r.code == 200 && (r.body?.string()?.contains("#EXTM3U") == true)
            }
            if (!ok) {
                return@withContext ResolveResult.TierWide("Auth Web HLS probe failed")
            }

            val loudnessDb = try {
                root.getAsJsonObject("playerConfig")
                    ?.getAsJsonObject("audioConfig")
                    ?.get("loudnessDb")
                    ?.asFloat
            } catch (_: Exception) {
                null
            }

            ResolveResult.Success(
                Resolved.Hls(
                    url = hlsUrl,
                    format = "Auth HLS (AAC)",
                    userAgent = USER_AGENT,
                    tierId = id,
                    loudnessDb = loudnessDb
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResolveResult.Transient(e.javaClass.simpleName)
        }
    }
}
