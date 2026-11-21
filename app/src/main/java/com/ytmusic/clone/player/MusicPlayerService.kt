package com.ytmusic.clone.player

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ytmusic.clone.MainActivity
import com.ytmusic.clone.R
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.model.Song
import java.io.File

/**
 * MediaLibraryService implementation powering Android Auto, lockscreen media controls,
 * Bluetooth car dashboards, and system-wide background media session management.
 */
class MusicPlayerService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var exoPlayer: Player? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var packageValidator: PackageValidator
    private var media3NotificationActive = false

    companion object {
        const val CHANNEL_ID = "yt_music_playback_channel"
        const val NOTIFICATION_ID = 1001

        var activeSession: MediaLibrarySession? = null

        const val ACTION_TOGGLE_LIKE = "com.ytmusic.clone.ACTION_TOGGLE_LIKE"
        const val ACTION_CYCLE_REPEAT = "com.ytmusic.clone.ACTION_CYCLE_REPEAT"
        const val ACTION_ENSURE_FOREGROUND = "com.ytmusic.clone.ACTION_ENSURE_FOREGROUND"

        val CUSTOM_COMMAND_TOGGLE_LIKE = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
        val CUSTOM_COMMAND_CYCLE_REPEAT = SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY)

        private const val ROOT_ID = "ytmusic_root"
        private const val CATEGORY_DOWNLOADS = "category_downloads"
        private const val CATEGORY_LIKED = "category_liked"
        private const val CATEGORY_PLAYLISTS = "category_playlists"
        private const val CATEGORY_HISTORY = "category_history"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        packageValidator = PackageValidator(this)
        createNotificationChannel()

        val player = MusicPlayerManager.getOrCreatePlayer(this)
        exoPlayer = player

        // Intent to launch MainActivity when tapping lock screen / notification media card
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_full_player", true)
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val queuePlayer = QueuePlayer(player)
        val libraryCallback = CustomMediaLibrarySessionCallback()
        val session = MediaLibrarySession.Builder(this, queuePlayer, libraryCallback)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
        mediaLibrarySession = session
        activeSession = session
        addSession(session)
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                // Gracefully avoid ForegroundServiceStartNotAllowedException crash on Android 12+
            }
        })

        // Custom notification provider to guarantee clean small icon, lockscreen visibility, and proper channel binding
        try {
            val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build().apply {
                    setSmallIcon(R.drawable.ic_notif_play_emblem)
                }
            setMediaNotificationProvider(notificationProvider)
        } catch (e: Exception) {
            // Fallback gracefully if builder options differ
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playback_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isPlaybackActive = intent?.action == ACTION_ENSURE_FOREGROUND ||
            MusicPlayerManager.isPlaying.value ||
            MusicPlayerManager.isBuffering.value ||
            exoPlayer?.playWhenReady == true
        if (isPlaybackActive && !media3NotificationActive) {
            ensureForeground()
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        media3NotificationActive = true
        val keepForeground = startInForegroundRequired ||
            MusicPlayerManager.isPlaying.value ||
            MusicPlayerManager.isBuffering.value ||
            MusicPlayerManager.isTransitioningTrack() ||
            exoPlayer?.playWhenReady == true
        super.onUpdateNotification(session, keepForeground)
    }

    @OptIn(UnstableApi::class)
    private fun ensureForeground() {
        try {
            val song = MusicPlayerManager.currentSong.value
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_full_player", true)
            }
            val pi = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_play_emblem)
                .setContentTitle(song?.title ?: getString(R.string.app_name))
                .setContentText(song?.artist ?: "Playing music")
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            mediaLibrarySession?.let { s ->
                builder.setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(s))
            }
            val notification = builder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        // IMPORTANT: do NOT release the player here. It is the app-wide singleton owned by
        // MusicPlayerManager; releasing it left a dead player behind and every later
        // song looked "stuck" until the app process was killed.
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        activeSession = null
        exoPlayer = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away from the recent apps list: stop the music and shut down.
        MusicPlayerManager.stopPlayback()
        stopSelf()
    }

    private fun songToMediaItem(song: Song): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        if (song.thumbnailUrl.isNotBlank()) {
            try {
                metadataBuilder.setArtworkUri(Uri.parse(song.thumbnailUrl))
            } catch (e: Exception) {
                // Ignore malformed URI
            }
        }
        val builder = MediaItem.Builder()
            .setMediaId(song.id)
            .setMediaMetadata(metadataBuilder.build())
        if (!song.localFilePath.isNullOrBlank()) {
            val localFile = File(song.localFilePath)
            if (localFile.exists() && DownloadManager.isPathInStorage(this, localFile)) {
                builder.setUri(Uri.fromFile(localFile))
            }
        }
        return builder.build()
    }

    private fun buildCategoryItem(id: String, title: String, subtitle: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun findSongById(id: String): Song? {
        return DownloadManager.downloadedSongs.value.find { it.id == id }
            ?: LibraryStore.liked.value.find { it.id == id }
            ?: LibraryStore.history.value.find { it.id == id }
            ?: LibraryStore.playlists.value.flatMap { it.songs }.find { it.id == id }
            ?: MusicPlayerManager.queue.value.find { it.id == id }
    }

    private fun searchSongs(query: String): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val all = (DownloadManager.downloadedSongs.value + LibraryStore.liked.value + LibraryStore.history.value)
            .distinctBy { it.id }
            .filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        return all.map { songToMediaItem(it) }
    }

    private fun handlePlayRequest(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        if (mediaItems.isEmpty()) return
        // Cap external playlist size at 200 items (Task 3.3)
        val cappedItems = mediaItems.take(200)
        val validMediaIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

        val songs = cappedItems.mapNotNull { item ->
            val id = item.mediaId
            val localSong = findSongById(id)
            if (localSong != null) {
                localSong
            } else if (validMediaIdRegex.matches(id)) {
                // Do NOT copy unvalidated external artworkUri to prevent URI injection attacks (Task 3.3)
                Song(
                    id = id,
                    title = item.mediaMetadata.title?.toString()?.take(100) ?: "Track",
                    artist = item.mediaMetadata.artist?.toString()?.take(100) ?: "YouTube Music",
                    thumbnailUrl = ""
                )
            } else {
                null // Drop invalid or malformed media IDs
            }
        }
        if (songs.isEmpty()) return
        val targetIndex = if (startIndex in songs.indices) startIndex else 0
        val targetSong = songs[targetIndex]
        mainHandler.post {
            MusicPlayerManager.playSong(
                context = this@MusicPlayerService,
                song = targetSong,
                newQueue = songs,
                startPositionMs = startPositionMs
            )
        }
    }

    /**
     * MediaLibrarySession.Callback implementation that exposes the root categories,
     * child playlists, downloads, and playback selection to Android Auto.
     */
    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            return if (packageValidator.isTrusted(controller)) {
                val trustedSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(CUSTOM_COMMAND_TOGGLE_LIKE)
                    .add(CUSTOM_COMMAND_CYCLE_REPEAT)
                    .build()
                val likeButton = CommandButton.Builder()
                    .setDisplayName("Favorite")
                    .setIconResId(R.drawable.ic_notif_heart)
                    .setSessionCommand(CUSTOM_COMMAND_TOGGLE_LIKE)
                    .build()
                val repeatButton = CommandButton.Builder()
                    .setDisplayName("Repeat")
                    .setIconResId(R.drawable.ic_notif_repeat)
                    .setSessionCommand(CUSTOM_COMMAND_CYCLE_REPEAT)
                    .build()
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(trustedSessionCommands)
                    .setAvailablePlayerCommands(session.player.availableCommands)
                    .setCustomLayout(listOf(likeButton, repeatButton))
                    .build()
            } else {
                // Untrusted caller: accept connection for basic transport controls only,
                // stripping library commands so private history and playlists cannot be harvested.
                val basicPlayerCommands = session.player.availableCommands.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .build()
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                    .setAvailablePlayerCommands(basicPlayerCommands)
                    .build()
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (!packageValidator.isTrusted(controller)) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                ACTION_TOGGLE_LIKE -> {
                    mainHandler.post {
                        MusicPlayerManager.currentSong.value?.let { song ->
                            LibraryStore.toggleLike(song)
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_CYCLE_REPEAT -> {
                    mainHandler.post {
                        MusicPlayerManager.cycleRepeat()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (!packageValidator.isTrusted(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("YouTube Music")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!packageValidator.isTrusted(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            val items: List<MediaItem> = when (parentId) {
                ROOT_ID -> listOf(
                    buildCategoryItem(CATEGORY_DOWNLOADS, "Downloads", "Offline music"),
                    buildCategoryItem(CATEGORY_LIKED, "Liked Songs", "Favorite tracks"),
                    buildCategoryItem(CATEGORY_PLAYLISTS, "Playlists", "Custom playlists"),
                    buildCategoryItem(CATEGORY_HISTORY, "Recently Played", "Recent listening history")
                )
                CATEGORY_DOWNLOADS -> DownloadManager.downloadedSongs.value.map { songToMediaItem(it) }
                CATEGORY_LIKED -> LibraryStore.liked.value.map { songToMediaItem(it) }
                CATEGORY_HISTORY -> LibraryStore.history.value.map { songToMediaItem(it) }
                CATEGORY_PLAYLISTS -> LibraryStore.playlists.value.map { pl ->
                    MediaItem.Builder()
                        .setMediaId("pl_${pl.id}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(pl.name)
                                .setSubtitle("${pl.songs.size} songs")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()
                }
                else -> {
                    if (parentId.startsWith("pl_")) {
                        val plId = parentId.removePrefix("pl_")
                        val pl = LibraryStore.playlists.value.find { it.id == plId || "pl_${it.id}" == parentId }
                        pl?.songs?.map { songToMediaItem(it) } ?: emptyList()
                    } else {
                        emptyList()
                    }
                }
            }

            val paged = if (pageSize > 0 && page >= 0) {
                items.drop(page * pageSize).take(pageSize)
            } else {
                items
            }

            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (!packageValidator.isTrusted(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            val song = findSongById(mediaId)
            return if (song != null) {
                Futures.immediateFuture(LibraryResult.ofItem(songToMediaItem(song), null))
            } else {
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (!packageValidator.isTrusted(controller)) {
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
            }
            handlePlayRequest(mediaItems, startIndex, startPositionMs)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            if (!packageValidator.isTrusted(controller)) {
                return Futures.immediateFuture(mutableListOf())
            }
            handlePlayRequest(mediaItems, 0, 0L)
            return Futures.immediateFuture(mediaItems)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            if (!packageValidator.isTrusted(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            val results = searchSongs(query)
            session.notifySearchResultChanged(browser, query, results.size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!packageValidator.isTrusted(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            val results = searchSongs(query)
            val paged = if (pageSize > 0 && page >= 0) {
                results.drop(page * pageSize).take(pageSize)
            } else {
                results
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            com.ytmusic.clone.data.local.PlayerStateStore.init(this@MusicPlayerService)
            val saved = com.ytmusic.clone.data.local.PlayerStateStore.load()
            val queue = MusicPlayerManager.queue.value.ifEmpty { saved?.queue.orEmpty() }
            val current = MusicPlayerManager.currentSong.value ?: saved?.song ?: queue.firstOrNull()
            if (current == null) {
                return Futures.immediateFailedFuture(IllegalStateException("No song to resume"))
            }
            val finalQueue = if (queue.any { it.id == current.id }) queue else listOf(current) + queue
            val idx = finalQueue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
            val posMs = MusicPlayerManager.currentPosition.value.takeIf { it > 0L } ?: (saved?.positionMs ?: 0L)
            val items = finalQueue.map { songToMediaItem(it) }
            mainHandler.post {
                MusicPlayerManager.playSong(this@MusicPlayerService, current, finalQueue, posMs)
            }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, idx, posMs))
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            if (!packageValidator.isTrusted(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
    }
}

/**
 * Wraps the real player so the lock-screen / notification / headphone / Android Auto Next and Previous
 * buttons use the app's queue (the real player only ever holds one song).
 */
private class QueuePlayer(player: Player) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }
    }

    override fun hasNextMediaItem(): Boolean = MusicPlayerManager.getNextSongInQueue() != null
    override fun hasPreviousMediaItem(): Boolean = MusicPlayerManager.queue.value.isNotEmpty()
    override fun seekToNext() { MusicPlayerManager.playNext() }
    override fun seekToNextMediaItem() { MusicPlayerManager.playNext() }
    override fun seekToPrevious() { MusicPlayerManager.playPrevious() }
    override fun seekToPreviousMediaItem() { MusicPlayerManager.playPrevious() }
}
