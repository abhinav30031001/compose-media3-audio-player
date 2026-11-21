package com.ytmusic.clone.data.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ytmusic.clone.data.model.LyricLine
import com.ytmusic.clone.data.model.LyricsResult
import com.ytmusic.clone.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Lyrics repository supporting LRCLIB millisecond-precision synchronized lyrics,
 * with search fallback for Bollywood / Hindi / regional music, and InnerTube fallback.
 */
object LyricsRepository {
    private const val TAG = "LyricsRepo"
    private val client = SecureHttp.newBuilder(PolicyScope.LYRICS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val cache = object : LinkedHashMap<String, LyricsResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>?): Boolean = size > 30
    }

    suspend fun get(song: Song, durationMs: Long = 0L): LyricsResult {
        synchronized(cache) { cache[song.id] }?.let { return it }

        // 1. Query LRCLIB for exact millisecond-synchronized LRC lyrics (direct + search fallback)
        val lrcResult = fetchFromLrcLib(song.title, song.artist, durationMs)
        if (lrcResult is LyricsResult.Found && lrcResult.isSynced) {
            synchronized(cache) { cache[song.id] = lrcResult }
            return lrcResult
        }

        // 2. Query YouTube Music InnerTube
        val ytResult = YouTubeExtractor.fetchLyrics(song.id, durationMs)
        if (ytResult is LyricsResult.Found) {
            synchronized(cache) { cache[song.id] = ytResult }
            return ytResult
        }

        // 3. Fall back to plain lyrics from LRCLIB if YouTube had none
        if (lrcResult is LyricsResult.Found) {
            synchronized(cache) { cache[song.id] = lrcResult }
            return lrcResult
        }

        return ytResult
    }

    suspend fun get(videoId: String): LyricsResult {
        synchronized(cache) { cache[videoId] }?.let { return it }
        val result = YouTubeExtractor.fetchLyrics(videoId, 0L)
        if (result !is LyricsResult.Error) synchronized(cache) { cache[videoId] = result }
        return result
    }

    private suspend fun fetchFromLrcLib(title: String, artist: String, durationMs: Long): LyricsResult? = withContext(Dispatchers.IO) {
        if (!com.ytmusic.clone.data.local.ExtractorPrefs.isLyricsLookupEnabled()) {
            return@withContext null
        }
        val cleanT = cleanTitle(title)
        val cleanA = cleanArtist(artist)
        val durationSec = if (durationMs > 10_000L) durationMs / 1000L else 0L

        // 1. Direct get query: track_name + artist_name (+ duration)
        try {
            val encodedTitle = URLEncoder.encode(cleanT, "UTF-8")
            val encodedArtist = URLEncoder.encode(cleanA, "UTF-8")
            var url = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist"
            if (durationSec > 0) {
                url += "&duration=$durationSec"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "YTMusic-Android/7.25")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.readCapped(512 * 1024)
                    if (bodyStr != null) {
                        val json = gson.fromJson(bodyStr, JsonObject::class.java)
                        val synced = json?.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
                        val plain = json?.get("plainLyrics")?.takeIf { !it.isJsonNull }?.asString

                        if (!synced.isNullOrBlank()) {
                            val lines = LyricsUtils.parse(synced, durationMs)
                            if (lines.isNotEmpty()) {
                                return@withContext LyricsResult.Found(
                                    text = plain ?: synced,
                                    source = "Synced Lyrics (LRCLIB)",
                                    lines = lines,
                                    isSynced = true
                                )
                            }
                        }
                        if (!plain.isNullOrBlank()) {
                            return@withContext LyricsResult.Found(
                                text = plain,
                                source = "Lyrics",
                                lines = LyricsUtils.parse(plain, durationMs),
                                isSynced = false
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "LRCLIB direct query skipped: ${e.javaClass.simpleName}")
        }

        // 2. Fallback search query (matches Bollywood / Hindi / Punjabi even if YouTube lists composer instead of singer)
        try {
            val searchUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(cleanT, "UTF-8")}"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "YTMusic-Android/7.25")
                .get()
                .build()

            client.newCall(searchReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.readCapped(512 * 1024)
                    if (bodyStr != null) {
                        val arr = gson.fromJson(bodyStr, JsonArray::class.java)
                        if (arr != null && arr.size() > 0) {
                            var bestCandidate: JsonObject? = null
                            var minDiff = Long.MAX_VALUE

                            for (el in arr) {
                                val obj = el.asJsonObject
                                val synced = obj.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
                                if (!synced.isNullOrBlank()) {
                                    val candDur = obj.get("duration")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                                    val diff = if (durationSec > 0 && candDur > 0) kotlin.math.abs(candDur - durationSec) else 0L
                                    if (diff < minDiff) {
                                        minDiff = diff
                                        bestCandidate = obj
                                        if (diff <= 3) break // Exact duration match!
                                    }
                                }
                            }

                            if (bestCandidate == null) {
                                for (el in arr) {
                                    val obj = el.asJsonObject
                                    val plain = obj.get("plainLyrics")?.takeIf { !it.isJsonNull }?.asString
                                    if (!plain.isNullOrBlank()) {
                                        bestCandidate = obj
                                        break
                                    }
                                }
                            }

                            if (bestCandidate != null) {
                                val synced = bestCandidate.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
                                val plain = bestCandidate.get("plainLyrics")?.takeIf { !it.isJsonNull }?.asString

                                if (!synced.isNullOrBlank()) {
                                    val lines = LyricsUtils.parse(synced, durationMs)
                                    if (lines.isNotEmpty()) {
                                        return@withContext LyricsResult.Found(
                                            text = plain ?: synced,
                                            source = "Synced Lyrics (Search)",
                                            lines = lines,
                                            isSynced = true
                                        )
                                    }
                                }
                                if (!plain.isNullOrBlank()) {
                                    return@withContext LyricsResult.Found(
                                        text = plain,
                                        source = "Lyrics",
                                        lines = LyricsUtils.parse(plain, durationMs),
                                        isSynced = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "LRCLIB search query skipped: ${e.javaClass.simpleName}")
        }

        null
    }

    private fun cleanTitle(title: String): String {
        var t = title
        t = t.replace(Regex("""(?i)\(from\s+["'].*?["']\)"""), "")
        t = t.replace(Regex("""(?i)\(movie:\s*.*?\)"""), "")
        t = t.replace(Regex("""(?i)\(feat\..*?\)"""), "")
        t = t.replace(Regex("""(?i)\(ft\..*?\)"""), "")
        t = t.replace(Regex("""(?i)\[.*?\]"""), "")
        t = t.replace(Regex("""(?i)\(official.*?\)|(?i)\(video.*?\)"""), "")
        t = t.replace(Regex("""(?i)\(audio.*?\)|(?i)\(lyric.*?\)"""), "")
        t = t.replace(Regex("""(?i)\bft\.?\s+.*"""), "")
        t = t.replace(Regex("""(?i)\bfeat\.?\s+.*"""), "")
        return t.trim().ifEmpty { title.trim() }
    }

    private fun cleanArtist(artist: String): String {
        return artist
            .split(",", "&", "ft.", "feat.", "FT.", "FEAT.", "•")
            .firstOrNull()?.trim() ?: artist.trim()
    }
}
