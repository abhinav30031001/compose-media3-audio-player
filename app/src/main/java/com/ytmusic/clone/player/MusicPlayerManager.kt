package com.ytmusic.clone.player

import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File
import java.util.concurrent.atomic.AtomicLong


import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable


import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import com.ytmusic.clone.data.network.YoutubeRequests
import com.ytmusic.clone.data.network.readBytesCapped


import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import okhttp3.Request
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.local.PlayerStateStore
import com.ytmusic.clone.data.local.SavedPlayerState
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.data.model.ThumbnailUtils
import com.ytmusic.clone.data.network.AndroidVrPlayerClient
import com.ytmusic.clone.data.network.AndroidVrProgressiveResolver
import com.ytmusic.clone.data.network.AndroidVrProfile
import com.ytmusic.clone.data.network.DefaultResolverChain
import com.ytmusic.clone.data.network.Resolved
import com.ytmusic.clone.data.network.TierCircuitBreaker
import com.ytmusic.clone.data.network.VisionOsHlsResolver
import com.ytmusic.clone.data.network.WebSafariAuthHlsResolver
import com.ytmusic.clone.data.network.VisionOsPlayerClient
import com.ytmusic.clone.data.network.VisionOsProfile
import com.ytmusic.clone.data.network.OfficialVideoMatcher
import com.ytmusic.clone.data.network.YouTubeExtractor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RepeatMode { OFF, ALL, ONE }

enum class VideoQuality(val label: String, val maxHeight: Int) {
    AUTO("Auto (best)", 2160),
    Q_1080P("1080p", 1080),
    Q_720P("720p", 720),
    Q_480P("480p", 480),
    Q_360P("360p", 360)
}

object MusicPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null
    private var playJob: Job? = null
    private const val TAG = "MusicPlayerManager"

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError = _playbackError.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _isVideoMode = MutableStateFlow(false)
    val isVideoMode = _isVideoMode.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQuality.AUTO)
    val videoQuality = _videoQuality.asStateFlow()

    private val _isFullscreenVideo = MutableStateFlow(false)
    val isFullscreenVideo = _isFullscreenVideo.asStateFlow()

    fun setFullscreenVideo(fullscreen: Boolean) {
        _isFullscreenVideo.value = fullscreen
    }

    fun getExoPlayer(): ExoPlayer? = exoPlayer

    // Is a video surface on screen right now? (false while the app is in the background)
    @Volatile private var videoSurfaceActive = true
    // Lowered after repeated decoder failures so weak phones step down 4K -> 720p -> 480p before giving up.
    @Volatile private var decoderMaxHeight = Int.MAX_VALUE
    private fun effectiveMaxHeight(): Int = minOf(_videoQuality.value.maxHeight, decoderMaxHeight)

    fun setVideoSurfaceActive(active: Boolean) {
        videoSurfaceActive = active
        val player = exoPlayer ?: return
        if (!_isVideoMode.value) return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !active)
            .build()
    }

    private fun applyVideoTrackParameters(videoEnabled: Boolean) {
        val player = exoPlayer ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        if (videoEnabled) {
            builder
                // In the background nothing draws the picture: keep the video track off so no decoder/bandwidth is wasted.
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoSurfaceActive)
                .setMaxVideoSize(Int.MAX_VALUE, effectiveMaxHeight())
                .setMaxAudioBitrate(Int.MAX_VALUE)
                .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
                .setForceHighestSupportedBitrate(false)
        } else {
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .clearVideoSizeConstraints()
                .setForceHighestSupportedBitrate(true)
        }
        player.trackSelectionParameters = builder.build()
    }

    // What the USER asked for. _isVideoMode is what the CURRENT song actually does: a song without
    // an official video drops to audio, but the next song tries video again.
    sealed class VideoLoadState {
        object Idle : VideoLoadState()
        object Resolving : VideoLoadState()
        data class Playing(val videoId: String) : VideoLoadState()
        data class Failed(val reason: String) : VideoLoadState()
    }

    private val _videoLoadState = MutableStateFlow<VideoLoadState>(VideoLoadState.Idle)
    val videoLoadState = _videoLoadState.asStateFlow()

    private var videoPreferred = false
    // True while the loaded stream really contains video (so failover keeps video as video).
    private var currentIsVideoStream = false
    private var currentVideoId: String? = null
    private var hasRetriedWrongVideo = false
    private var videoFallbackCountForTrack = 0
    private var lastVideoFallbackToastSongId: String? = null
    private val wrongVideoIds = ConcurrentHashMap<String, MutableSet<String>>()
    // Monotonic generation counter so rapid Song <-> Video or track taps never apply stale async results.
    private val loadGeneration = AtomicLong(0L)

    private fun dropVideoToAudio(reason: String, userMessage: String? = null, clearPreferred: Boolean = false) {
        Log.w(TAG, "video->audio: $reason (song=${_currentSong.value?.id}, vid=$currentVideoId)")
        _isVideoMode.value = false
        _isFullscreenVideo.value = false
        currentIsVideoStream = false
        currentVideoId = null
        _videoLoadState.value = VideoLoadState.Failed(reason)
        if (clearPreferred) videoPreferred = false
        applyVideoTrackParameters(false)
        val sid = _currentSong.value?.id
        if (userMessage != null && sid != null && lastVideoFallbackToastSongId != sid) {
            lastVideoFallbackToastSongId = sid
            showMessage(userMessage)
        }
    }

    fun setVideoMode(enabled: Boolean) {
        videoPreferred = false
        _isVideoMode.value = false
        _isFullscreenVideo.value = false
        if (enabled) {
            showMessage("Video mode coming soon!")
        }
    }

    fun setVideoQuality(quality: VideoQuality) {
        if (_videoQuality.value == quality) return
        _videoQuality.value = quality
        appContext?.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putString("video_quality", quality.name)?.apply()
        if (_isVideoMode.value || videoPreferred) {
            applyVideoTrackParameters(true)
            val isHls = currentClient == YouTubeExtractor.CLIENT_VISIONOS_HLS ||
                currentClient == VisionOsHlsResolver.TIER_ID
            val song = _currentSong.value
            if (!isHls && song != null) {
                val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: _currentPosition.value
                _isBuffering.value = true
                launchLoad(song, pos, isRetry = false, targetClient = null, forceFresh = false)
            }
        }
    }

    enum class SleepTimerType {
        DURATION,
        END_OF_TRACK
    }

    private val _sleepTimerType = MutableStateFlow<SleepTimerType?>(null)
    val sleepTimerType = _sleepTimerType.asStateFlow()

    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    val sleepEndsAt = _sleepEndsAt.asStateFlow()

    private val _finishCurrentSong = MutableStateFlow(true)
    val finishCurrentSong = _finishCurrentSong.asStateFlow()

    private val _sleepRemainingSeconds = MutableStateFlow<Long?>(null)
    val sleepRemainingSeconds = _sleepRemainingSeconds.asStateFlow()

    private val _hasTimerExpiredWaitingForSong = MutableStateFlow(false)

    // v7.20: playback speed (pitch stays normal). Kept for this app session.
    val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private val _pitchSemitones = MutableStateFlow(0)
    val pitchSemitones = _pitchSemitones.asStateFlow()

    // Remembers the order before shuffling, so switching shuffle off restores it.
    private var originalQueue: List<Song> = emptyList()
    private var isOfflineQueue: Boolean = false
    private var sleepJob: Job? = null
    private var prefsLoaded = false
    private var warnedAboutNotifications = false

    // Set when we restored the last session: the song is shown but NOT loaded in the player.
    // When the user presses play we start it from this position.
    private var resumePositionMs = 0L
    private var progressTicks = 0
    // While a new song is being loaded the old one may still be in the player, so its
    // position must not be saved as the new song's position.
    private var pendingStartMs = 0L

    // ---- Automatic fallback when a stream breaks ----
    private const val MAX_AUTO_RETRIES = 3
    // Which client made the stream that is loaded now (null = offline file, nothing to switch).
    private var currentClient: String? = null
    private var retryCount = 0
    private val failedClients = mutableSetOf<String>()
    private var retryToastShown = false
    // When the current stream first became ready (0 = not yet), and where this load started.
    private var streamReadyAt = 0L
    private var lastLoadStartMs = 0L

    // Task 4: Same-client fresh retry on 403
    private var hasRetriedSameClient = false
    // Mid-track tier failover counter (allows cascading through all 3 tiers: max 2 failovers per track)
    private var failoverCount = 0

    // Task 4: Buffering watchdog (stalled stream recovery)
    private var bufferingWatchdogJob: Job? = null
    private const val BUFFERING_WATCHDOG_TIMEOUT_MS = 18_000L // 18 seconds

    // Task 4: Auto-resume when internet returns
    private var isOfflinePaused = false
    private var networkCallbackRegistered = false

    // v7.25: WakeLock & WifiLock to ensure background playback and stream resolution never stall when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun updateNetworkLocks(context: Context, isOnlineStream: Boolean) {
        // CPU wakeLock is held so audio decoding never drops in the background
        try {
            if (wakeLock == null) {
                val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "YTMusicClone:PlaybackWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(30 * 60 * 1000L) // 30 min safety timeout
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock", e)
        }

        // WifiLock optimization: ONLY hold WifiLock during online network streaming.
        // For offline downloaded files and cached playback, release WifiLock so the Wi-Fi chip enters deep sleep (~15-20% battery savings).
        try {
            if (isOnlineStream) {
                if (wifiLock == null) {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wifiManager?.createWifiLock(mode, "YTMusicClone:WifiLock")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (wifiLock?.isHeld != true) {
                    wifiLock?.acquire()
                }
            } else {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not update wifi lock", e)
        }
    }

    fun isTransitioningTrack(): Boolean = playJob?.isActive == true || isFetchingAutoplay

    private fun acquireLocks(context: Context) {
        val song = _currentSong.value
        val isOffline = song?.isDownloaded == true || (song != null && DownloadManager.isSongDownloaded(song.id))
        val isActive = _isPlaying.value || _isBuffering.value || playJob?.isActive == true || exoPlayer?.playWhenReady == true
        updateNetworkLocks(context, isOnlineStream = isActive && !isOffline)
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock", e)
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wifi lock", e)
        }
    }

    private fun processBitmapToArtworkBytes(bitmap: Bitmap): ByteArray? {
        return try {
            val targetSize = 800
            val minEdge = minOf(bitmap.width, bitmap.height)
            val xOffset = (bitmap.width - minEdge) / 2
            val yOffset = (bitmap.height - minEdge) / 2
            val square = Bitmap.createBitmap(bitmap, xOffset, yOffset, minEdge, minEdge)
            // Always scale to high-DPI 800x800 master with bilinear filtering
            val scaled = if (minEdge != targetSize) {
                Bitmap.createScaledBitmap(square, targetSize, targetSize, true)
            } else {
                square
            }
            val stream = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.JPEG
            }
            scaled.compress(format, 92, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

        private fun isValidImageHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val isJpeg = (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 && (bytes[2].toInt() and 0xFF) == 0xFF
        if (isJpeg) return true
        val isPng = (bytes[0].toInt() and 0xFF) == 0x89 && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        if (isPng) return true
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return true
        return false
    }

    private suspend fun loadArtworkBytes(rawUrl: String, songId: String? = null): ByteArray? = withContext(Dispatchers.IO) {
        val ctx = appContext

        // 1. Check if offline artwork is already saved on disk (for downloaded songs)
        if (ctx != null && !songId.isNullOrBlank()) {
            val cleanSongId = songId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (cleanSongId.isNotEmpty()) {
                try {
                    val storage = DownloadManager.getStorageDir(ctx)
                    val offlineCover = File(storage, "$cleanSongId.jpg")
                if (offlineCover.exists() && offlineCover.length() > 1024) {
                    val bytes = offlineCover.readBytes()
                    if (bytes.isNotEmpty() && isValidImageHeader(bytes)) {
                        Log.d(TAG, "Loaded artwork from offline disk for $songId")
                        return@withContext bytes
                    }
                }
            } catch (_: Exception) {}
            }
        }

        if (rawUrl.isBlank()) return@withContext null
        val fullMasterUrl = ThumbnailUtils.upgrade(rawUrl)

        // 2. Try loading full master URL through Coil with explicit 800x800 size
        var loadedBytes: ByteArray? = null
        if (ctx != null) {
            try {
                val req = ImageRequest.Builder(ctx)
                    .data(fullMasterUrl)
                    .size(800, 800)
                    .allowHardware(false)
                    .build()
                val result = ctx.imageLoader.execute(req)
                if (result is SuccessResult) {
                    val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bmp != null) {
                        val bytes = processBitmapToArtworkBytes(bmp)
                        if (bytes != null && bytes.isNotEmpty()) {
                            loadedBytes = bytes
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Coil master artwork load error: ${e.javaClass.simpleName}")
            }
        }

        // 3. Direct OkHttp fallback with clean query stripping & resolution cascade
        if (loadedBytes == null) {
            val clean = if (fullMasterUrl.contains("i.ytimg.com")) fullMasterUrl.substringBefore("?") else fullMasterUrl
            val urlsToTry = mutableListOf<String>()
            urlsToTry.add(clean)

            if (clean.contains("i.ytimg.com")) {
                val videoId = Regex("""vi/([a-zA-Z0-9_-]+)""").find(clean)?.groupValues?.getOrNull(1)
                if (videoId != null) {
                    urlsToTry.add("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")
                    urlsToTry.add("https://i.ytimg.com/vi/$videoId/sddefault.jpg")
                    urlsToTry.add("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                }
            } else if (clean.contains("googleusercontent.com") || clean.contains("ggpht.com")) {
                val base = clean.substringBefore("=")
                urlsToTry.add("$base=w1200-h1200-l90-rj")
                urlsToTry.add("$base=w800-h800-l90-rj")
                urlsToTry.add("$base=w544-h544-l90-rj")
                urlsToTry.add("$base=s800")
            }

            for (u in urlsToTry.distinct()) {
                try {
                    val req = Request.Builder()
                        .url(u)
                        .header("User-Agent", YoutubeRequests.FIREFOX_UA)
                        .build()
                    playbackOkHttp.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val b = try {
                                resp.readBytesCapped(2 * 1024 * 1024L) // 2 MB cap (Task C5)
                            } catch (_: Exception) {
                                null
                            }
                            if (b != null && b.size > 1024) {
                                val boundsOpts = BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                BitmapFactory.decodeByteArray(b, 0, b.size, boundsOpts)
                                val w = boundsOpts.outWidth
                                val h = boundsOpts.outHeight

                                // Reject images exceeding 4096x4096
                                if (w in 1..4096 && h in 1..4096) {
                                    val decodeOpts = BitmapFactory.Options().apply {
                                        inJustDecodeBounds = false
                                        inSampleSize = 1
                                        while (w / inSampleSize > 1200 || h / inSampleSize > 1200) {
                                            inSampleSize *= 2
                                        }
                                    }
                                    val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, decodeOpts)
                                    if (bmp != null) {
                                        val processed = processBitmapToArtworkBytes(bmp)
                                        if (processed != null && processed.isNotEmpty()) {
                                            loadedBytes = processed
                                            return@use
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (loadedBytes != null) break
                } catch (_: Exception) {}
            }
        }

        // 4. Save 800x800 artwork to offline disk so already-downloaded songs stay 100% crisp offline
        if (loadedBytes != null && ctx != null && !songId.isNullOrBlank()) {
            val cleanSongId = songId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (cleanSongId.isNotEmpty() && DownloadManager.isSongDownloaded(songId)) {
                try {
                    val storage = DownloadManager.getStorageDir(ctx)
                    if (!storage.exists()) storage.mkdirs()
                    val offlineCover = File(storage, "$cleanSongId.jpg")
                    offlineCover.writeBytes(loadedBytes)
                    Log.d(TAG, "Cached 800x800 offline artwork to disk for $songId")
                } catch (_: Exception) {}
            }
        }

        loadedBytes
    }

    // v7.25: Audio DSP & Built-In In-App Equalizer Engine
    data class EqBand(
        val index: Int,
        val centerFreqHz: Int,
        val levelMillibels: Short
    )

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var isNormalizationEnabled: Boolean = true
    private val _crossfadeSeconds = MutableStateFlow(0)
    private var baseTrackVolume: Float = 1.0f
    val crossfadeSeconds = _crossfadeSeconds.asStateFlow()
    private var isCrossfading = false
    private var lastCrossfadedSongId: String? = null

    fun setCrossfadeSeconds(seconds: Int) {
        val s = seconds.coerceIn(0, 12)
        _crossfadeSeconds.value = s
        appContext?.getSharedPreferences("audio_fx_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putInt("crossfade_seconds", s)
            ?.apply()
    }

    suspend fun fadeVolume(from: Float, to: Float, durationMs: Long) {
        val player = exoPlayer ?: return
        val steps = 12
        val stepDelay = (durationMs / steps).coerceAtLeast(15L)
        for (i in 0..steps) {
            val fraction = i / steps.toFloat()
            val vol = from + (to - from) * fraction
            player.volume = vol.coerceIn(0f, 1f)
            delay(stepDelay)
        }
    }

    /**
     * Fades in towards the CURRENT track's normalization gain (read live, so the new song's
     * loudness correction is honoured), starting only once the new stream is really playing
     * (max 10 s wait) so the fade is heard instead of being consumed while the track loads.
     */
    private suspend fun fadeInToBaseVolume(durationMs: Long) {
        var waited = 0L
        while (waited < 10_000L) {
            val p = exoPlayer ?: return
            if (streamReadyAt != 0L && p.isPlaying) break
            delay(50L)
            waited += 50L
        }
        val player = exoPlayer ?: return
        val steps = 12
        val stepDelay = (durationMs / steps).coerceAtLeast(15L)
        val from = player.volume
        for (i in 0..steps) {
            val target = baseTrackVolume
            player.volume = (from + (target - from) * (i / steps.toFloat())).coerceIn(0f, 1f)
            delay(stepDelay)
        }
        player.volume = baseTrackVolume.coerceIn(0f, 1f)
    }

    private val _autoplayEnabled = MutableStateFlow(true)
    val autoplayEnabled = _autoplayEnabled.asStateFlow()

    private var isFetchingAutoplay = false

    fun setAutoplayEnabled(enabled: Boolean) {
        _autoplayEnabled.value = enabled
        savePrefs()
        if (enabled) {
            checkAndTriggerAutoplay()
        }
    }

    fun checkAndTriggerAutoplay() {
        if (!_autoplayEnabled.value) return
        if (isOfflineQueue) return
        if (_repeatMode.value != RepeatMode.OFF) return
        val current = _currentSong.value ?: return
        val queue = _queue.value
        val currentIndex = queue.indexOfFirst { it.id == current.id }
        if (currentIndex != -1 && currentIndex >= queue.size - 1 && !isFetchingAutoplay) {
            fetchAndAppendSimilarTracks(current)
        }
    }

    private fun fetchAndAppendSimilarTracks(seedSong: Song) {
        if (isFetchingAutoplay) return
        isFetchingAutoplay = true
        scope.launch {
            try {
                val similar = YouTubeExtractor.fetchSimilarSongs(seedSong.id, seedSong.artist)
                val currentIds = _queue.value.map { it.id }.toSet()
                val fresh = similar.filter { it.id !in currentIds }
                if (fresh.isNotEmpty()) {
                    val updated = _queue.value + fresh
                    _queue.value = updated
                    originalQueue = updated
                    saveState()
                    triggerPrefetchForNextTrack()
                    Log.d(TAG, "Autoplay appended ${fresh.size} radio tracks to queue")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Autoplay fetch error: ${e.javaClass.simpleName}")
            } finally {
                isFetchingAutoplay = false
            }
        }
    }

    private val _isNormalizationEnabled = MutableStateFlow(true)
    val isNormalizationEnabledFlow = _isNormalizationEnabled.asStateFlow()
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled = _eqEnabled.asStateFlow()

    private val _eqBands = MutableStateFlow<List<EqBand>>(emptyList())
    val eqBands = _eqBands.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow<Short>(0)
    val bassBoostStrength = _bassBoostStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow<Short>(0)
    val virtualizerStrength = _virtualizerStrength.asStateFlow()

    private val _currentPreset = MutableStateFlow("Flat")
    val currentPreset = _currentPreset.asStateFlow()

    fun getAudioSessionId(): Int = exoPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET

    fun ensureEffectsInitialized(context: Context) {
        val player = getOrCreatePlayer(context)
        val sid = player.audioSessionId
        if (sid != C.AUDIO_SESSION_ID_UNSET && sid > 0) {
            initAudioEffects(sid)
        }
    }

    private fun releaseDspEffectsOnly() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
            equalizer = null
        } catch (_: Exception) {}
        try {
            bassBoost?.enabled = false
            bassBoost?.release()
            bassBoost = null
        } catch (_: Exception) {}
        try {
            virtualizer?.enabled = false
            virtualizer?.release()
            virtualizer = null
        } catch (_: Exception) {}
    }

    fun initAudioEffects(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            applyLoudnessEnhancer(sessionId, _currentSong.value?.loudnessDb)
            appContext?.let { ctx ->
                val prefs = ctx.getSharedPreferences("audio_fx_prefs", Context.MODE_PRIVATE)
                val savedEq = prefs.getBoolean("eq_enabled", false)
                _eqEnabled.value = savedEq
                if (!savedEq) {
                    releaseDspEffectsOnly()
                    if (_eqBands.value.isEmpty()) {
                        val defaultFreqs = listOf(60, 230, 910, 3600, 14000)
                        _eqBands.value = defaultFreqs.mapIndexed { i, f ->
                            EqBand(i, f, prefs.getInt("band_$i", 0).toShort())
                        }
                    }
                    return
                }
                try {
                    val openSessionIntent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                    ctx.sendBroadcast(openSessionIntent)
                } catch (_: Exception) {}
            }

            try {
                equalizer?.release()
                val hasNonZero = _eqBands.value.any { it.levelMillibels != 0.toShort() }
                val eq = Equalizer(100, sessionId).apply {
                    enabled = _eqEnabled.value && hasNonZero
                }
                equalizer = eq
                val bands = mutableListOf<EqBand>()
                val numBands = eq.numberOfBands.toInt()
                for (i in 0 until numBands) {
                    val centerFreq = eq.getCenterFreq(i.toShort()) / 1000
                    val level = eq.getBandLevel(i.toShort())
                    bands.add(EqBand(i, centerFreq, level))
                }
                if (bands.isEmpty()) {
                    val defaultFreqs = listOf(60, 230, 910, 3600, 14000)
                    for (i in defaultFreqs.indices) {
                        bands.add(EqBand(i, defaultFreqs[i], 0))
                    }
                }
                _eqBands.value = bands
            } catch (e: Exception) {
                Log.w(TAG, "Equalizer init error on session $sessionId", e)
                if (_eqBands.value.isEmpty()) {
                    val defaultFreqs = listOf(60, 230, 910, 3600, 14000)
                    _eqBands.value = defaultFreqs.mapIndexed { i, f -> EqBand(i, f, 0) }
                }
            }

            try {
                bassBoost?.release()
                val bb = BassBoost(100, sessionId).apply {
                    val strength = _bassBoostStrength.value
                    enabled = _eqEnabled.value && strength > 0
                    if (strengthSupported) {
                        setStrength(strength)
                    }
                }
                bassBoost = bb
            } catch (e: Exception) {
                Log.w(TAG, "BassBoost init error on session $sessionId", e)
            }

            try {
                virtualizer?.release()
                val virt = Virtualizer(100, sessionId).apply {
                    try {
                        forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL)
                    } catch (_: Exception) {}
                    val strength = _virtualizerStrength.value
                    enabled = _eqEnabled.value && strength > 0
                    if (strengthSupported) {
                        setStrength(strength)
                    }
                }
                virtualizer = virt
            } catch (e: Exception) {
                Log.w(TAG, "Virtualizer init error on session $sessionId", e)
            }

            loadSavedEqSettings()
        } catch (e: Exception) {
            Log.w(TAG, "initAudioEffects failed", e)
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        saveEqSettings()
        if (!enabled) {
            releaseDspEffectsOnly()
        } else {
            val sid = getAudioSessionId()
            if (sid != C.AUDIO_SESSION_ID_UNSET && sid > 0) {
                initAudioEffects(sid)
            }
        }
    }

    fun setBandLevel(bandIndex: Int, levelMillibels: Short, saveToDisk: Boolean = true, isManualAdjustment: Boolean = false) {
        try {
            if (isManualAdjustment && !_eqEnabled.value && levelMillibels != 0.toShort()) {
                _eqEnabled.value = true
            }
            _eqBands.value = _eqBands.value.map { band ->
                if (band.index == bandIndex) band.copy(levelMillibels = levelMillibels) else band
            }
            val hasNonZero = _eqBands.value.any { it.levelMillibels != 0.toShort() }
            equalizer?.let { eq ->
                val range = try { eq.bandLevelRange } catch (_: Exception) { shortArrayOf(-1500, 1500) }
                val min = range[0]
                val max = range[1]
                val clamped = levelMillibels.coerceIn(min, max)
                eq.setBandLevel(bandIndex.toShort(), clamped)
                eq.enabled = _eqEnabled.value && hasNonZero
            }
            if (isManualAdjustment) {
                _currentPreset.value = "Custom"
            }
            if (saveToDisk) saveEqSettings()
        } catch (e: Exception) {
            Log.w(TAG, "setBandLevel failed for band $bandIndex", e)
        }
    }

    fun setBassBoost(strength: Short, saveToDisk: Boolean = true, isManualAdjustment: Boolean = false) {
        try {
            val clamped = strength.coerceIn(0, 1000)
            if (isManualAdjustment && !_eqEnabled.value && clamped > 0) _eqEnabled.value = true
            bassBoost?.let { bb ->
                bb.enabled = _eqEnabled.value && clamped > 0
                if (bb.strengthSupported) {
                    bb.setStrength(clamped)
                }
            }
            _bassBoostStrength.value = clamped
            if (isManualAdjustment) {
                _currentPreset.value = "Custom"
            }
            if (saveToDisk) saveEqSettings()
        } catch (e: Exception) {
            Log.w(TAG, "setBassBoost failed", e)
        }
    }

    fun setVirtualizer(strength: Short, saveToDisk: Boolean = true, isManualAdjustment: Boolean = false) {
        try {
            val clamped = strength.coerceIn(0, 1000)
            if (isManualAdjustment && !_eqEnabled.value && clamped > 0) _eqEnabled.value = true
            virtualizer?.let { virt ->
                try {
                    virt.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL)
                } catch (_: Exception) {}
                virt.enabled = _eqEnabled.value && clamped > 0
                if (virt.strengthSupported) {
                    virt.setStrength(clamped)
                }
            }
            _virtualizerStrength.value = clamped
            if (isManualAdjustment) {
                _currentPreset.value = "Custom"
            }
            if (saveToDisk) saveEqSettings()
        } catch (e: Exception) {
            Log.w(TAG, "setVirtualizer failed", e)
        }
    }

    fun setEqPreset(presetName: String) {
        _currentPreset.value = presetName
        if (presetName != "Flat" && !_eqEnabled.value) _eqEnabled.value = true
        when (presetName) {
            "Flat" -> {
                _eqBands.value.forEach { setBandLevel(it.index, 0, saveToDisk = false) }
                setBassBoost(0, saveToDisk = false)
                setVirtualizer(0, saveToDisk = false)
            }
            "Bass Boost" -> {
                setBassBoost(750, saveToDisk = false)
                setVirtualizer(200, saveToDisk = false)
                if (_eqBands.value.isNotEmpty()) setBandLevel(0, 600, saveToDisk = false)
                if (_eqBands.value.size > 1) setBandLevel(1, 400, saveToDisk = false)
            }
            "Vocal Booster" -> {
                setBassBoost(100, saveToDisk = false)
                if (_eqBands.value.size >= 5) {
                    setBandLevel(0, -200, saveToDisk = false)
                    setBandLevel(1, 100, saveToDisk = false)
                    setBandLevel(2, 600, saveToDisk = false)
                    setBandLevel(3, 400, saveToDisk = false)
                    setBandLevel(4, 100, saveToDisk = false)
                }
            }
            "Rock" -> {
                setBassBoost(400, saveToDisk = false)
                if (_eqBands.value.size >= 5) {
                    setBandLevel(0, 500, saveToDisk = false)
                    setBandLevel(1, 200, saveToDisk = false)
                    setBandLevel(2, -100, saveToDisk = false)
                    setBandLevel(3, 300, saveToDisk = false)
                    setBandLevel(4, 500, saveToDisk = false)
                }
            }
            "Pop" -> {
                setBassBoost(300, saveToDisk = false)
                if (_eqBands.value.size >= 5) {
                    setBandLevel(0, 200, saveToDisk = false)
                    setBandLevel(1, 400, saveToDisk = false)
                    setBandLevel(2, 500, saveToDisk = false)
                    setBandLevel(3, 200, saveToDisk = false)
                    setBandLevel(4, 100, saveToDisk = false)
                }
            }
            "Electronic" -> {
                setBassBoost(600, saveToDisk = false)
                setVirtualizer(400, saveToDisk = false)
                if (_eqBands.value.size >= 5) {
                    setBandLevel(0, 600, saveToDisk = false)
                    setBandLevel(1, 300, saveToDisk = false)
                    setBandLevel(2, 0, saveToDisk = false)
                    setBandLevel(3, 300, saveToDisk = false)
                    setBandLevel(4, 600, saveToDisk = false)
                }
            }
        }
        _currentPreset.value = presetName
        saveEqSettings()
    }

    fun saveEqSettings() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("audio_fx_prefs", Context.MODE_PRIVATE).edit()
        prefs.putBoolean("eq_enabled", _eqEnabled.value)
        prefs.putString("preset", _currentPreset.value)
        prefs.putInt("bass_boost", _bassBoostStrength.value.toInt())
        prefs.putInt("virtualizer", _virtualizerStrength.value.toInt())
        _eqBands.value.forEach { band ->
            prefs.putInt("band_${band.index}", band.levelMillibels.toInt())
        }
        prefs.apply()
    }

    private fun loadSavedEqSettings() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("audio_fx_prefs", Context.MODE_PRIVATE)
        val savedEqEnabled = prefs.getBoolean("eq_enabled", false)
        _eqEnabled.value = savedEqEnabled
        val savedPreset = prefs.getString("preset", "Flat") ?: "Flat"
        _currentPreset.value = savedPreset
        val savedBass = prefs.getInt("bass_boost", 0).toShort()
        val savedVirt = prefs.getInt("virtualizer", 0).toShort()
        val savedNorm = prefs.getBoolean("volume_norm_enabled", true)
        val savedCrossfade = prefs.getInt("crossfade_seconds", 0)
        _crossfadeSeconds.value = savedCrossfade
        isNormalizationEnabled = savedNorm
        _isNormalizationEnabled.value = savedNorm
        try { loudnessEnhancer?.enabled = savedNorm } catch (_: Exception) {}
        _bassBoostStrength.value = savedBass
        _virtualizerStrength.value = savedVirt
        bassBoost?.let { bb ->
            bb.enabled = _eqEnabled.value && savedBass > 0
            if (bb.strengthSupported) bb.setStrength(savedBass)
        }
        virtualizer?.let { virt ->
            try { virt.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL) } catch (_: Exception) {}
            virt.enabled = _eqEnabled.value && savedVirt > 0
            if (virt.strengthSupported) virt.setStrength(savedVirt)
        }

        equalizer?.let { eq ->
            val numBands = eq.numberOfBands.toInt()
            val updatedBands = mutableListOf<EqBand>()
            var anyNonZero = false
            for (i in 0 until numBands) {
                val savedLevel = prefs.getInt("band_$i", 0).toShort()
                if (savedLevel != 0.toShort()) anyNonZero = true
                try {
                    eq.setBandLevel(i.toShort(), savedLevel)
                } catch (_: Exception) {}
                val centerFreq = eq.getCenterFreq(i.toShort()) / 1000
                updatedBands.add(EqBand(i, centerFreq, savedLevel))
            }
            _eqBands.value = updatedBands
            try { eq.enabled = savedEqEnabled && anyNonZero } catch (_: Exception) {}
        }
    }

    fun releaseAudioEffects() {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            equalizer?.release()
            equalizer = null
            bassBoost?.release()
            bassBoost = null
            virtualizer?.release()
            virtualizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio effects", e)
        }
    }

    fun openSystemSoundSettings(context: Context) {
        val sessionId = getAudioSessionId()
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            }
        }
        try {
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                val openSessionIntent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                }
                context.sendBroadcast(openSessionIntent)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                val soundSettings = Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                if (soundSettings.resolveActivity(context.packageManager) != null) {
                    context.startActivity(soundSettings)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not open system sound settings", e)
        }
    }

    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        isNormalizationEnabled = enabled
        _isNormalizationEnabled.value = enabled
        try {
            applyLoudnessEnhancer(getAudioSessionId(), _currentSong.value?.loudnessDb)
        } catch (e: Exception) {
            Log.w(TAG, "Error toggling LoudnessEnhancer", e)
        }
        val ctx = appContext ?: return
        ctx.getSharedPreferences("audio_fx_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("volume_norm_enabled", enabled)
            .apply()
    }

    fun applyLoudnessEnhancer(sessionId: Int, trackLoudnessDb: Float? = null) {
        // Release any hardware LoudnessEnhancer compressor so audio stays uncolored & free of limiter harshness.
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        } catch (_: Exception) {}

        // Official YouTube Music behavior: linear attenuation only when a track exceeds -14 LUFS (loudnessDb > 0).
        val targetVol = if (isNormalizationEnabled && trackLoudnessDb != null && trackLoudnessDb > 0f) {
            Math.pow(10.0, (-trackLoudnessDb / 20.0)).toFloat().coerceIn(0.25f, 1.0f)
        } else {
            1.0f
        }
        baseTrackVolume = targetVol
        if (!isCrossfading) {
            try { exoPlayer?.volume = targetVol } catch (_: Exception) {}
        }
    }

    private val _isFullPlayerExpanded = MutableStateFlow(false)
    val isFullPlayerExpanded = _isFullPlayerExpanded.asStateFlow()

    fun setFullPlayerExpanded(expanded: Boolean) {
        _isFullPlayerExpanded.value = expanded
    }

    fun setPlaybackSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 2.0f)
        _playbackSpeed.value = s
        applyPlaybackParameters()
    }

    fun setPitchSemitones(semitones: Int) {
        val st = semitones.coerceIn(-6, 6)
        _pitchSemitones.value = st
        applyPlaybackParameters()
    }

    fun setSpeedAndPitch(speed: Float, semitones: Int) {
        val s = speed.coerceIn(0.25f, 2.0f)
        val st = semitones.coerceIn(-6, 6)
        _playbackSpeed.value = s
        _pitchSemitones.value = st
        applyPlaybackParameters()
    }

    fun resetSpeedAndPitch() {
        _playbackSpeed.value = 1.0f
        _pitchSemitones.value = 0
        applyPlaybackParameters()
    }

    private fun applyPlaybackParameters() {
        val speed = _playbackSpeed.value
        val semitones = _pitchSemitones.value
        val pitchMultiplier = Math.pow(2.0, semitones / 12.0).toFloat()
        exoPlayer?.setPlaybackParameters(androidx.media3.common.PlaybackParameters(speed, pitchMultiplier))
    }

    /** Cache key unique per song AND format, so Opus/AAC/HLS bytes of one song can never mix. */
    internal fun cacheKeyFor(songId: String, format: String): String =
        songId + "|" + format.replace(" ", "")

    private const val PREBUFFER_BYTES = 400_000L // ~25 s of Opus: makes the next track start instantly

    private fun progressiveCacheFactory(context: Context, userAgent: String?): CacheDataSource.Factory {
        val upstream = androidx.media3.datasource.DefaultDataSource.Factory(
            context.applicationContext,
            YoutubeDataSource.Factory(playbackOkHttp, userAgent = userAgent)
        )
        return CacheDataSource.Factory()
            .setCache(AudioCache.get(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Downloads the first seconds of the next track into the audio cache (progressive tiers only). */
    private fun prebufferStart(videoId: String, stream: YouTubeExtractor.ResolvedStream) {
        val ctx = appContext ?: return
        if (stream.url.contains(".m3u8") || stream.client == VisionOsHlsResolver.TIER_ID) return
        scope.launch(Dispatchers.IO) {
            try {
                val dataSource = progressiveCacheFactory(ctx, stream.userAgent).createDataSource()
                val spec = androidx.media3.datasource.DataSpec.Builder()
                    .setUri(Uri.parse(stream.url))
                    .setPosition(0L)
                    .setLength(PREBUFFER_BYTES)
                    .setKey(cacheKeyFor(videoId, stream.format))
                    .build()
                androidx.media3.datasource.cache.CacheWriter(dataSource, spec, null, null).cache()
                Log.d(TAG, "Pre-buffered start of $videoId")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d(TAG, "Pre-buffer skipped for $videoId: ${e.javaClass.simpleName}")
            }
        }
    }

    fun initialize(player: ExoPlayer) {
        PrefetchManager.onResolved = { id, stream -> prebufferStart(id, stream) }
        this.exoPlayer = player
        applyPlaybackParameters()
        setupPlayerListeners()
        initAudioEffects(player.audioSessionId)
    }

    // Dedicated playback OkHttp client (NO callTimeout, standard connect/read timeouts)
    private val playbackOkHttp: okhttp3.OkHttpClient = com.ytmusic.clone.data.network.SecureHttp.newBuilder(com.ytmusic.clone.data.network.PolicyScope.STREAM)
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Dedicated MediaSource per tier (also used by the Settings soak test so it plays through the
     * exact same data sources as real playback; v7.19):
     * - HLS uses HlsMediaSource with plain OkHttpDataSource and VisionOsProfile.USER_AGENT
     * - Progressive uses ProgressiveMediaSource with YoutubeDataSource
     */
    internal fun createMediaSource(
        context: Context,
        mediaItem: MediaItem,
        isHls: Boolean,
        transferListener: androidx.media3.datasource.TransferListener? = null,
        userAgent: String? = null,
        bypassCache: Boolean = false,
        secondaryAudioUrl: String? = null,
        videoStreamId: String? = null,
        audioFormatLabel: String = ""
    ): androidx.media3.exoplayer.source.MediaSource {
        val cache = AudioCache.get(context)

        return if (isHls) {
            val ua = userAgent ?: VisionOsProfile.USER_AGENT
            val hlsDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(playbackOkHttp)
                .setUserAgent(ua)
            transferListener?.let { hlsDataSourceFactory.setTransferListener(it) }

            val factory: androidx.media3.datasource.DataSource.Factory = if (bypassCache) {
                hlsDataSourceFactory
            } else {
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(hlsDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }

            androidx.media3.exoplayer.hls.HlsMediaSource.Factory(factory)
                .createMediaSource(mediaItem)
        } else {
            val progressiveDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                context.applicationContext,
                YoutubeDataSource.Factory(playbackOkHttp, userAgent = userAgent)
            )
            transferListener?.let { progressiveDataSourceFactory.setTransferListener(it) }

            val factory: androidx.media3.datasource.DataSource.Factory = if (bypassCache) {
                progressiveDataSourceFactory
            } else {
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(progressiveDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }

            val primarySource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory)
                .createMediaSource(mediaItem)

            if (!secondaryAudioUrl.isNullOrBlank()) {
                val vidKey = videoStreamId ?: mediaItem.mediaId
                val fmtKey = audioFormatLabel.replace(" ", "").ifEmpty { "opus" }
                val cachedAudioFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(progressiveDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                val audioItem = MediaItem.Builder()
                    .setUri(Uri.parse(secondaryAudioUrl))
                    .setMediaId(vidKey + "_vaudio")
                    .setCustomCacheKey("vaudio|$vidKey|$fmtKey")
                    .build()
                val audioSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cachedAudioFactory)
                    .createMediaSource(audioItem)
                MergingMediaSource(true, true, primarySource, audioSource)
            } else {
                primarySource
            }
        }
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        this.appContext = context.applicationContext
        loadPrefs(context)
        setupNetworkMonitor(context)
        if (exoPlayer == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()

            val renderersFactory = DefaultRenderersFactory(context.applicationContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    25_000, // minBufferMs
                    60_000, // maxBufferMs
                    1_500,  // bufferForPlaybackMs
                    3_000   // bufferForPlaybackAfterRebufferMs
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val player = ExoPlayer.Builder(context.applicationContext)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .build()

            // Disable video track globally so HLS video streams/chunks are never decoded
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                // HLS fallback: always take the best audio rendition instead of ExoPlayer's default pick.
                .setForceHighestSupportedBitrate(true)
                .build()

            player.repeatMode = if (_repeatMode.value == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            initialize(player)
        }
        return exoPlayer!!
    }

    private fun setupNetworkMonitor(context: Context) {
        if (networkCallbackRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    PrefetchManager.clearCache()
                    scope.launch(Dispatchers.Main) {
                        delay(800L) // Brief delay for DNS/routing stabilization on tower switch
                        if (isOfflinePaused && _currentSong.value != null && !_isPlaying.value) {
                            isOfflinePaused = false
                            _playbackError.value = null
                            val song = _currentSong.value ?: return@launch
                            DefaultResolverChain.instance.clearVideoMemory(song.id)
                            failedClients.clear()
                            showMessage("Connection restored — resuming playback")
                            val resumeAt = _currentPosition.value.coerceAtLeast(resumePositionMs)
                            playSong(null, song, emptyList(), resumeAt)
                        } else {
                            triggerPrefetchForNextTrack()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    PrefetchManager.clearCache()
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network monitor", e)
        }
    }

    private fun setupPlayerListeners() {
        exoPlayer?.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                initAudioEffects(audioSessionId)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) {
                    isOfflinePaused = false
                    _playbackError.value = null
                    appContext?.let { acquireLocks(it) }
                    startTrackingProgress()
                } else {
                    stopTrackingProgress()
                    saveState() // paused or stopped: remember the position
                    if (!_isBuffering.value && playJob?.isActive != true && exoPlayer?.playWhenReady != true) {
                        releaseLocks()
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                val buffering = (state == Player.STATE_BUFFERING)
                _isBuffering.value = buffering
                if (buffering) {
                    appContext?.let { acquireLocks(it) }
                    startBufferingWatchdog()
                } else {
                    stopBufferingWatchdog()
                    if (!_isPlaying.value && playJob?.isActive != true && exoPlayer?.playWhenReady != true) {
                        releaseLocks()
                    }
                }

                if (state == Player.STATE_READY) {
                    val durMs = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                    _duration.value = durMs
                    val song = _currentSong.value
                    val durSec = durMs / 1000L
                    if (song != null && durSec > 0L && song.durationSeconds <= 0L && !currentIsVideoStream) {
                        _currentSong.value = song.copy(durationSeconds = durSec)
                    }
                    if (currentIsVideoStream && song != null && currentVideoId != null && durSec > 0L) {
                        val vid = currentVideoId!!
                        Log.i(TAG, "Video STATE_READY: exoDur=${durSec}s vs songDur=${song.durationSeconds}s (vid=$vid)")
                        if (song.durationSeconds > 0L && !OfficialVideoMatcher.durationClose(song.durationSeconds, durSec, official = true)) {
                            Log.w(TAG, "Video duration mismatch on STATE_READY ($durSec vs ${song.durationSeconds}): blacklisting $vid")
                            wrongVideoIds.getOrPut(song.id) { ConcurrentHashMap.newKeySet() }.add(vid)
                            YouTubeExtractor.forgetVideoLookup(song.id)
                            if (!hasRetriedWrongVideo) {
                                hasRetriedWrongVideo = true
                                launchLoad(song, 0L, isRetry = false, targetClient = null, forceFresh = true, explicitTap = true, bypassNegCache = true)
                                return
                            } else {
                                dropVideoToAudio("duration_mismatch_on_ready", "Video didn't match song — playing audio")
                                launchLoad(song, 0L, isRetry = true, targetClient = null, forceFresh = false)
                                return
                            }
                        } else {
                            YouTubeExtractor.confirmVideoPlayback(song.id, vid, durSec)
                            _videoLoadState.value = VideoLoadState.Playing(vid)
                        }
                    }
                    appContext?.let { acquireLocks(it) }
                    if (streamReadyAt == 0L) {
                        streamReadyAt = SystemClock.elapsedRealtime()
                        currentClient?.let { YouTubeExtractor.rememberWorkingClient(it) }
                    }
                } else if (state == Player.STATE_ENDED) {
                    onTrackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.errorCodeName}", error)
                _isPlaying.value = false
                stopBufferingWatchdog()

                // Opus decoder missing/broken on this device: retry the same track as AAC.
                if (handleDecoderFailure(error)) return

                if (currentIsVideoStream) {
                    val errHttp = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<YtHttpException>().firstOrNull()
                    Log.w(TAG, "Video onPlayerError: ${error.errorCodeName}, http=${errHttp?.code}, vid=$currentVideoId")
                }

                // Merged mid-track failover across resilient tiers
                if (handlePlaybackError(error)) return

                // Non-retryable or exhausted video error: always fall back cleanly to audio instead of pausing!
                if (currentIsVideoStream) {
                    val song = _currentSong.value
                    val pos = (exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: lastLoadStartMs)
                    if (song != null) {
                        dropVideoToAudio("video_error_${error.errorCodeName}", "Video couldn't load — playing audio")
                        launchLoad(song, pos, isRetry = true, targetClient = null, forceFresh = false)
                        return
                    }
                }

                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<YtHttpException>()
                    .firstOrNull()
                val isNet = !isNetworkAvailable(appContext) || isNetworkError(error)
                val friendlyMsg = when {
                    !isNetworkAvailable(appContext) -> "No internet connection"
                    isNet -> "Weak or unstable connection"
                    http != null -> "Stream rejected (HTTP ${http.code})"
                    else -> "Playback interrupted"
                }
                enterRetryablePauseState(friendlyMsg, isNetworkIssue = isNet)
            }
        })
    }

    private fun handleDecoderFailure(error: PlaybackException): Boolean {
        val code = error.errorCode
        val isDecoderError = code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        if (!isDecoderError) return false
        if (currentIsVideoStream) {
            val vsong = _currentSong.value ?: return false
            val resume = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
            if (!com.ytmusic.clone.data.network.VideoCodecPolicy.avcOnly) {
                // First failure: this phone can't decode VP9/AV1 here. Stick to H.264 and retry.
                com.ytmusic.clone.data.network.VideoCodecPolicy.avcOnly = true
                Log.w(TAG, "Video decoder failure (${error.errorCodeName}); retrying ${vsong.id} with H.264 only")
            } else if (decoderMaxHeight > 480) {
                decoderMaxHeight = if (minOf(_videoQuality.value.maxHeight, decoderMaxHeight) > 720) 720 else 480
                Log.w(TAG, "Video decoder failure again; capping height at ${decoderMaxHeight}p")
                showMessage("Lowering video quality for this device")
                applyVideoTrackParameters(true)
            } else {
                showMessage("This device can't play that video — switching to audio")
                _isVideoMode.value = false
                applyVideoTrackParameters(false)
            }
            _isBuffering.value = true
            launchLoad(vsong, resume, isRetry = true, forceFresh = true)
            return true
        }
        if (com.ytmusic.clone.data.network.AudioFormatPicker.preferAac) return false // AAC already tried
        val song = _currentSong.value ?: return false
        if (currentClient == null) return false // offline file: nothing to re-resolve
        com.ytmusic.clone.data.network.AudioFormatPicker.preferAac = true
        val resumeAt = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
        PrefetchManager.clearCache()
        DefaultResolverChain.instance.clearVideoMemory(song.id)
        Log.w(TAG, "Decoder failure (${error.errorCodeName}); retrying ${song.id} as AAC")
        launchLoad(song, resumeAt, isRetry = true, forceFresh = true)
        return true
    }

    private fun startBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = scope.launch {
            delay(BUFFERING_WATCHDOG_TIMEOUT_MS)
            if (_isBuffering.value && exoPlayer?.playWhenReady == true) {
                Log.w(TAG, "Buffering watchdog: stalled for ${BUFFERING_WATCHDOG_TIMEOUT_MS / 1000}s")
                val timeoutError = PlaybackException(
                    "Stream buffering stalled (network timeout)",
                    null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                )
                if (!handlePlaybackError(timeoutError)) {
                    val msg = if (isNetworkAvailable(appContext)) "Connection timed out" else "No internet connection"
                    enterRetryablePauseState(msg, isNetworkIssue = true)
                }
            }
        }
    }

    private fun stopBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
    }

    private fun isNetworkAvailable(context: Context?): Boolean {
        val ctx = context ?: return true
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun isNetworkError(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            ) return true
        }
        return generateSequence(error) { it.cause }.any {
            it is java.net.UnknownHostException ||
                it is java.net.ConnectException ||
                it is java.net.NoRouteToHostException ||
                it is java.net.SocketTimeoutException ||
                it is java.io.EOFException ||
                it is java.io.IOException
        }
    }

    private fun enterRetryablePauseState(reason: String, isNetworkIssue: Boolean) {
        _isBuffering.value = false
        _isPlaying.value = false
        stopBufferingWatchdog()
        val curPos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        if (curPos > 0L) {
            resumePositionMs = curPos
            _currentPosition.value = curPos
        } else if (lastLoadStartMs > 0L) {
            resumePositionMs = lastLoadStartMs
        }
        isOfflinePaused = isNetworkIssue
        _playbackError.value = reason
        _currentSong.value?.let { DefaultResolverChain.instance.clearVideoMemory(it.id) }
        failedClients.clear()
        Log.i(TAG, "Playback paused for retry ($reason, autoResume=$isNetworkIssue)")
    }

    /**
     * Retries the current song from the exact position where it stopped,
     * clearing any temporary per-video tier failures.
     */
    fun retryCurrentSong() {
        val song = _currentSong.value ?: return
        _playbackError.value = null
        isOfflinePaused = false
        failoverCount = 0
        retryCount = 0
        hasRetriedSameClient = false
        failedClients.clear()
        DefaultResolverChain.instance.clearVideoMemory(song.id)
        currentVideoId = null
        hasRetriedWrongVideo = false
        videoFallbackCountForTrack = 0
        val resumeAt = _currentPosition.value.coerceAtLeast(resumePositionMs)
        playSong(null, song, emptyList(), resumeAt)
    }

    private fun showMessage(msg: String) {
        appContext?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
    }

    /** The last lines of YouTubeExtractor.lastDiagnostics (a long trace would fill the screen). */
    private fun diagnosticsForToast(): String =
        YouTubeExtractor.lastDiagnostics.lines().takeLast(10).joinToString("\n")

    /**
     * Unified error handling and mid-track failover:
     *  - On HTTP 403: does ONE forceFresh same-tier retry before switching tiers.
     *  - Stalls, timeouts, and EOF on weak network do NOT count against the circuit breaker.
     *  - Played > 60s rule: streams that played fine for >60s are treated as expired links
     *    and do NOT penalize the tier's health.
     *  - BAD_HTTP_STATUS is restricted to 403/410.
     *  - Mid-track failover swaps MediaItem cleanly with seekTo(position) and no retry loops.
     */
    private fun handlePlaybackError(error: PlaybackException): Boolean {
        val song = _currentSong.value ?: return false
        val client = currentClient ?: return false // offline file: nothing to switch
        val player = exoPlayer ?: return false

        // 1. Offline check (explicit DNS/connect failure or no active internet capability)
        val offline = !isNetworkAvailable(appContext) || generateSequence<Throwable>(error) { it.cause }.any {
            it is java.net.UnknownHostException || it is java.net.ConnectException ||
                it is java.net.NoRouteToHostException
        }
        if (offline) {
            enterRetryablePauseState("No internet connection", isNetworkIssue = true)
            return true
        }

        // 2. Played > 60s rule: streams that played fine for >60s likely just expired naturally
        val playedLong = streamReadyAt != 0L && (SystemClock.elapsedRealtime() - streamReadyAt) > 60_000L
        if (playedLong) {
            failoverCount = 0
            hasRetriedSameClient = false
            retryCount = 0
            streamReadyAt = 0L
        }

        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<YtHttpException>()
            .firstOrNull()
        val httpCode = http?.code
        val isHttp403 = httpCode == 403
        val isHttp410 = httpCode == 410
        val isBadHttpStatus = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        val isStallOrTimeout = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            generateSequence<Throwable>(error) { it.cause }.any { it is java.io.EOFException || it is java.net.SocketTimeoutException }

        val pos = player.currentPosition.coerceAtLeast(0L)
        val resumePos = if (pos > 0L) pos else lastLoadStartMs

        val decision = FailoverEngine.evaluatePlaybackError(
            errorCode = error.errorCode,
            httpStatusCode = httpCode,
            isEofOrTimeout = isStallOrTimeout,
            hasRetriedSameClient = hasRetriedSameClient,
            failoverCount = failoverCount,
            maxFailovers = DefaultResolverChain.instance.resolvers.size - 1,
            playedLong = playedLong
        )

        when (decision.action) {
            FailoverDecision.Action.NONE -> {
                if (currentIsVideoStream) {
                    val pos2 = resumePos
                    dropVideoToAudio("failover_exhausted_${error.errorCodeName}", "Video keeps failing — playing audio")
                    launchLoad(song, pos2, isRetry = true, targetClient = null, forceFresh = false)
                    return true
                }
                return false
            }

            FailoverDecision.Action.RETRY_SAME_TIER_FRESH -> {
                hasRetriedSameClient = true
                retryCount++
                Log.i(TAG, "HTTP 403 on $client -> forceFresh retry on same tier ($retryCount/$MAX_AUTO_RETRIES)")
                YouTubeExtractor.addDiagnostic("HTTP 403 on $client -> forceFresh retry on same tier")
                if (client == AndroidVrProgressiveResolver.TIER_ID) {
                    AndroidVrPlayerClient.invalidateCache(song.id)
                } else {
                    VisionOsPlayerClient.invalidateCache(song.id)
                }
                if (!retryToastShown) {
                    retryToastShown = true
                    showMessage("Refreshing stream link (HTTP 403)...")
                }
                _isBuffering.value = true
                _currentPosition.value = resumePos
                launchLoad(song, resumePos, isRetry = true, targetClient = client, forceFresh = true)
                return true
            }

            FailoverDecision.Action.FAILOVER_NEXT_TIER -> {
                failoverCount++

                // Video stream broke: the audio tier chain below would silently swap in an
                // audio-only stream while the UI still says "video". Reload through the video
                // path with a fresh link; after repeated failures drop to audio on purpose.
                if (currentIsVideoStream) {
                    _isBuffering.value = true
                    _currentPosition.value = resumePos
                    videoFallbackCountForTrack++
                    if (videoFallbackCountForTrack >= 2) {
                        dropVideoToAudio("video_failover_limit", "Video keeps failing — playing audio")
                    } else if (!retryToastShown) {
                        retryToastShown = true
                        showMessage("Refreshing video stream...")
                    }
                    launchLoad(song, resumePos, isRetry = true, targetClient = null, forceFresh = true, reuseVideoId = currentVideoId)
                    return true
                }

                // Call markTierFailedForVideo for ANY failover (EOF/stall/timeout/403/410)
                if (decision.markVideoTierFailed) {
                    DefaultResolverChain.instance.markTierFailedForVideo(song.id, client)
                }

                // ONLY call TierCircuitBreaker.recordFailure for 403/410 when not playedLong
                if (decision.blameCircuitBreaker) {
                    TierCircuitBreaker.recordFailure(client)
                    YouTubeExtractor.forgetWorkingClient(client)
                }

                showMessage("Stream interrupted - seamlessly failing over to backup tier...")
                _isBuffering.value = true
                _currentPosition.value = resumePos

                scope.launch {
                    val nextStream = withContext(Dispatchers.IO) {
                        DefaultResolverChain.instance.resolve(
                            videoId = song.id,
                            skipFailed = true,
                            forceFreshPlayer = true,
                            excludedTierIds = setOf(WebSafariAuthHlsResolver.TIER_ID)
                        )
                    }

                    if (nextStream == null) {
                        val msg = if (isNetworkAvailable(appContext)) {
                            "Weak connection — couldn't reload stream"
                        } else {
                            "No internet connection"
                        }
                        enterRetryablePauseState(msg, isNetworkIssue = true)
                        return@launch
                    }

                    currentClient = nextStream.tierId
                    val playUri = Uri.parse(nextStream.url)

                    val artworkBytes = withTimeoutOrNull(400L) { loadArtworkBytes(song.thumbnailUrl, song.id) }
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setArtworkUri(Uri.parse(song.thumbnailUrl))
                    if (artworkBytes != null) {
                        metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }

                    val mediaItemBuilder = MediaItem.Builder()
                        .setUri(playUri)
                        .setMediaId(song.id)
                        .setCustomCacheKey(cacheKeyFor(song.id, nextStream.format))
                        .setMediaMetadata(metadataBuilder.build())

                    val isHls = nextStream is Resolved.Hls || playUri.toString().contains(".m3u8") || currentClient == VisionOsHlsResolver.TIER_ID
                    if (isHls) {
                        mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                    val mediaItem = mediaItemBuilder.build()

                    val p = appContext?.let { getOrCreatePlayer(it) } ?: exoPlayer
                    p?.let { playerInstance ->
                        val ctx = appContext ?: return@launch
                        val mediaSource = createMediaSource(ctx, mediaItem, isHls, userAgent = nextStream.userAgent)
                        playerInstance.setMediaSource(mediaSource, resumePos)
                        playerInstance.seekTo(resumePos)
                        playerInstance.prepare()
                        playerInstance.playWhenReady = true
                    }

                    Log.i(TAG, "Seamlessly failed over to ${nextStream.tierId} (${nextStream.format}) at ${resumePos}ms")
                }

                return true
            }
        }
    }

    fun playSong(song: Song, newQueue: List<Song> = emptyList()) {
        playSong(null, song, newQueue)
    }

    fun playSongFromSearch(context: Context?, song: Song) {
        playSong(context, song, listOf(song))
        scope.launch {
            try {
                val similar = YouTubeExtractor.fetchSimilarSongs(song.id, song.artist)
                if (similar.isNotEmpty() && _currentSong.value?.id == song.id) {
                    val currentIds = setOf(song.id)
                    val clean = similar.filter { it.id !in currentIds }
                    if (clean.isNotEmpty()) {
                        val radioQueue = listOf(song) + clean
                        _queue.value = radioQueue
                        originalQueue = radioQueue
                        saveState()
                        triggerPrefetchForNextTrack()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Song radio fetch from search failed", e)
            }
        }
    }

    private fun startMusicServiceForeground(ctx: Context) {
        val intent = Intent(ctx, MusicPlayerService::class.java).apply {
            action = MusicPlayerService.ACTION_ENSURE_FOREGROUND
        }
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Exception) {
            try {
                ctx.startService(intent)
            } catch (e2: Exception) {
                Log.w(TAG, "Could not start MusicPlayerService", e2)
            }
        }
    }

    fun playSong(context: Context?, song: Song, newQueue: List<Song> = emptyList(), startPositionMs: Long = 0L) {
        val ctx = context ?: appContext
        if (ctx != null) {
            getOrCreatePlayer(ctx)
            acquireLocks(ctx)
            if (MusicPlayerService.activeSession == null) {
                startMusicServiceForeground(ctx)
            }
        }
        if (newQueue.isNotEmpty()) {
            val list = newQueue.distinctBy { it.id }
            originalQueue = list
            isOfflineQueue = list.all { it.isDownloaded || DownloadManager.isSongDownloaded(it.id) }
            _queue.value = if (_shuffleEnabled.value) {
                listOf(song) + list.filter { it.id != song.id }.shuffled()
            } else {
                list
            }
        } else {
            if (_queue.value.none { it.id == song.id }) {
                originalQueue = listOf(song)
                _queue.value = listOf(song)
                isOfflineQueue = song.isDownloaded || DownloadManager.isSongDownloaded(song.id)
            }
        }
        // Pause the previous track during transition WITHOUT clearing MediaItems,
        // so Media3 keeps the lockscreen notification and foreground service alive seamlessly.
        if (_currentSong.value?.id != song.id) {
            exoPlayer?.let { p ->
                p.pause()
            }
            _isPlaying.value = false
        }
        _currentSong.value = song
        resumePositionMs = 0L
        _currentPosition.value = startPositionMs
        LibraryStore.addToHistory(song)
        _isBuffering.value = true
        saveState(positionOverride = startPositionMs)

        // Bring video back for the new song if the user wants video (a previous song without an
        // official video may have switched this off).
        currentVideoId = null
        hasRetriedWrongVideo = false
        videoFallbackCountForTrack = 0
        lastVideoFallbackToastSongId = null
        if (videoPreferred && !_isVideoMode.value && isNetworkAvailable(appContext)) {
            _isVideoMode.value = true
            applyVideoTrackParameters(true)
        }

        // A fresh start by the user: reset fallback counters
        failoverCount = 0
        DefaultResolverChain.instance.clearVideoMemory(song.id)
        retryCount = 0
        failedClients.clear()
        retryToastShown = false
        hasRetriedSameClient = false
        isOfflinePaused = false
        _playbackError.value = null
        stopBufferingWatchdog()

        launchLoad(song, startPositionMs, isRetry = false, targetClient = null)
    }

    /** Resolves the stream and starts it. Used for a normal start AND for automatic retries. */
    private fun launchLoad(
        song: Song,
        startPositionMs: Long,
        isRetry: Boolean,
        targetClient: String? = null,
        forceFresh: Boolean = false,
        explicitTap: Boolean = false,
        bypassNegCache: Boolean = false,
        reuseVideoId: String? = null
    ) {
        pendingStartMs = startPositionMs
        lastLoadStartMs = startPositionMs
        currentClient = null
        streamReadyAt = 0L
        if (!isRetry) {
            failoverCount = 0
            hasRetriedSameClient = false
        }

        // A newer tap must win over a slower, older stream lookup.
        val myGen = loadGeneration.incrementAndGet()
        playJob?.cancel()
        playJob = scope.launch {
            try {
                val artworkJob = async(Dispatchers.IO) { loadArtworkBytes(song.thumbnailUrl, song.id) }
                // 1. Offline copy first
                val localFile = withContext(Dispatchers.IO) {
                    DownloadManager.downloadedSongs.value.find { it.id == song.id }
                        ?.localFilePath?.let { File(it) }
                        ?.takeIf { it.exists() && (appContext == null || DownloadManager.isPathInStorage(appContext!!, it)) }
                }

                var wantVideo = false
                if (wantVideo && !isNetworkAvailable(appContext)) {
                    wantVideo = false
                    _isVideoMode.value = false
                    applyVideoTrackParameters(false)
                    showMessage("Video unavailable offline")
                }

                // Official video only. Anything else falls back to the normal audio stream.
                var videoStream: YouTubeExtractor.ResolvedStream? = null
                var resolvedVideoId: String? = null
                if (wantVideo) {
                    _videoLoadState.value = VideoLoadState.Resolving
                    val excluded = wrongVideoIds[song.id]?.toSet() ?: emptySet()
                    val lookup = if (!reuseVideoId.isNullOrBlank() && reuseVideoId !in excluded) {
                        YouTubeExtractor.VideoLookup.Found(reuseVideoId)
                    } else {
                        var res = YouTubeExtractor.lookupMusicVideo(song, excludedIds = excluded, bypassNegativeCache = bypassNegCache)
                        if (res is YouTubeExtractor.VideoLookup.Error) {
                            delay(800L)
                            res = YouTubeExtractor.lookupMusicVideo(song, excludedIds = excluded, bypassNegativeCache = bypassNegCache)
                        }
                        res
                    }
                    if (myGen != loadGeneration.get()) return@launch
                    when (lookup) {
                        is YouTubeExtractor.VideoLookup.Found -> {
                            val vid = lookup.id
                            suspend fun resolve(fresh: Boolean) = withContext(Dispatchers.IO) {
                                YouTubeExtractor.resolveVideoStream(
                                    vid,
                                    maxHeight = effectiveMaxHeight(),
                                    forceFresh = fresh
                                )
                            }
                            videoStream = resolve(forceFresh || isRetry)
                            if (myGen != loadGeneration.get()) return@launch
                            if (videoStream == null && isNetworkAvailable(appContext)) {
                                delay(600L)
                                if (myGen != loadGeneration.get()) return@launch
                                videoStream = resolve(true)
                            }
                            if (videoStream != null) {
                                resolvedVideoId = vid
                                Log.i(TAG, "launchLoad(${song.id} -> $vid): format=${videoStream!!.format}, dualAudio=${videoStream!!.secondaryAudioUrl != null}")
                            } else {
                                YouTubeExtractor.forgetVideoLookup(song.id)
                                dropVideoToAudio("resolve_video_null", "Couldn't load official video — playing audio")
                            }
                        }
                        is YouTubeExtractor.VideoLookup.None ->
                            dropVideoToAudio("lookup_none", if (explicitTap) "No official video available for this song" else "Video unavailable — playing audio")
                        is YouTubeExtractor.VideoLookup.Error ->
                            dropVideoToAudio("lookup_error", "Couldn't check for official video — playing audio")
                    }
                    if (myGen != loadGeneration.get()) return@launch
                    if (videoStream == null) {
                        wantVideo = false
                    }
                }
                val readyVideoStream = videoStream
                currentIsVideoStream = readyVideoStream != null
                currentVideoId = resolvedVideoId

                val playUri: Uri
                val streamUserAgent: String?
                var streamLoudnessDb: Float? = null
                var secondaryAudioUrl: String? = null
                var streamFormatLabel = song.format
                var cacheKey = song.id
                if (localFile != null && !wantVideo) {
                    playUri = Uri.fromFile(localFile)
                    streamUserAgent = null
                    appContext?.let { updateNetworkLocks(it, isOnlineStream = false) }
                } else {
                    appContext?.let { updateNetworkLocks(it, isOnlineStream = true) }
                    // Check prefetch cache for instant playback start (audio mode only)
                    val prefetched = if (!wantVideo && !isRetry && !forceFresh && failedClients.isEmpty() && targetClient == null) {
                        PrefetchManager.getPrefetched(song.id)
                    } else null

                    var stream = if (readyVideoStream != null) {
                        readyVideoStream
                    } else if (prefetched != null) {
                        Log.i(TAG, "Instant playback start using pre-resolved stream for ${song.id}")
                        prefetched
                    } else {
                        val skip = failedClients.toSet()
                        withContext(Dispatchers.IO) {
                            YouTubeExtractor.resolveAudioStream(
                                song.id,
                                skipClients = skip,
                                keepDiagnostics = isRetry,
                                targetClient = targetClient,
                                forceFresh = forceFresh
                            )
                        }
                    }
                    if (stream == null && targetClient != null && isNetworkAvailable(appContext)) {
                        DefaultResolverChain.instance.clearVideoMemory(song.id)
                        stream = withContext(Dispatchers.IO) {
                            YouTubeExtractor.resolveAudioStream(
                                song.id,
                                skipClients = setOf(targetClient),
                                keepDiagnostics = true,
                                targetClient = null,
                                forceFresh = true
                            )
                        }
                    }
                    // Absorb brief 1-second cell-tower handoffs silently with 1 quick retry before showing error UI
                    if (stream == null && !isRetry && isNetworkAvailable(appContext)) {
                        delay(1200L)
                        DefaultResolverChain.instance.clearVideoMemory(song.id)
                        stream = withContext(Dispatchers.IO) {
                            YouTubeExtractor.resolveAudioStream(
                                song.id,
                                skipClients = setOf(WebSafariAuthHlsResolver.TIER_ID),
                                keepDiagnostics = true,
                                targetClient = targetClient,
                                forceFresh = true
                            )
                        }
                    }
                    if (stream == null) {
                        val reason = DefaultResolverChain.instance.lastFailureReason ?: ""
                        val msg = when {
                            !isNetworkAvailable(appContext) -> "No internet connection"
                            reason.contains("LOGIN_REQUIRED", ignoreCase = true) && com.ytmusic.clone.data.local.ExtractorPrefs.isYoutubeCookieExpired() ->
                                "Session expired — Re-Sign In via Settings or skip"
                            reason.contains("LOGIN_REQUIRED", ignoreCase = true) && !com.ytmusic.clone.data.local.ExtractorPrefs.hasYoutubeCookie() ->
                                "Login required — sign in via Settings or skip"
                            else -> "Weak connection — stream unavailable"
                        }
                        enterRetryablePauseState(msg, isNetworkIssue = true)
                        return@launch
                    }
                    currentClient = stream.client
                    playUri = Uri.parse(stream.url)
                    streamUserAgent = stream.userAgent
                    streamLoudnessDb = stream.loudnessDb
                    secondaryAudioUrl = stream.secondaryAudioUrl
                    streamFormatLabel = stream.format
                    cacheKey = (if (wantVideo) "video|" + (resolvedVideoId ?: song.id) + "|" + stream.format.replace(" ", "") else "audio|" + cacheKeyFor(song.id, stream.format))
                    _currentSong.value = song.copy(format = stream.format, loudnessDb = stream.loudnessDb)
                    Log.d(TAG, "Playing via ${stream.client}: ${stream.format}")
                }

                val artworkBytes = withTimeoutOrNull(1500L) { artworkJob.await() }
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(Uri.parse(song.thumbnailUrl))
                if (artworkBytes != null) {
                    metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }

                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(playUri)
                    .setMediaId(song.id)
                    .setCustomCacheKey(cacheKey)
                    .setMediaMetadata(metadataBuilder.build())

                val isHls = playUri.toString().contains(".m3u8") ||
                    playUri.toString().contains("hls_variant") ||
                    playUri.toString().contains("hls_playlist") ||
                    currentClient == YouTubeExtractor.CLIENT_VISIONOS_HLS ||
                    currentClient == VisionOsHlsResolver.TIER_ID ||
                    currentClient == WebSafariAuthHlsResolver.TIER_ID

                if (isHls) {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                val mediaItem = mediaItemBuilder.build()

                if (myGen != loadGeneration.get()) return@launch
                val player = appContext?.let { getOrCreatePlayer(it) } ?: exoPlayer
                player?.let { p ->
                    val ctx = appContext ?: return@launch
                    applyVideoTrackParameters(wantVideo)
                    val mediaSource = createMediaSource(
                        ctx,
                        mediaItem,
                        isHls,
                        userAgent = streamUserAgent,
                        bypassCache = wantVideo,
                        secondaryAudioUrl = secondaryAudioUrl,
                        videoStreamId = resolvedVideoId,
                        audioFormatLabel = streamFormatLabel
                    )
                    p.setMediaSource(mediaSource, startPositionMs)
                    p.prepare()
                    p.playWhenReady = true
                    applyPlaybackParameters()
                    applyLoudnessEnhancer(p.audioSessionId, streamLoudnessDb ?: song.loudnessDb)

                    // Pre-resolve the next song in queue
                    triggerPrefetchForNextTrack()
                }

                appContext?.let { ctx ->
                    startMusicServiceForeground(ctx)
                    // Lock-screen controls are notifications: tell the user once if they're blocked.
                    if (!warnedAboutNotifications &&
                        !androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                    ) {
                        warnedAboutNotifications = true
                        showMessage("Notifications are turned off for this app, so lock-screen controls can't show. Turn them on in Settings > Apps.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "playSong failed", e)
                val msg = if (isNetworkAvailable(appContext)) "Network error — tap Retry" else "No internet connection"
                enterRetryablePauseState(msg, isNetworkIssue = true)
            }
        }
    }

    /** Stops the music completely (used when the app is swiped away from recent apps). */
    fun stopPlayback() {
        playJob?.cancel()
        cancelSleepTimer()
        stopBufferingWatchdog()
        PrefetchManager.clearCache()
        releaseLocks()
        releaseAudioEffects()
        // Remember where we were BEFORE clearing anything, so the next app start can resume.
        // (Written right now, not in the background, because the process may die any moment.)
        saveState(sync = true)
        // Clear the song first: the player's "stopped" callbacks that follow must not
        // overwrite the saved state with an empty one.
        _currentSong.value = null
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        resumePositionMs = 0L
        currentClient = null
        currentIsVideoStream = false
        retryCount = 0
        failedClients.clear()
        hasRetriedSameClient = false
        isOfflinePaused = false
        _playbackError.value = null
        _isPlaying.value = false
        _isBuffering.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _isFullscreenVideo.value = false
        _isFullPlayerExpanded.value = false
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (playJob?.isActive == true) return // still resolving the stream
        val song = _currentSong.value
        if (_playbackError.value != null && song != null) {
            retryCurrentSong()
            return
        }
        isOfflinePaused = false
        when {
            // After an error the player sits in IDLE and play() does nothing; also covers
            // expired stream URLs. Re-resolve and start again.
            // (also the "restored last session" case: start from the saved position)
            player.playbackState == Player.STATE_IDLE && song != null -> {
                val start = if (player.mediaItemCount == 0) resumePositionMs else _currentPosition.value
                playSong(null, song, emptyList(), start)
            }
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val dur = _duration.value
        val clamped = if (dur > 0L) positionMs.coerceIn(0L, dur) else positionMs.coerceAtLeast(0L)
        val player = exoPlayer
        if (player != null && player.mediaItemCount == 0 && _currentSong.value != null) {
            // Restored session, nothing loaded yet: just remember the spot to start from.
            resumePositionMs = clamped
        } else {
            player?.seekTo(clamped)
        }
        _currentPosition.value = clamped
    }

    // ---------- Next / previous / repeat / shuffle (Apple Music / YT Music Standard) ----------

    private fun onTrackEnded() {
        if (_sleepTimerType.value == SleepTimerType.END_OF_TRACK) {
            cancelSleepTimer()
            scope.launch { fadeOutAndPause() }
            return
        }
        if (_sleepTimerType.value == SleepTimerType.DURATION && _hasTimerExpiredWaitingForSong.value) {
            cancelSleepTimer()
            scope.launch { fadeOutAndPause() }
            return
        }

        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                // Seamlessly looped by ExoPlayer.repeatMode = REPEAT_MODE_ONE,
                // fallback restart if state ended ever fires:
                val p = exoPlayer
                if (p != null) {
                    p.seekTo(0)
                    p.play()
                } else {
                    _currentSong.value?.let { playSong(it) }
                }
            }
            RepeatMode.ALL -> {
                // Loop the entire active queue/playlist indefinitely
                goToNext(wrap = true, allowAutoplay = false)
            }
            RepeatMode.OFF -> {
                // Play sequentially. At the end of queue:
                // If autoplay is ON and online, transition to similar radio.
                // Otherwise, stop cleanly.
                val queue = _queue.value
                val current = _currentSong.value
                val currentIndex = if (current != null) queue.indexOfFirst { it.id == current.id } else -1
                val isAtEnd = currentIndex == -1 || currentIndex >= queue.size - 1

                if (isAtEnd) {
                    if (_autoplayEnabled.value && !isOfflineQueue) {
                        goToNext(wrap = false, allowAutoplay = true)
                    } else {
                        // Reached end of playlist/album: stop cleanly
                        _isPlaying.value = false
                        exoPlayer?.pause()
                        exoPlayer?.seekTo(0)
                        _currentPosition.value = 0L
                    }
                } else {
                    goToNext(wrap = false, allowAutoplay = false)
                }
            }
        }
    }

    /**
     * Returns the next song in the queue respecting Repeat Mode.
     * When Repeat ALL is active: wraps to track 0 at the end.
     * When Repeat OFF is active: returns null at the end of the queue.
     */
    fun getNextSongInQueue(): Song? {
        val queue = _queue.value
        if (queue.isEmpty()) return null
        val current = _currentSong.value ?: return queue.firstOrNull()
        val index = queue.indexOfFirst { it.id == current.id }

        return when {
            index == -1 -> queue.firstOrNull()
            index + 1 < queue.size -> queue[index + 1]
            _repeatMode.value == RepeatMode.ALL -> queue.firstOrNull()
            else -> null
        }
    }

    /**
     * Returns the previous song in the queue respecting Repeat Mode.
     * When Repeat ALL is active: wraps to the last track at index 0.
     */
    fun getPreviousSongInQueue(): Song? {
        val queue = _queue.value
        if (queue.isEmpty()) return null
        val current = _currentSong.value ?: return queue.lastOrNull()
        val index = queue.indexOfFirst { it.id == current.id }

        return when {
            index > 0 -> queue[index - 1]
            _repeatMode.value == RepeatMode.ALL -> queue.lastOrNull()
            else -> queue.firstOrNull()
        }
    }

    fun triggerPrefetchForNextTrack() {
        if (_repeatMode.value == RepeatMode.ONE) return
        val nextSong = getNextSongInQueue() ?: return
        if (nextSong.id == _currentSong.value?.id) return

        if (DownloadManager.isSongDownloaded(nextSong.id)) {
            Log.d(TAG, "Next track ${nextSong.id} is downloaded, skipping network prefetch")
            return
        }
        PrefetchManager.prefetchNextTrack(nextSong.id)
    }

    private fun goToNext(wrap: Boolean = true, allowAutoplay: Boolean = true) {
        if (_repeatMode.value == RepeatMode.ONE) {
            val p = exoPlayer
            if (p != null) {
                p.seekTo(0)
                p.play()
            } else {
                _currentSong.value?.let { playSong(it) }
            }
            return
        }
        val queue = _queue.value
        if (queue.isEmpty()) return
        val current = _currentSong.value ?: return
        val index = queue.indexOfFirst { it.id == current.id }
        val isAtEnd = index == -1 || index + 1 >= queue.size

        if (isAtEnd) {
            // Case 1: Loop all -> wrap to track 0
            if (_repeatMode.value == RepeatMode.ALL || (wrap && _repeatMode.value != RepeatMode.OFF)) {
                playSong(queue.first())
                return
            }

            // Case 2: Repeat is OFF, Autoplay is enabled and online -> fetch & transition
            if (allowAutoplay && _autoplayEnabled.value && !isOfflineQueue) {
                scope.launch {
                    isFetchingAutoplay = true
                    _isBuffering.value = true
                    try {
                        val similar = YouTubeExtractor.fetchSimilarSongs(current.id, current.artist)
                        val currentIds = _queue.value.map { it.id }.toSet()
                        val fresh = similar.filter { it.id !in currentIds }
                        if (fresh.isNotEmpty()) {
                            val updated = _queue.value + fresh
                            _queue.value = updated
                            originalQueue = updated
                            saveState()
                            playSong(fresh.first())
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Autoplay on-demand fetch failed: ${e.javaClass.simpleName}")
                    } finally {
                        isFetchingAutoplay = false
                        if (playJob?.isActive != true && exoPlayer?.isPlaying != true) {
                            _isBuffering.value = false
                        }
                    }
                    _isPlaying.value = false
                    exoPlayer?.pause()
                    seekTo(0)
                    _currentPosition.value = 0L
                }
                return
            }

            // Case 3: Reached end of queue without loop -> stop cleanly
            _isPlaying.value = false
            exoPlayer?.pause()
            seekTo(0)
            _currentPosition.value = 0L
            return
        }

        val nextIndex = index + 1
        playSong(queue[nextIndex])
    }

    fun playNext() {
        if (_crossfadeSeconds.value > 0) {
            scope.launch {
                isCrossfading = true
                try {
                    fadeVolume(from = exoPlayer?.volume ?: 1f, to = 0.05f, durationMs = 200L)
                    val shouldWrap = _repeatMode.value == RepeatMode.ALL
                    goToNext(wrap = shouldWrap, allowAutoplay = true)
                    fadeInToBaseVolume(300L)
                } finally {
                    isCrossfading = false
                    try { exoPlayer?.volume = baseTrackVolume } catch (_: Exception) {}
                }
            }
        } else {
            val shouldWrap = _repeatMode.value == RepeatMode.ALL
            goToNext(wrap = shouldWrap, allowAutoplay = true)
        }
    }

    fun playPrevious(forcePrevious: Boolean = false) {
        val current = _currentSong.value ?: return
        val queue = _queue.value
        if (queue.isEmpty()) return

        if (!forcePrevious && _currentPosition.value > 3000L) {
            seekTo(0)
            return
        }

        val index = queue.indexOfFirst { it.id == current.id }
        val prevIndex = when {
            index > 0 -> index - 1
            _repeatMode.value == RepeatMode.ALL -> queue.size - 1
            else -> 0
        }

        if (_crossfadeSeconds.value > 0) {
            scope.launch {
                isCrossfading = true
                try {
                    fadeVolume(from = exoPlayer?.volume ?: 1f, to = 0.05f, durationMs = 200L)
                    playSong(queue[prevIndex])
                    fadeInToBaseVolume(300L)
                } finally {
                    isCrossfading = false
                    try { exoPlayer?.volume = baseTrackVolume } catch (_: Exception) {}
                }
            }
        } else {
            playSong(queue[prevIndex])
        }
    }

    fun toggleShuffle() {
        val on = !_shuffleEnabled.value
        _shuffleEnabled.value = on
        val queue = _queue.value
        val current = _currentSong.value

        if (on) {
            val sourceList = if (originalQueue.isNotEmpty()) originalQueue else queue
            originalQueue = sourceList
            if (current != null) {
                val remaining = sourceList.filter { it.id != current.id }.shuffled()
                _queue.value = listOf(current) + remaining
            } else {
                _queue.value = sourceList.shuffled()
            }
        } else {
            if (originalQueue.isNotEmpty()) {
                _queue.value = originalQueue
            }
        }
        savePrefs()
        saveState()
        triggerPrefetchForNextTrack()
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        exoPlayer?.repeatMode = if (mode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        savePrefs()
        saveState()
        triggerPrefetchForNextTrack()
    }

    fun cycleRepeat() {
        val nextMode = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        setRepeatMode(nextMode)
    }

    /** Turns shuffle on and starts a random song strictly within [songs]. */
    fun shuffleAndPlay(context: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        val distinctSongs = songs.distinctBy { it.id }
        val startSong = distinctSongs.random()
        originalQueue = distinctSongs
        isOfflineQueue = distinctSongs.all { it.isDownloaded || DownloadManager.isSongDownloaded(it.id) }
        _shuffleEnabled.value = true
        savePrefs()
        _queue.value = listOf(startSong) + distinctSongs.filter { it.id != startSong.id }.shuffled()
        playSong(context, startSong, emptyList())
    }

    // ---------- Queue editing ----------

    fun setQueueForTesting(songs: List<Song>, activeSong: Song?) {
        originalQueue = songs
        _queue.value = songs
        _currentSong.value = activeSong
    }

    fun addToQueue(song: Song): Boolean {
        if (_currentSong.value == null || _queue.value.isEmpty()) {
            playSong(song, listOf(song))
            return true
        }
        if (_queue.value.any { it.id == song.id }) return false
        _queue.value = _queue.value + song
        originalQueue = originalQueue + song
        saveState()
        triggerPrefetchForNextTrack()
        return true
    }

    fun playNextInQueue(song: Song) {
        val current = _currentSong.value
        if (current == null || _queue.value.isEmpty()) {
            playSong(song, listOf(song))
            return
        }
        if (song.id == current.id) return
        val without = _queue.value.filter { it.id != song.id }
        val index = without.indexOfFirst { it.id == current.id }
        val insertAt = if (index == -1) 0 else index + 1
        _queue.value = without.toMutableList().also { it.add(insertAt, song) }
        if (originalQueue.none { it.id == song.id }) {
            originalQueue = originalQueue + song
        }
        saveState()
        triggerPrefetchForNextTrack()
        checkAndTriggerAutoplay()
    }

    fun removeFromQueue(songId: String) {
        if (songId == _currentSong.value?.id) return
        _queue.value = _queue.value.filter { it.id != songId }
        originalQueue = originalQueue.filter { it.id != songId }
        saveState()
        triggerPrefetchForNextTrack()
        checkAndTriggerAutoplay()
    }

    /** Removes everything after the song that is playing now. */
    fun clearUpcoming() {
        val current = _currentSong.value ?: run { _queue.value = emptyList(); return }
        val queue = _queue.value
        val index = queue.indexOfFirst { it.id == current.id }
        _queue.value = if (index == -1) listOf(current) else queue.take(index + 1)
        saveState()
        triggerPrefetchForNextTrack()
        checkAndTriggerAutoplay()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int, saveToDisk: Boolean = true) {
        val list = _queue.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices && fromIndex != toIndex) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _queue.value = list
            if (!_shuffleEnabled.value) {
                originalQueue = list
            }
            if (saveToDisk) {
                saveState()
                triggerPrefetchForNextTrack()
            }
        }
    }

    fun commitQueueOrder() {
        saveState()
        triggerPrefetchForNextTrack()
    }

    fun moveQueueItemUp(index: Int) {
        if (index > 0 && index < _queue.value.size) {
            moveQueueItem(index, index - 1)
        }
    }

    fun moveQueueItemDown(index: Int) {
        if (index >= 0 && index < _queue.value.size - 1) {
            moveQueueItem(index, index + 1)
        }
    }

    fun saveQueueAsPlaylist(name: String): Boolean {
        val songs = _queue.value
        if (songs.isEmpty()) return false
        val playlistId = LibraryStore.createPlaylistWithSongs(name, songs)
        return playlistId != null
    }

    // ---------- Sleep timer & Fade Out Engine ----------

    suspend fun fadeOutAndPause() {
        val player = exoPlayer ?: return
        try {
            val initialVol = player.volume
            for (i in 10 downTo 0) {
                player.volume = initialVol * (i / 10f)
                delay(250)
            }
            player.pause()
            player.volume = initialVol
        } catch (_: Exception) {
            player.pause()
        }
    }

    fun setSleepTimerDuration(minutes: Int, finishSong: Boolean = true) {
        cancelSleepTimer()
        _sleepTimerType.value = SleepTimerType.DURATION
        _finishCurrentSong.value = finishSong
        val endTime = System.currentTimeMillis() + minutes * 60_000L
        _sleepEndsAt.value = endTime

        sleepJob = scope.launch {
            while (isActive) {
                val remaining = ((_sleepEndsAt.value ?: 0L) - System.currentTimeMillis()) / 1000L
                if (remaining <= 0) {
                    _sleepRemainingSeconds.value = 0L
                    if (_finishCurrentSong.value) {
                        _hasTimerExpiredWaitingForSong.value = true
                    } else {
                        cancelSleepTimer()
                        fadeOutAndPause()
                    }
                    break
                } else {
                    _sleepRemainingSeconds.value = remaining
                }
                delay(1000)
            }
        }
    }

    fun setSleepTimerEndOfTrack() {
        cancelSleepTimer()
        _sleepTimerType.value = SleepTimerType.END_OF_TRACK
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimerType.value = null
        _sleepEndsAt.value = null
        _sleepRemainingSeconds.value = null
        _hasTimerExpiredWaitingForSong.value = false
    }

    fun setSleepTimer(minutes: Int?) {
        if (minutes == null) {
            cancelSleepTimer()
        } else {
            setSleepTimerDuration(minutes, finishSong = true)
        }
    }

    // ---------- Saved settings ----------

    private fun loadPrefs(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        val p = context.applicationContext.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        _shuffleEnabled.value = p.getBoolean("shuffle", false)
        _autoplayEnabled.value = p.getBoolean("autoplay", true)
        val savedVQ = p.getString("video_quality", VideoQuality.AUTO.name)
        _videoQuality.value = VideoQuality.entries.firstOrNull { it.name == savedVQ } ?: VideoQuality.AUTO
        _repeatMode.value = try {
            RepeatMode.valueOf(p.getString("repeat", "OFF") ?: "OFF")
        } catch (e: Exception) {
            RepeatMode.OFF
        }
    }

    private fun savePrefs() {
        appContext?.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("shuffle", _shuffleEnabled.value)
            ?.putBoolean("autoplay", _autoplayEnabled.value)
            ?.putString("repeat", _repeatMode.value.name)
            ?.apply()
    }

    // ---------- Remember the last session ----------

    /**
     * Saves the current song, queue, position, shuffle and repeat to a private file.
     * Does nothing when no song is loaded (so an empty state never overwrites a good one).
     */
    fun saveState(positionOverride: Long? = null, sync: Boolean = false) {
        val song = _currentSong.value ?: return
        val player = exoPlayer
        val position = positionOverride ?: when {
            playJob?.isActive == true -> pendingStartMs // new song still loading
            player != null && player.mediaItemCount > 0 -> player.currentPosition.coerceAtLeast(0L)
            else -> resumePositionMs
        }
        val state = SavedPlayerState(
            song = song,
            queue = _queue.value,
            originalQueue = originalQueue,
            positionMs = position,
            shuffle = _shuffleEnabled.value,
            repeat = _repeatMode.value.name
        )
        if (sync) {
            PlayerStateStore.save(state)
        } else {
            scope.launch(Dispatchers.IO) { PlayerStateStore.save(state) }
        }
    }

    /**
     * Brings back the last song and queue, PAUSED. Nothing is loaded or downloaded yet:
     * the stream is resolved only when the user presses play.
     * Safe to call many times: it only works if nothing is loaded right now.
     */
    fun restoreLastSession(context: Context) {
        if (_currentSong.value != null) return
        val saved = PlayerStateStore.load() ?: return
        val song = saved.song ?: return
        originalQueue = saved.originalQueue ?: emptyList()
        _queue.value = saved.queue?.takeIf { q -> q.any { it.id == song.id } } ?: listOf(song)
        var pos = saved.positionMs.coerceAtLeast(0L)
        // Saved at the very end of the song? Start it from the beginning instead.
        if (song.durationSeconds > 0 && pos >= song.durationSeconds * 1000L - 3000L) pos = 0L
        resumePositionMs = pos
        _currentPosition.value = resumePositionMs
        _duration.value = song.durationSeconds * 1000L
        _isPlaying.value = false
        _isBuffering.value = false
        _currentSong.value = song // shows the mini player (paused)
    }

    private fun startTrackingProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    _currentPosition.value = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.coerceAtLeast(0L)
                    _duration.value = dur

                    // Automatic End-of-Track Crossfade transition (Poweramp/Spotify style)
                    val crossfadeSec = _crossfadeSeconds.value
                    if (crossfadeSec > 0 && _repeatMode.value != RepeatMode.ONE && dur > 15_000L && player.isPlaying && !isCrossfading) {
                        val remainingMs = dur - player.currentPosition
                        val currentId = _currentSong.value?.id
                        if (remainingMs <= crossfadeSec * 1000L && remainingMs > 800L && lastCrossfadedSongId != currentId) {
                            lastCrossfadedSongId = currentId
                            isCrossfading = true
                            scope.launch {
                                try {
                                    fadeVolume(from = player.volume, to = 0.08f, durationMs = (remainingMs - 350L).coerceAtLeast(400L))
                                    goToNext(wrap = true)
                                    fadeInToBaseVolume(600L)
                                } finally {
                                    isCrossfading = false
                                    try { exoPlayer?.volume = baseTrackVolume } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }
                // Save our place about every 10 seconds while music is playing.
                progressTicks++
                if (progressTicks >= 20) {
                    progressTicks = 0
                    saveState()
                }
                val interval = if (_isFullPlayerExpanded.value) 500L else 1200L
                delay(interval)
            }
        }
    }

    private fun stopTrackingProgress() {
        progressJob?.cancel()
    }
}
