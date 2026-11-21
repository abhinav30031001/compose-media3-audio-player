package com.ytmusic.clone.data.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ytmusic.clone.data.local.ExtractorPrefs
import com.ytmusic.clone.data.model.LyricsResult
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.data.model.ThumbnailUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * YouTube Music on-device extractor.
 *
 *  - Streams: Tiered Resolver Chain (visionOS Progressive Opus/AAC -> visionOS HLS AAC Backup).
 *    All client parameters and User-Agent are sourced from VisionOsProfile.
 *  - Search: YouTube Music web remix search endpoint.
 */
object YouTubeExtractor {
    private const val TAG = "YTExtractor"

    // Tier IDs matching resolvers
    const val CLIENT_VISIONOS = VisionOsProgressiveResolver.TIER_ID
    const val CLIENT_VISIONOS_HLS = VisionOsHlsResolver.TIER_ID
    const val CLIENT_ANDROID_VR = AndroidVrProgressiveResolver.TIER_ID
    val DEFAULT_ORDER = listOf(CLIENT_VISIONOS, CLIENT_VISIONOS_HLS)

    private val client = SecureHttp.newBuilder(PolicyScope.API)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private const val MUSIC_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val SONGS_PARAMS = "Eg-KAQwIARAAGAAgACgAMABqChAEEAUQAxAKEAk%3D"

    // ------------------------------------------------------------------
    //  Diagnostics + Memory tracking
    // ------------------------------------------------------------------

    @Volatile
    var lastDiagnostics: String = ""
        private set

    fun addDiagnostic(msg: String) = note(msg)

    @Volatile
    private var lastWorkingClient: String? = null

    fun rememberWorkingClient(label: String) {
        lastWorkingClient = label
    }

    fun forgetWorkingClient(label: String) {
        if (lastWorkingClient == label) lastWorkingClient = null
    }

    private fun note(msg: String) {
        val clean = DiagnosticSanitizer.sanitize(msg)
        Log.w(TAG, clean)
        lastDiagnostics = if (lastDiagnostics.isEmpty()) clean else lastDiagnostics + "\n" + clean
    }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parseObject(text: String?): JsonObject? = try {
        if (text.isNullOrEmpty()) null else gson.fromJson(text, JsonObject::class.java)
    } catch (e: Exception) {
        null
    }

    private fun postJson(url: String, body: JsonObject, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url).post(gson.toJson(body).toRequestBody(jsonMediaType))
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                note("HTTP ${response.code} from ${url.substringBefore('?').substringAfter("//").take(40)}")
                return null
            }
            return response.readCapped(8 * 1024 * 1024)
        }
    }

    private fun baseContext(clientJson: JsonObject): JsonObject = JsonObject().apply {
        add("client", clientJson)
        add("request", JsonObject().apply {
            add("internalExperimentFlags", JsonArray())
            addProperty("useSsl", true)
        })
        add("user", JsonObject().apply { addProperty("lockedSafetyMode", false) })
    }

    // ------------------------------------------------------------------
    //  Search (YouTube Music)
    // ------------------------------------------------------------------

    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        try {
            val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&client=firefox&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", YoutubeRequests.FIREFOX_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.readCapped(128 * 1024)
                val array = gson.fromJson(body, JsonArray::class.java)
                if (array != null && array.size() > 1 && array[1].isJsonArray) {
                    val list = mutableListOf<String>()
                    for (el in array[1].asJsonArray) {
                        if (el.isJsonPrimitive) list.add(el.asString)
                    }
                    list
                } else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        try {
            // 1. Primary: YouTube Music with Songs filter (official tracks only)
            val songs = searchMusic(trimmed, withFilter = true)
            if (songs.isNotEmpty()) return@withContext songs

            // 2. Fallback: YouTube Music unfiltered (captures artist shelves, official singles)
            val unfiltered = searchMusic(trimmed, withFilter = false)
            if (unfiltered.isNotEmpty()) return@withContext unfiltered

            // Pure music policy: Regular YouTube videos are only played when user pastes exact link
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "search failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    //  Lyrics (YouTube Music "Lyrics" tab; plain text, only when YouTube provides it) - v7.20
    // ------------------------------------------------------------------

    private const val MUSIC_NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val MUSIC_BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"

    private fun remixClientJson(remixVer: String) = JsonObject().apply {
        addProperty("clientName", "WEB_REMIX")
        addProperty("clientVersion", remixVer)
        addProperty("hl", VisionOsProfile.getDeviceHl())
        addProperty("gl", VisionOsProfile.getDeviceGl())
        addProperty("platform", "DESKTOP")
        addProperty("utcOffsetMinutes", VisionOsProfile.getDeviceUtcOffsetMinutes())
    }

    private fun remixHeaders(remixVer: String) = mapOf(
        "User-Agent" to YoutubeRequests.FIREFOX_UA,
        "Origin" to "https://music.youtube.com",
        "Referer" to "https://music.youtube.com/",
        "X-YouTube-Client-Name" to "67",
        "X-YouTube-Client-Version" to remixVer
    )

    suspend fun fetchLyrics(videoId: String, durationMs: Long = 0L): LyricsResult = withContext(Dispatchers.IO) {
        try {
            val remixVer = ExtractorPrefs.getRemixVersion()
            val headers = remixHeaders(remixVer)

            val nextBody = JsonObject().apply {
                add("context", baseContext(remixClientJson(remixVer)))
                addProperty("videoId", videoId)
                addProperty("isAudioOnly", true)
            }
            val nextText = postJson(MUSIC_NEXT_URL, nextBody, headers)
                ?: return@withContext LyricsResult.Error("Could not reach YouTube Music")
            val nextJson = parseObject(nextText)
                ?: return@withContext LyricsResult.Error("Unexpected response")
            val browseId = LyricsParser.findLyricsBrowseId(nextJson)
                ?: return@withContext LyricsResult.NotAvailable

            val browseBody = JsonObject().apply {
                add("context", baseContext(remixClientJson(remixVer)))
                addProperty("browseId", browseId)
            }
            val browseText = postJson(MUSIC_BROWSE_URL, browseBody, headers)
                ?: return@withContext LyricsResult.Error("Could not load lyrics")
            val browseJson = parseObject(browseText)
                ?: return@withContext LyricsResult.Error("Unexpected response")
            val lyrics = LyricsParser.extractLyrics(browseJson)
                ?: return@withContext LyricsResult.NotAvailable
            val parsedLines = LyricsUtils.parse(lyrics.first, durationMs)
            val hasLrc = parsedLines.any { it.isSynced }
            LyricsResult.Found(lyrics.first, lyrics.second, parsedLines, isSynced = hasLrc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lyrics failed: ${e.javaClass.simpleName}")
            LyricsResult.Error("Network error")
        }
    }

    suspend fun fetchSimilarSongs(videoId: String, artistHint: String? = null): List<Song> = withContext(Dispatchers.IO) {
        try {
            val remixVer = ExtractorPrefs.getRemixVersion()
            val headers = remixHeaders(remixVer)

            val nextBody = JsonObject().apply {
                add("context", baseContext(remixClientJson(remixVer)))
                addProperty("videoId", videoId)
                addProperty("isAudioOnly", true)
            }
            val nextText = postJson(MUSIC_NEXT_URL, nextBody, headers)
            val songs = mutableListOf<Song>()
            val seen = HashSet<String>()
            seen.add(videoId)

            if (nextText != null) {
                val nextJson = parseObject(nextText)
                if (nextJson != null) {
                    val panelRenderers = mutableListOf<JsonObject>()
                    collectPanelRenderers(nextJson, panelRenderers)

                    for (r in panelRenderers) {
                        val id = r.get("videoId")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                        if (!seen.add(id)) continue

                        val title = runsToString(r.get("title")).trim()
                        if (title.isEmpty()) continue

                        val artistRuns = runsToString(r.get("longBylineText") ?: r.get("shortBylineText"))
                        val artist = artistRuns.split("•").firstOrNull()?.trim()?.ifEmpty { "Unknown Artist" } ?: "Unknown Artist"

                        val durationText = runsToString(r.get("lengthText")).trim()
                        val duration = parseDuration(durationText) ?: 0L

                        val rawThumb = r.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
                            ?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("url")?.asString
                            ?: "https://i.ytimg.com/vi/$id/sddefault.jpg"
                        val thumb = ThumbnailUtils.upgrade(rawThumb)

                        songs.add(Song(id = id, title = title, artist = artist, durationSeconds = duration, thumbnailUrl = thumb))
                    }
                }
            }

            // Fallback: if YouTube next queue returned fewer than 3 items, query artist radio / top tracks
            if (songs.size < 3 && !artistHint.isNullOrBlank()) {
                val searchRecs = searchMusic(artistHint).filter { it.id != videoId && seen.add(it.id) }
                songs.addAll(searchRecs)
            }

            songs
        } catch (e: Exception) {
            Log.w(TAG, "fetchSimilarSongs failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun runsToString(el: JsonElement?): String {
        val runs = el?.takeIf { it.isJsonObject }?.asJsonObject
            ?.getAsJsonArray("runs") ?: return ""
        val sb = StringBuilder()
        for (run in runs) {
            val t = run.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            if (t != null) sb.append(t)
        }
        return sb.toString()
    }

    private fun collectPanelRenderers(el: JsonElement, out: MutableList<JsonObject>) {
        when {
            el.isJsonObject -> for ((key, value) in el.asJsonObject.entrySet()) {
                if (key == "playlistPanelVideoRenderer" && value.isJsonObject) out.add(value.asJsonObject)
                else collectPanelRenderers(value, out)
            }
            el.isJsonArray -> el.asJsonArray.forEach { collectPanelRenderers(it, out) }
        }
    }

    private fun searchMusic(query: String, withFilter: Boolean = true): List<Song> {
        val remixVer = ExtractorPrefs.getRemixVersion()
        val visitorData = VisionOsPlayerClient.getOrFetchVisitorData()
        val clientJson = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", remixVer)
            if (!visitorData.isNullOrBlank()) {
                addProperty("visitorData", visitorData)
            }
            addProperty("hl", VisionOsProfile.getDeviceHl())
            addProperty("gl", VisionOsProfile.getDeviceGl())
            addProperty("platform", "DESKTOP")
            addProperty("utcOffsetMinutes", VisionOsProfile.getDeviceUtcOffsetMinutes())
        }
        val body = JsonObject().apply {
            add("context", baseContext(clientJson))
            addProperty("query", query)
            if (withFilter) {
                addProperty("params", SONGS_PARAMS)
            }
        }
        val headers = buildMap {
            put("User-Agent", YoutubeRequests.FIREFOX_UA)
            put("Origin", "https://music.youtube.com")
            put("Referer", "https://music.youtube.com/")
            put("X-YouTube-Client-Name", "67")
            put("X-YouTube-Client-Version", remixVer)
            if (!visitorData.isNullOrBlank()) {
                put("X-Goog-Visitor-Id", visitorData)
            }
        }
        val json = parseObject(postJson(MUSIC_SEARCH_URL, body, headers)) ?: return emptyList()

        val renderers = mutableListOf<JsonObject>()
        collectRenderers(json, renderers)

        val seen = HashSet<String>()
        val candidates = mutableListOf<Pair<Song, Int>>()
        for (r in renderers) {
            if (r.str("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") continue
            val id = extractVideoIdFromRenderer(r) ?: continue
            if (!seen.add(id)) continue

            val title = flexRuns(r, 0).firstOrNull() ?: continue
            val details = flexRuns(r, 1).map { it.trim() }.filter { it.isNotEmpty() && it != "•" }
            val artist = details.firstOrNull { it.lowercase() !in setOf("song", "video", "single", "ep", "album") }
                ?: "Unknown Artist"
            val duration = details.lastOrNull()?.let { parseDuration(it) } ?: 0L

            if (isUnofficialVideoNoise(title, duration, query)) continue

            val rawThumb = r.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")
                ?.arr("thumbnails")?.toList()?.lastOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.str("url")
                ?: "https://i.ytimg.com/vi/$id/sddefault.jpg"
            val thumb = ThumbnailUtils.upgrade(rawThumb)

            val videoType = extractMusicVideoType(r)
            val score = when (videoType) {
                "MUSIC_VIDEO_TYPE_ATV" -> 100 // Official Studio Audio Track (Album version)
                "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC_VIDEO" -> 80 // Official Music Video
                null -> 50
                "MUSIC_VIDEO_TYPE_UGC", "MUSIC_VIDEO_TYPE_USER_GENERATED" -> 10
                else -> 40
            }

            val song = Song(id = id, title = title, artist = artist, durationSeconds = duration, thumbnailUrl = thumb)
            candidates.add(song to score)
        }
        // Keep YouTube Music's own relevance order (same as the official app); only push
        // user-uploaded copies below official audio/video. sortedBy is stable.
        return candidates.sortedBy { if (it.second <= 10) 1 else 0 }.map { it.first }
    }

    private fun extractMusicVideoType(r: JsonObject): String? {
        return r.obj("navigationEndpoint")
            ?.obj("watchEndpoint")
            ?.obj("watchEndpointMusicSupportedConfigs")
            ?.obj("watchEndpointMusicConfig")
            ?.str("musicVideoType")
            ?: r.obj("overlay")
                ?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")
                ?.obj("musicPlayButtonRenderer")
                ?.obj("playNavigationEndpoint")
                ?.obj("watchEndpoint")
                ?.obj("watchEndpointMusicSupportedConfigs")
                ?.obj("watchEndpointMusicConfig")
                ?.str("musicVideoType")
    }

    private fun isUnofficialVideoNoise(title: String, durationSec: Long, query: String): Boolean {
        val lowerQ = query.lowercase()
        val lowerT = title.lowercase()

        // Filter extreme durations: songs are not 1-hour loops or 15-second status clips
        if (durationSec > 900L && !lowerQ.contains("hour") && !lowerQ.contains("mix") && !lowerQ.contains("compilation")) return true
        if (durationSec in 1L..34L && !lowerQ.contains("intro") && !lowerQ.contains("skit")) return true

        val noisePatterns = listOf(
            "1 hour loop", "10 hour", "1 hr loop", "1 hour version",
            "slowed + reverb", "slowed and reverb", "slowed & reverb",
            "bass boosted", "bassboosted", "8d audio",
            "reaction", "reacts to", "review",
            "tiktok version", "sped up", "nightcore",
            "full episode", "status video", "whatsapp status"
        )

        for (p in noisePatterns) {
            if (lowerT.contains(p) && !lowerQ.contains(p)) {
                return true
            }
        }
        return false
    }

    private fun extractVideoIdFromRenderer(r: JsonObject): String? {
        r.obj("playlistItemData")?.str("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        r.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        r.obj("overlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")
            ?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")
            ?.obj("watchEndpoint")?.str("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        val flex = r.arr("flexColumns")?.toList()
        if (flex != null) {
            for (col in flex) {
                val runs = col.takeIf { it.isJsonObject }?.asJsonObject
                    ?.obj("musicResponsiveListItemFlexColumnRenderer")
                    ?.obj("text")?.arr("runs")?.toList() ?: continue
                for (run in runs) {
                    val id = run.takeIf { it.isJsonObject }?.asJsonObject
                        ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId")
                    if (!id.isNullOrBlank()) return id
                }
            }
        }
        return null
    }

    private fun searchYouTubeUniversal(query: String): List<Song> {
        val visitorData = VisionOsPlayerClient.getOrFetchVisitorData()
        val clientJson = JsonObject().apply {
            addProperty("clientName", "WEB")
            addProperty("clientVersion", "2.20240101.00.00")
            addProperty("hl", VisionOsProfile.getDeviceHl())
            addProperty("gl", VisionOsProfile.getDeviceGl())
            if (!visitorData.isNullOrBlank()) {
                addProperty("visitorData", visitorData)
            }
        }
        val body = JsonObject().apply {
            add("context", baseContext(clientJson))
            addProperty("query", query)
            addProperty("params", "EgIQAQ%3D%3D") // Video filter
        }
        val headers = buildMap {
            put("User-Agent", YoutubeRequests.FIREFOX_UA)
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
            if (!visitorData.isNullOrBlank()) {
                put("X-Goog-Visitor-Id", visitorData)
            }
        }
        val json = parseObject(postJson("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body, headers)) ?: return emptyList()
        val renderers = mutableListOf<JsonObject>()
        collectVideoRenderers(json, renderers)
        collectNamedRenderers(json, "gridVideoRenderer", renderers)
        collectNamedRenderers(json, "compactVideoRenderer", renderers)

        val seen = HashSet<String>()
        val songs = mutableListOf<Song>()
        for (vr in renderers) {
            val id = vr.str("videoId") ?: continue
            if (!seen.add(id)) continue

            val title = vr.obj("title")?.arr("runs")?.toList()?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.str("text")
                ?: vr.obj("title")?.str("simpleText") ?: continue
            val artist = vr.obj("ownerText")?.arr("runs")?.toList()?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.str("text") ?: "YouTube"
            val durationText = vr.obj("lengthText")?.str("simpleText")
            val duration = durationText?.let { parseDuration(it) } ?: 0L
            val rawThumb = vr.obj("thumbnail")?.arr("thumbnails")?.toList()?.lastOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.str("url")
                ?: "https://i.ytimg.com/vi/$id/sddefault.jpg"
            val thumb = ThumbnailUtils.upgrade(rawThumb)

            songs.add(Song(id = id, title = title, artist = artist, durationSeconds = duration, thumbnailUrl = thumb))
        }
        return songs
    }

    private fun collectVideoRenderers(el: JsonElement, out: MutableList<JsonObject>) {
        when {
            el.isJsonObject -> for ((key, value) in el.asJsonObject.entrySet()) {
                if (key == "videoRenderer" && value.isJsonObject) out.add(value.asJsonObject)
                else collectVideoRenderers(value, out)
            }
            el.isJsonArray -> el.asJsonArray.forEach { collectVideoRenderers(it, out) }
        }
    }

    private fun collectRenderers(el: JsonElement, out: MutableList<JsonObject>) {
        when {
            el.isJsonObject -> for ((key, value) in el.asJsonObject.entrySet()) {
                if (key == "musicResponsiveListItemRenderer" && value.isJsonObject) out.add(value.asJsonObject)
                else collectRenderers(value, out)
            }
            el.isJsonArray -> el.asJsonArray.forEach { collectRenderers(it, out) }
        }
    }

    private fun flexRuns(renderer: JsonObject, index: Int): List<String> {
        val column = renderer.arr("flexColumns")?.toList()?.getOrNull(index)
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val runs = column.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.arr("runs")
            ?: return emptyList()
        return runs.mapNotNull { (it as? JsonObject)?.str("text") }
    }

    private fun parseDuration(text: String): Long? {
        if (!Regex("^\\d{1,2}(:\\d{2}){1,2}$").matches(text)) return null
        return text.split(":").fold(0L) { acc, part -> acc * 60 + part.toLong() }
    }

    // ------------------------------------------------------------------
    //  Stream resolution
    // ------------------------------------------------------------------

    data class ResolvedStream(
        val url: String,
        val format: String,
        val userAgent: String,
        val client: String,
        val loudnessDb: Float? = null,
        val secondaryAudioUrl: String? = null
    )

    /**
     * Resolves audio stream URL using the Tiered Resolver Chain:
     * Tier 1: visionOS Progressive (Opus 251 > AAC 140) with 3MB range probe
     * Tier 2: visionOS HLS (.m3u8, AAC Backup Tier) with playlist + segment probe
     */
    suspend fun resolveAudioStream(
        videoId: String,
        skipClients: Set<String> = emptySet(),
        keepDiagnostics: Boolean = false,
        targetClient: String? = null,
        forceFresh: Boolean = false,
        recordBreaker: Boolean = true
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        if (!keepDiagnostics) {
            lastDiagnostics = ""
        }

        val resolved: Resolved? = when (targetClient) {
            CLIENT_VISIONOS -> {
                val r = VisionOsProgressiveResolver.resolve(videoId)
                (r as? ResolveResult.Success)?.resolved
            }
            CLIENT_VISIONOS_HLS -> {
                val r = VisionOsHlsResolver.resolve(videoId)
                (r as? ResolveResult.Success)?.resolved
            }
            CLIENT_ANDROID_VR -> {
                val r = AndroidVrProgressiveResolver.resolve(videoId)
                (r as? ResolveResult.Success)?.resolved
            }
            else -> {
                DefaultResolverChain.instance.resolve(
                    videoId = videoId,
                    skipFailed = skipClients.isNotEmpty(),
                    recordBreaker = recordBreaker,
                    forceFreshPlayer = forceFresh,
                    excludedTierIds = skipClients
                )
            }
        }

        if (resolved != null) {
            ResolvedStream(
                url = resolved.url,
                format = resolved.format,
                userAgent = resolved.userAgent,
                client = resolved.tierId,
                loudnessDb = resolved.loudnessDb
            )
        } else {
            null
        }
    }
    suspend fun resolveVideoStream(
        videoId: String,
        maxHeight: Int = 1080,
        forceFresh: Boolean = false
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        // Tier 1: Adaptive Video (<= maxHeight) + Opus 160kbps Audio via MergingMediaSource
        // (Matches Song mode's exact audio quality without HLS AAC/DRC downgrade)
        val adaptive = resolveAdaptiveOrProgressiveVideo(videoId, maxHeight, forceFresh)
        if (adaptive != null && !adaptive.audioUrl.isNullOrBlank()) {
            return@withContext ResolvedStream(
                url = adaptive.videoUrl,
                format = adaptive.formatLabel,
                userAgent = adaptive.userAgent,
                client = adaptive.client,
                loudnessDb = adaptive.loudnessDb,
                secondaryAudioUrl = adaptive.audioUrl
            )
        }
        // Tier 2: VISIONOS_HLS (.m3u8 adaptive video + audio fallback)
        val hls = VisionOsHlsResolver.resolve(videoId)
        if (hls is ResolveResult.Success) {
            return@withContext ResolvedStream(
                url = hls.resolved.url,
                format = "HLS (${maxHeight}p max)",
                userAgent = hls.resolved.userAgent,
                client = hls.resolved.tierId,
                loudnessDb = hls.resolved.loudnessDb
            )
        }
        // Tier 3: Progressive muxed fallback (360p MP4)
        if (adaptive != null) {
            return@withContext ResolvedStream(
                url = adaptive.videoUrl,
                format = adaptive.formatLabel,
                userAgent = adaptive.userAgent,
                client = adaptive.client,
                loudnessDb = adaptive.loudnessDb,
                secondaryAudioUrl = null
            )
        }
        null
    }


    // ------------------------------------------------------------------
    //  Official video lookup (v7.52 step 3)
    //  Order of trust:
    //   1. YouTube Music's own metadata (musicVideoType + "counterpart" = the song's video)
    //   2. YouTube Music video search, accepting ONLY official-typed results
    //   3. Regular YouTube search, accepting ONLY verified channels with "official" titles
    //  Anything else (lyric videos, covers, fan uploads, live clips) is never played as video.
    // ------------------------------------------------------------------

    private const val VIDEOS_PARAMS = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
    private const val NEG_CACHE_TTL_MS = 4 * 60 * 1000L

    private val musicVideoIdCache = object : LinkedHashMap<String, String>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 300
    }
    // songId -> time when we learned "no official video exists". Only stored after a clean search.
    private val noVideoCache = object : LinkedHashMap<String, Long>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 300
    }

    private val inFlightLookups = ConcurrentHashMap<String, Deferred<VideoLookup>>()

    fun forgetVideoLookup(songId: String) {
        synchronized(musicVideoIdCache) { musicVideoIdCache.remove(songId) }
        synchronized(noVideoCache) { noVideoCache.remove(songId) }
        ExtractorPrefs.removeCachedMusicVideoId(songId)
    }

    fun confirmVideoPlayback(songId: String, videoId: String, videoDurationSec: Long) {
        synchronized(musicVideoIdCache) { musicVideoIdCache[songId] = videoId }
        synchronized(noVideoCache) { noVideoCache.remove(songId) }
        ExtractorPrefs.putCachedMusicVideoId(songId, videoId, videoDurationSec)
    }

    // Matching rules live in OfficialVideoMatcher (pure Kotlin, unit-tested).
    private fun isOfficialVideoType(t: String?): Boolean = OfficialVideoMatcher.isOfficialVideoType(t)
    private fun artistNames(raw: String): List<String> = OfficialVideoMatcher.artistNames(raw)

    private class PanelInfo(
        val videoId: String,
        val type: String?,
        val counterpartId: String?,
        val counterpartType: String?,
        val counterpartTitle: String?,
        val counterpartArtist: String?,
        val counterpartDurationSec: Long
    )

    private fun panelMusicType(r: JsonObject): String? =
        r.obj("navigationEndpoint")?.obj("watchEndpoint")
            ?.obj("watchEndpointMusicSupportedConfigs")?.obj("watchEndpointMusicConfig")?.str("musicVideoType")

    private fun extractPanelRendererTitle(r: JsonObject): String? =
        r.obj("title")?.arr("runs")?.toList()?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject?.str("text")
            ?: r.obj("title")?.str("simpleText")

    private fun extractPanelRendererArtist(r: JsonObject): String? {
        val runs = r.obj("longBylineText")?.arr("runs")?.toList()
            ?: r.obj("shortBylineText")?.arr("runs")?.toList()
            ?: return null
        return runs.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("text")?.trim() }
            .firstOrNull { it.isNotEmpty() && it != "\u2022" && parseDuration(it) == null }
    }

    private fun extractPanelRendererDuration(r: JsonObject): Long {
        val raw = r.obj("lengthText")?.arr("runs")?.toList()?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject?.str("text")
            ?: r.obj("lengthText")?.str("simpleText")
            ?: return 0L
        return parseDuration(raw) ?: 0L
    }

    /** Asks YouTube Music about this exact video id: what type is it, and what is its counterpart? */
    private fun fetchPanelInfo(videoId: String): PanelInfo? {
        val remixVer = ExtractorPrefs.getRemixVersion()
        val body = JsonObject().apply {
            add("context", baseContext(remixClientJson(remixVer)))
            addProperty("videoId", videoId)
            addProperty("isAudioOnly", true)
            addProperty("enablePersistentPlaylistPanel", true)
        }
        val json = parseObject(postJson(MUSIC_NEXT_URL, body, remixHeaders(remixVer))) ?: return null
        val wrappers = mutableListOf<JsonObject>()
        collectWrapperRenderers(json, wrappers)
        for (w in wrappers) {
            val primary = w.obj("primaryRenderer")?.obj("playlistPanelVideoRenderer") ?: continue
            if (primary.str("videoId") != videoId) continue
            val cp = w.arr("counterpart")?.toList()?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
                ?.obj("counterpartRenderer")?.obj("playlistPanelVideoRenderer")
            return PanelInfo(
                videoId = videoId,
                type = panelMusicType(primary),
                counterpartId = cp?.str("videoId"),
                counterpartType = cp?.let { panelMusicType(it) },
                counterpartTitle = cp?.let { extractPanelRendererTitle(it) },
                counterpartArtist = cp?.let { extractPanelRendererArtist(it) },
                counterpartDurationSec = cp?.let { extractPanelRendererDuration(it) } ?: 0L
            )
        }
        val panels = mutableListOf<JsonObject>()
        collectPanelRenderers(json, panels)
        val self = panels.firstOrNull { it.str("videoId") == videoId } ?: return null
        val cp = self.arr("counterpart")?.toList()?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?.obj("counterpartRenderer")?.obj("playlistPanelVideoRenderer")
        return PanelInfo(
            videoId = videoId,
            type = panelMusicType(self),
            counterpartId = cp?.str("videoId"),
            counterpartType = cp?.let { panelMusicType(it) },
            counterpartTitle = cp?.let { extractPanelRendererTitle(it) },
            counterpartArtist = cp?.let { extractPanelRendererArtist(it) },
            counterpartDurationSec = cp?.let { extractPanelRendererDuration(it) } ?: 0L
        )
    }

    private fun collectWrapperRenderers(el: JsonElement, out: MutableList<JsonObject>) {
        when {
            el.isJsonObject -> for ((key, value) in el.asJsonObject.entrySet()) {
                if (key == "playlistPanelVideoWrapperRenderer" && value.isJsonObject) out.add(value.asJsonObject)
                else collectWrapperRenderers(value, out)
            }
            el.isJsonArray -> el.asJsonArray.forEach { collectWrapperRenderers(it, out) }
        }
    }

    private sealed class SearchOutcome {
        data class Ok(val candidates: List<OfficialVideoMatcher.Candidate>, val hasContainer: Boolean) : SearchOutcome()
        object Failed : SearchOutcome()
    }

    /** YouTube Music "Videos" search. Distinguishes clean 0-result responses from network/parse failures. */
    private fun searchMusicVideos(query: String, filtered: Boolean): SearchOutcome {
        val remixVer = ExtractorPrefs.getRemixVersion()
        val body = JsonObject().apply {
            add("context", baseContext(remixClientJson(remixVer)))
            addProperty("query", query)
            if (filtered) addProperty("params", VIDEOS_PARAMS)
        }
        val raw = postJson(MUSIC_SEARCH_URL, body, remixHeaders(remixVer)) ?: return SearchOutcome.Failed
        val json = parseObject(raw) ?: return SearchOutcome.Failed
        val hasContainer = json.has("contents")
        val renderers = mutableListOf<JsonObject>()
        collectRenderers(json, renderers)
        val labels = setOf("song", "video", "songs", "videos", "single", "ep", "album")
        val out = mutableListOf<OfficialVideoMatcher.Candidate>()
        for (r in renderers) {
            if (r.str("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") continue
            val id = extractVideoIdFromRenderer(r) ?: continue
            val title = flexRuns(r, 0).firstOrNull() ?: continue
            val details = flexRuns(r, 1).map { it.trim() }.filter { it.isNotEmpty() && it != "\u2022" }
            val duration = details.lastOrNull()?.let { parseDuration(it) } ?: 0L
            val artist = details.firstOrNull { it.lowercase() !in labels && parseDuration(it) == null && !it.contains(" views", true) && !it.contains(" plays", true) } ?: ""
            out.add(OfficialVideoMatcher.Candidate(id, title, artist, duration, extractMusicVideoType(r), verified = false))
        }
        val twoRows = mutableListOf<JsonObject>()
        collectNamedRenderers(json, "musicTwoRowItemRenderer", twoRows)
        for (r in twoRows) {
            val id = r.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId") ?: continue
            val title = r.obj("title")?.arr("runs")?.toList()?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.str("text") ?: continue
            val sub = r.obj("subtitle")?.arr("runs")?.toList().orEmpty()
                .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("text")?.trim() }
                .filter { it.isNotEmpty() && it != "\u2022" }
            val duration = sub.lastOrNull()?.let { parseDuration(it) } ?: 0L
            val artist = sub.firstOrNull { it.lowercase() !in labels && parseDuration(it) == null && !it.contains(" views", true) } ?: ""
            out.add(OfficialVideoMatcher.Candidate(id, title, artist, duration, extractMusicVideoType(r), verified = false))
        }
        return SearchOutcome.Ok(out, hasContainer)
    }

    private fun collectNamedRenderers(el: JsonElement, name: String, out: MutableList<JsonObject>) {
        when {
            el.isJsonObject -> for ((key, value) in el.asJsonObject.entrySet()) {
                if (key == name && value.isJsonObject) out.add(value.asJsonObject)
                else collectNamedRenderers(value, name, out)
            }
            el.isJsonArray -> el.asJsonArray.forEach { collectNamedRenderers(it, name, out) }
        }
    }

    /** Regular YouTube search. Candidates are only usable when the channel carries a verified badge. */
    private fun searchVerifiedYouTube(query: String): SearchOutcome {
        val visitorData = VisionOsPlayerClient.getOrFetchVisitorData()
        val clientJson = JsonObject().apply {
            addProperty("clientName", "WEB")
            addProperty("clientVersion", "2.20240101.00.00")
            addProperty("hl", VisionOsProfile.getDeviceHl())
            addProperty("gl", VisionOsProfile.getDeviceGl())
            if (!visitorData.isNullOrBlank()) addProperty("visitorData", visitorData)
        }
        val body = JsonObject().apply {
            add("context", baseContext(clientJson))
            addProperty("query", query)
            addProperty("params", "EgIQAQ%3D%3D")
        }
        val headers = buildMap {
            put("User-Agent", YoutubeRequests.FIREFOX_UA)
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
            if (!visitorData.isNullOrBlank()) put("X-Goog-Visitor-Id", visitorData)
        }
        val raw = postJson("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body, headers) ?: return SearchOutcome.Failed
        val json = parseObject(raw) ?: return SearchOutcome.Failed
        val hasContainer = json.has("contents")
        val renderers = mutableListOf<JsonObject>()
        collectVideoRenderers(json, renderers)
        collectNamedRenderers(json, "gridVideoRenderer", renderers)
        collectNamedRenderers(json, "compactVideoRenderer", renderers)
        val out = mutableListOf<OfficialVideoMatcher.Candidate>()
        for (vr in renderers) {
            val id = vr.str("videoId") ?: continue
            val title = vr.obj("title")?.arr("runs")?.toList()?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.str("text")
                ?: vr.obj("title")?.str("simpleText") ?: continue
            val channel = vr.obj("ownerText")?.arr("runs")?.toList()?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.str("text") ?: ""
            val duration = vr.obj("lengthText")?.str("simpleText")?.let { parseDuration(it) } ?: 0L
            val badgeStyles = vr.arr("ownerBadges")?.toList().orEmpty().mapNotNull {
                it.takeIf { e -> e.isJsonObject }?.asJsonObject?.obj("metadataBadgeRenderer")?.str("style")
            }
            val verified = badgeStyles.any { it == "BADGE_STYLE_TYPE_VERIFIED_ARTIST" || it == "BADGE_STYLE_TYPE_VERIFIED" } ||
                channel.endsWith("vevo", ignoreCase = true)
            val artistChannel = badgeStyles.any { it == "BADGE_STYLE_TYPE_VERIFIED_ARTIST" }
            out.add(OfficialVideoMatcher.Candidate(id, title, channel, duration, null, verified, artistChannel))
        }
        return SearchOutcome.Ok(out, hasContainer)
    }

    private fun rememberVideo(songId: String, videoId: String): String {
        synchronized(musicVideoIdCache) { musicVideoIdCache[songId] = videoId }
        synchronized(noVideoCache) { noVideoCache.remove(songId) }
        return videoId
    }

    sealed class VideoLookup {
        data class Found(val id: String) : VideoLookup()
        /** A clean search found no official video. */
        object None : VideoLookup()
        /** Network/parse problem: we simply don't know. Never treat as "no video". */
        object Error : VideoLookup()
    }

    suspend fun findMusicVideoId(song: Song): String? = (lookupMusicVideo(song) as? VideoLookup.Found)?.id

    fun isKnownWithoutVideo(songId: String): Boolean = synchronized(noVideoCache) {
        val t = noVideoCache[songId]
        t != null && System.currentTimeMillis() - t < NEG_CACHE_TTL_MS
    }

    suspend fun lookupMusicVideo(
        song: Song,
        excludedIds: Set<String> = emptySet(),
        bypassNegativeCache: Boolean = false
    ): VideoLookup = withContext(Dispatchers.IO) {
        synchronized(musicVideoIdCache) {
            musicVideoIdCache[song.id]?.takeIf { it !in excludedIds }?.let { vid ->
                Log.i(TAG, "lookupMusicVideo(${song.id}) -> cache-mem [$vid]")
                return@withContext VideoLookup.Found(vid)
            }
        }
        ExtractorPrefs.getCachedMusicVideoId(song.id, song.durationSeconds)
            ?.takeIf { it !in excludedIds }
            ?.let { persisted ->
                synchronized(musicVideoIdCache) { musicVideoIdCache[song.id] = persisted }
                Log.i(TAG, "lookupMusicVideo(${song.id}) -> cache-disk [$persisted]")
                return@withContext VideoLookup.Found(persisted)
            }
        if (!bypassNegativeCache && excludedIds.isEmpty()) {
            synchronized(noVideoCache) {
                val t = noVideoCache[song.id]
                if (t != null && System.currentTimeMillis() - t < NEG_CACHE_TTL_MS) {
                    return@withContext VideoLookup.None
                }
            }
        }

        // Single-flight deduplication when no exclusions are active
        if (excludedIds.isEmpty()) {
            val existing = inFlightLookups[song.id]
            if (existing != null && existing.isActive) {
                return@withContext existing.await()
            }
        }

        coroutineScope {
            val job = async {
                runLookupMusicVideo(song, excludedIds)
            }
            if (excludedIds.isEmpty()) {
                inFlightLookups[song.id] = job
            }
            try {
                job.await()
            } finally {
                if (excludedIds.isEmpty()) {
                    inFlightLookups.remove(song.id, job)
                }
            }
        }
    }

    private suspend fun runLookupMusicVideo(song: Song, excludedIds: Set<String>): VideoLookup {
        var hadError = false
        var anyValidContainer = false
        val rejectLogger: (OfficialVideoMatcher.Candidate, String) -> Unit = { c, reason ->
            Log.d(TAG, "VideoCandidate rejected [${c.id}] '${c.title}' (${c.artist}, ${c.durationSec}s, type=${c.type}): $reason")
        }

        // 1. YouTube Music's own panel metadata (validated strictly before accepting counterpart!).
        try {
            kotlin.coroutines.coroutineContext.ensureActive()
            val info = withTimeoutOrNull(4_000L) { fetchPanelInfo(song.id) }
            if (info != null) {
                if (OfficialVideoMatcher.isOmv(info.type) && info.videoId !in excludedIds) {
                    Log.i(TAG, "lookupMusicVideo(${song.id}) -> panel-self [${info.videoId}] dur=${song.durationSeconds}s")
                    return VideoLookup.Found(rememberVideo(song.id, info.videoId))
                }
                val cpId = info.counterpartId
                if (cpId != null && cpId !in excludedIds) {
                    val cpCand = OfficialVideoMatcher.Candidate(
                        id = cpId,
                        title = info.counterpartTitle ?: song.title,
                        artist = info.counterpartArtist ?: "",
                        durationSec = info.counterpartDurationSec,
                        type = info.counterpartType
                    )
                    if (OfficialVideoMatcher.isValidCounterpart(song.id, song.title, song.artist, song.durationSeconds, cpCand, rejectLogger)) {
                        Log.i(TAG, "lookupMusicVideo(${song.id}) -> panel-counterpart [$cpId] '${cpCand.title}' (${cpCand.artist}, ${cpCand.durationSec}s vs song=${song.durationSeconds}s)")
                        return VideoLookup.Found(rememberVideo(song.id, cpId))
                    }
                }
            } else {
                hadError = true
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { hadError = true }

        val cleanArtist = artistNames(song.artist).firstOrNull() ?: song.artist
        val cleanTitle = OfficialVideoMatcher.cleanSongTitle(song.title)
        val movieHint = OfficialVideoMatcher.extractMovieHint(song.title)
        val q = "$cleanTitle $cleanArtist"

        // 2. Run all YT Music search attempts in parallel, merge & deduplicate, then pick highest score (Issue 5 & 6).
        val ytmQueries = buildList {
            add(q to true)
            add("$q official video" to true)
            if (movieHint != null) add("$cleanTitle $movieHint $cleanArtist" to true)
            add("$q official video" to false)
        }
        val mergedYtm = linkedMapOf<String, OfficialVideoMatcher.Candidate>()
        coroutineScope {
            val defs = ytmQueries.map { (query, filtered) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(4_500L) { searchMusicVideos(query, filtered) } ?: SearchOutcome.Failed
                }
            }
            for (outcome in defs.awaitAll()) {
                when (outcome) {
                    is SearchOutcome.Ok -> {
                        if (outcome.hasContainer) anyValidContainer = true
                        for (c in outcome.candidates) {
                            if (c.id !in excludedIds) {
                                val prev = mergedYtm[c.id]
                                if (prev == null || (OfficialVideoMatcher.isOmv(c.type) && !OfficialVideoMatcher.isOmv(prev.type))) {
                                    mergedYtm[c.id] = c
                                }
                            }
                        }
                    }
                    SearchOutcome.Failed -> hadError = true
                }
            }
        }
        val bestYtm = OfficialVideoMatcher.pickFromMusicSearch(
            song.id, song.title, song.artist, song.durationSeconds,
            mergedYtm.values.toList(), rejectLogger
        )
        if (bestYtm != null) {
            Log.i(TAG, "lookupMusicVideo(${song.id}) -> ytm-search [${bestYtm.id}] '${bestYtm.title}' (${bestYtm.artist}, ${bestYtm.durationSec}s vs song=${song.durationSeconds}s, type=${bestYtm.type})")
            return VideoLookup.Found(rememberVideo(song.id, bestYtm.id))
        }

        // 3. Regular YouTube (verified channels only), merging plain & movie-hint queries.
        val ytQueries = buildList {
            add("$q official video")
            if (movieHint != null) add("$cleanTitle $movieHint official video")
        }
        val mergedYt = linkedMapOf<String, OfficialVideoMatcher.Candidate>()
        coroutineScope {
            val defs = ytQueries.map { query ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(4_500L) { searchVerifiedYouTube(query) } ?: SearchOutcome.Failed
                }
            }
            for (outcome in defs.awaitAll()) {
                when (outcome) {
                    is SearchOutcome.Ok -> {
                        if (outcome.hasContainer) anyValidContainer = true
                        for (c in outcome.candidates) {
                            if (c.id !in excludedIds && c.id !in mergedYt) {
                                mergedYt[c.id] = c
                            }
                        }
                    }
                    SearchOutcome.Failed -> hadError = true
                }
            }
        }
        val bestYt = OfficialVideoMatcher.pickFromYouTube(
            song.id, song.title, song.artist, song.durationSeconds,
            mergedYt.values.toList(), rejectLogger
        )
        if (bestYt != null) {
            Log.i(TAG, "lookupMusicVideo(${song.id}) -> youtube [${bestYt.id}] '${bestYt.title}' (${bestYt.artist}, ${bestYt.durationSec}s vs song=${song.durationSeconds}s, verified=${bestYt.verified})")
            return VideoLookup.Found(rememberVideo(song.id, bestYt.id))
        }

        return if (hadError || !anyValidContainer) {
            VideoLookup.Error
        } else {
            synchronized(noVideoCache) { noVideoCache[song.id] = System.currentTimeMillis() }
            VideoLookup.None
        }
    }
}
