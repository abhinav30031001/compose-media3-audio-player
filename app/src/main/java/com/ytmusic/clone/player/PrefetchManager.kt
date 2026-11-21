package com.ytmusic.clone.player

import android.util.Log
import com.ytmusic.clone.data.network.DiagnosticLogger
import com.ytmusic.clone.data.network.WebSafariAuthHlsResolver
import com.ytmusic.clone.data.network.YouTubeExtractor
import com.ytmusic.clone.data.network.YouTubeExtractor.ResolvedStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Proactively pre-resolves the next track in the queue.
 *
 * When track N is playing, PrefetchManager pre-resolves track N+1 in the background
 * via DefaultResolverChain (including 3MB range probe verification).
 * When the track advances, the resolved stream URL is retrieved from the prefetch cache
 * instantly (~0ms latency instead of 500-1500ms network round-trip).
 *
 * Safe bounded LRU cache with 10-minute TTL (well within YouTube's 6-hour URL expiry).
 */
object PrefetchManager {
    private const val TAG = "PrefetchManager"
    private const val CACHE_CAPACITY = 10
    private const val TTL_MS = 10 * 60 * 1000L // 10 minutes

    private data class CachedResolved(
        val timestampMs: Long,
        val stream: ResolvedStream
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val cache = object : LinkedHashMap<String, CachedResolved>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResolved>?): Boolean {
            return size > CACHE_CAPACITY
        }
    }

    private val ongoingJobs = mutableMapOf<String, Job>()

    /** Called after a track was pre-resolved (used to pre-buffer its first seconds into the audio cache). */
    @Volatile
    var onResolved: ((String, ResolvedStream) -> Unit)? = null

    /**
     * Checks if a song has already been pre-resolved and is still valid.
     * If valid, consumes and returns the resolved stream.
     * If prefetch is currently in flight for this videoId, awaits the job before giving up.
     */
    suspend fun getPrefetched(videoId: String): ResolvedStream? {
        var inFlightJob: Job? = null
        val immediate = mutex.withLock {
            val entry = cache[videoId]
            if (entry != null) {
                val age = System.currentTimeMillis() - entry.timestampMs
                if (age > TTL_MS) {
                    Log.d(TAG, "Prefetched stream for $videoId expired (${age / 1000}s old), evicting")
                    cache.remove(videoId)
                    null
                } else {
                    Log.i(TAG, "Prefetch cache HIT for $videoId (age: ${age / 1000}s, tier: ${entry.stream.client})")
                    DiagnosticLogger.log(
                        tierId = entry.stream.client,
                        status = "PREFETCH_HIT",
                        probeResult = "Prefetch cache hit for $videoId (saved ~800ms resolution time)"
                    )
                    cache.remove(videoId)
                    entry.stream
                }
            } else {
                inFlightJob = ongoingJobs[videoId]?.takeIf { it.isActive }
                null
            }
        }

        if (immediate != null) return immediate

        // If a prefetch is actively running for this videoId, await it instead of cache-missing immediately
        if (inFlightJob != null) {
            Log.i(TAG, "Prefetch in flight for $videoId, awaiting completion...")
            try {
                inFlightJob?.join()
            } catch (e: Exception) {
                Log.w(TAG, "Awaiting in-flight prefetch interrupted: ${e.javaClass.simpleName}")
            }
            return mutex.withLock {
                val entry = cache[videoId] ?: return@withLock null
                val age = System.currentTimeMillis() - entry.timestampMs
                if (age > TTL_MS) {
                    cache.remove(videoId)
                    null
                } else {
                    Log.i(TAG, "Prefetch cache HIT after in-flight join for $videoId (tier: ${entry.stream.client})")
                    DiagnosticLogger.log(
                        tierId = entry.stream.client,
                        status = "PREFETCH_HIT",
                        probeResult = "Prefetch joined and hit for $videoId"
                    )
                    cache.remove(videoId)
                    entry.stream
                }
            }
        }

        return null
    }

    /**
     * Manually inserts a pre-resolved stream into cache (useful for testing).
     */
    suspend fun putPrefetched(videoId: String, stream: ResolvedStream, timestampMs: Long = System.currentTimeMillis()) = mutex.withLock {
        cache[videoId] = CachedResolved(timestampMs, stream)
    }

    /**
     * Triggers background prefetch for the upcoming song.
     */
    fun prefetchNextTrack(videoId: String) {
        if (videoId.isBlank()) return

        scope.launch {
            val shouldLaunch = mutex.withLock {
                val existing = cache[videoId]
                if (existing != null && (System.currentTimeMillis() - existing.timestampMs) < TTL_MS) {
                    Log.d(TAG, "Track $videoId already pre-resolved in cache, skipping prefetch")
                    false
                } else if (ongoingJobs[videoId]?.isActive == true) {
                    Log.d(TAG, "Prefetch for $videoId already in progress")
                    false
                } else {
                    true
                }
            }

            if (!shouldLaunch) return@launch

            val job = scope.launch {
                try {
                    Log.i(TAG, "Starting background prefetch for upcoming track: $videoId")
                    val startTime = System.currentTimeMillis()
                    val stream = YouTubeExtractor.resolveAudioStream(
                        videoId = videoId,
                        skipClients = setOf(WebSafariAuthHlsResolver.TIER_ID),
                        keepDiagnostics = true,
                        forceFresh = false,
                        recordBreaker = false
                    )
                    if (stream != null) {
                        val latency = System.currentTimeMillis() - startTime
                        mutex.withLock {
                            cache[videoId] = CachedResolved(System.currentTimeMillis(), stream)
                        }
                        Log.i(TAG, "Prefetch successful for $videoId via ${stream.client} in ${latency}ms")
                        try { onResolved?.invoke(videoId, stream) } catch (_: Exception) {}
                        DiagnosticLogger.log(
                            tierId = stream.client,
                            status = "PREFETCH_SUCCESS",
                            probeResult = "Pre-resolved $videoId (${stream.format}) in ${latency}ms"
                        )
                    } else {
                        Log.w(TAG, "Prefetch failed for $videoId: no stream returned")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch exception for $videoId: ${e.javaClass.simpleName}")
                } finally {
                    mutex.withLock {
                        ongoingJobs.remove(videoId)
                    }
                }
            }

            mutex.withLock {
                ongoingJobs[videoId] = job
            }
        }
    }

    /**
     * Clear all pre-resolved streams (e.g. on network switch, playback stop, or full reset).
     */
    suspend fun clear() = mutex.withLock {
        cache.clear()
        ongoingJobs.values.forEach { it.cancel() }
        ongoingJobs.clear()
    }

    /**
     * Non-suspending clear helper for calling from synchronous lifecycle hooks.
     */
    fun clearCache() {
        scope.launch {
            clear()
        }
    }

    /**
     * Cache size query for tests and diagnostics.
     */
    suspend fun size(): Int = mutex.withLock { cache.size }
}
