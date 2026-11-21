package com.ytmusic.clone.ui.components

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import com.ytmusic.clone.player.MusicPlayerService


import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing


import android.app.Activity
import com.ytmusic.clone.R
import android.content.pm.ActivityInfo
import android.widget.Toast
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ytmusic.clone.player.VideoQuality
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import com.ytmusic.clone.data.network.LyricsUtils
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ytmusic.clone.data.model.ThumbnailUtils
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.model.LyricsResult
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.data.network.LyricsRepository
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.player.RepeatMode
import com.ytmusic.clone.ui.theme.*

@Composable
fun FullPlayerSheet(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    // v7.23: the host moves the whole panel; we only report the finger.
    onPanelDragStart: () -> Unit = {},
    onPanelDrag: (Float) -> Unit = {},
    onPanelDragEnd: (Float) -> Unit = {}
) {
    val context = LocalContext.current

    val currentSong by MusicPlayerManager.currentSong.collectAsState()
    val isPlaying by MusicPlayerManager.isPlaying.collectAsState()
    val isBuffering by MusicPlayerManager.isBuffering.collectAsState()
    val playbackError by MusicPlayerManager.playbackError.collectAsState()
    val position by MusicPlayerManager.currentPosition.collectAsState()
    val duration by MusicPlayerManager.duration.collectAsState()
    val shuffleOn by MusicPlayerManager.shuffleEnabled.collectAsState()
    val repeatMode by MusicPlayerManager.repeatMode.collectAsState()
    val sleepEndsAt by MusicPlayerManager.sleepEndsAt.collectAsState()
    val sleepTimerType by MusicPlayerManager.sleepTimerType.collectAsState()
    val sleepRemaining by MusicPlayerManager.sleepRemainingSeconds.collectAsState()
    val queue by MusicPlayerManager.queue.collectAsState()
    val downloadedSongs by DownloadManager.downloadedSongs.collectAsState()
    val downloadProgress by DownloadManager.downloadProgress.collectAsState()
    val likedSongs by LibraryStore.liked.collectAsState()

    val song = currentSong ?: return
    val isDownloaded = downloadedSongs.any { it.id == song.id }
    val isDownloading = downloadProgress.containsKey(song.id)
    val isFavorite = likedSongs.any { it.id == song.id }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var showQueue by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showOutputDevicesDialog by remember { mutableStateOf(false) }
    var showClientSettings by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    val isVideoSelected by MusicPlayerManager.isVideoMode.collectAsState()
    val videoQuality by MusicPlayerManager.videoQuality.collectAsState()
    var showVideoQualityMenu by remember { mutableStateOf(false) }
    val showFullscreenVideo by MusicPlayerManager.isFullscreenVideo.collectAsState()

    // v7.20: lyrics, playback speed, artwork-matched colours
    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<LyricsResult?>(null) }
    var lyricsAttempt by remember { mutableStateOf(0) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var showPitchSpeedDialog by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubRatio by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    var lastHapticMinute by remember { mutableIntStateOf(-1) }


    // v7.21: panel gestures. dragX = song content following a sideways swipe,
    // dragY = whole panel following a downward swipe.
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) } // net vertical movement, only used to detect "swipe up = queue"
    var gestureBusy by remember { mutableStateOf(false) }
    val gestureScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val speed by MusicPlayerManager.playbackSpeed.collectAsState()
    val accent = rememberArtworkAccent(song.thumbnailUrl)
    val animatedAccent by animateColorAsState(
        targetValue = accent ?: YtSurfaceVariant,
        animationSpec = tween(600),
        label = "playerAccent"
    )
    LaunchedEffect(song.id, showLyrics, lyricsAttempt) {
        if (showLyrics) {
            lyrics = null
            lyrics = LyricsRepository.get(song, duration)
        }
    }

    if (showClientSettings) {
        ClientSettingsDialog(onDismiss = { showClientSettings = false })
    }
    if (showEqualizer) {
        EqualizerSheet(onDismiss = { showEqualizer = false })
    }


    if (showSleepTimer) {
        SleepTimerSheet(onDismiss = { showSleepTimer = false })
    }
    if (showPitchSpeedDialog) {
        PitchSpeedStudioDialog(onDismiss = { showPitchSpeedDialog = false })
    }

    // Back closes the queue first, then the player.
    BackHandler(enabled = showQueue) { showQueue = false }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(YtBackground) // fully opaque base: nothing behind the player can show through
            // Swallow taps on empty areas so they can never reach the song list behind the player
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        // Flagship Apple Music-style Animated Liquid Mesh Gradient Backdrop
        AnimatedLiquidArtworkBackground(
            accentColor = animatedAccent,
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(showQueue) {
                    if (showQueue) return@pointerInput
                    var axis = PanelGesture.Axis.NONE // locked by the first movement of each drag
                    val sideThreshold = 96.dp.toPx()
                    val upThreshold = 80.dp.toPx()
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            axis = PanelGesture.Axis.NONE
                            dragY = 0f
                            tracker.resetTracking()
                        },
                        onDrag = { change, drag ->
                            if (gestureBusy) return@detectDragGestures
                            if (axis == PanelGesture.Axis.NONE) {
                                axis = PanelGesture.lockAxis(drag.x, drag.y)
                                if (axis == PanelGesture.Axis.VERTICAL) onPanelDragStart()
                            }
                            change.consume()
                            if (axis == PanelGesture.Axis.HORIZONTAL) {
                                dragX += drag.x
                            } else {
                                dragY += drag.y
                                tracker.addPosition(change.uptimeMillis, Offset(0f, dragY))
                                onPanelDrag(drag.y) // whole panel follows the finger
                            }
                        },
                        onDragEnd = {
                            if (gestureBusy) return@detectDragGestures
                            if (axis == PanelGesture.Axis.VERTICAL) {
                                val action = PanelGesture.decide(axis, 0f, dragY, sideThreshold, Float.MAX_VALUE, upThreshold)
                                if (action == PanelGesture.Action.OPEN_QUEUE) {
                                    showQueue = true
                                    onPanelDragEnd(0f) // stay open
                                } else {
                                    onPanelDragEnd(tracker.calculateVelocity().y)
                                }
                                dragY = 0f
                                return@detectDragGestures
                            }
                            val action = PanelGesture.decide(axis, dragX, 0f, sideThreshold, Float.MAX_VALUE, upThreshold)
                            gestureScope.launch {
                                gestureBusy = true
                                try {
                                    when (action) {
                                        PanelGesture.Action.NEXT, PanelGesture.Action.PREVIOUS -> {
                                            val dir = if (action == PanelGesture.Action.NEXT) -1 else 1
                                            // slide the song out, switch, slide the new one in
                                            animate(dragX, dir * screenWidthPx, animationSpec = tween(160)) { v, _ -> dragX = v }
                                            if (dir < 0) MusicPlayerManager.playNext() else MusicPlayerManager.playPrevious(forcePrevious = true)
                                            dragX = -dir * screenWidthPx
                                            animate(dragX, 0f, animationSpec = tween(220)) { v, _ -> dragX = v }
                                        }
                                        else -> animate(dragX, 0f, animationSpec = spring()) { v, _ -> dragX = v }
                                    }
                                } finally {
                                    gestureBusy = false
                                }
                            }
                        },
                        onDragCancel = {
                            if (axis == PanelGesture.Axis.VERTICAL) {
                                onPanelDragEnd(0f)
                                dragY = 0f
                            } else {
                                gestureScope.launch { animate(dragX, 0f, animationSpec = spring()) { v, _ -> dragX = v } }
                            }
                        }
                    )
                }
                .padding(horizontal = 24.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: authentic YouTube Music styling (Down Arrow, Song | Video switcher, Cast, More Options)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = YtTextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Iconic YouTube Music "Song | Video" pill switcher
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (!isVideoSelected) YtSurfaceVariant else Color.Transparent)
                            .clickable { MusicPlayerManager.setVideoMode(false) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Song",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isVideoSelected) YtTextPrimary else YtTextSecondary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (isVideoSelected) YtSurfaceVariant else Color.Transparent)
                            .clickable { Toast.makeText(context, "Video mode coming soon!", Toast.LENGTH_SHORT).show() }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Video",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isVideoSelected) YtTextPrimary else YtTextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        openAudioOutputSwitcher(context) {
                            showOutputDevicesDialog = true
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Cast,
                            contentDescription = "Audio Output Devices",
                            tint = YtTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = YtTextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            modifier = Modifier.background(YtSurfaceVariant)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add to playlist", color = YtTextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Add, null, tint = YtTextSecondary) },
                                onClick = { menuOpen = false; showPicker = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Share", color = YtTextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = YtTextSecondary) },
                                onClick = { menuOpen = false; shareSong(context, song) }
                            )
                            DropdownMenuItem(
                                text = { Text("Equalizer", color = YtTextPrimary) },
                                leadingIcon = { Icon(Icons.Default.GraphicEq, null, tint = YtTextSecondary) },
                                onClick = {
                                    menuOpen = false
                                    showEqualizer = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Pitch & Speed", color = YtTextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Speed, null, tint = YtTextSecondary) },
                                onClick = {
                                    menuOpen = false
                                    showPitchSpeedDialog = true
                                }
                            )
                            val sleepTimerLabel = when (sleepTimerType) {
                                MusicPlayerManager.SleepTimerType.END_OF_TRACK -> "Sleep timer (End of song)"
                                MusicPlayerManager.SleepTimerType.DURATION -> {
                                    val rem = sleepRemaining ?: 0L
                                    val mins = (rem + 59) / 60
                                    "Sleep timer (${mins}m left)"
                                }
                                null -> "Sleep timer"
                            }
                            DropdownMenuItem(
                                text = { Text(sleepTimerLabel, color = if (sleepTimerType != null) YtPrimaryRed else YtTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Bedtime,
                                        null,
                                        tint = if (sleepTimerType != null) YtPrimaryRed else YtTextSecondary
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    showSleepTimer = true
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // v7.21: artwork + title follow a sideways swipe (swipe to change song)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = dragX
                        alpha = (1f - abs(dragX) / (screenWidthPx * 1.1f)).coerceIn(0.2f, 1f)
                    }
            ) {
            // Big Album Artwork with ambient fluid glow (or the lyrics panel, same size)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                // Ambient glow pulsing softly behind cover
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.92f)
                        .graphicsLayer {
                            translationY = 16.dp.toPx()
                        }
                        .background(
                            Brush.radialGradient(
                                listOf(animatedAccent.copy(alpha = 0.65f), Color.Transparent)
                            )
                        )
                )

                if (showLyrics) {
                    LyricsPanel(
                        lyrics = lyrics,
                        accent = animatedAccent,
                        onRetry = { lyricsAttempt++ },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    val fullArtworkUrl = remember(song.thumbnailUrl) {
                        ThumbnailUtils.upgrade(song.thumbnailUrl)
                    }

                    AsyncImage(
                        model = fullArtworkUrl,
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Song Info & Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = YtTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(YtSurfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "HQ",
                                color = YtPrimaryRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${song.artist} • ${song.format}",
                            color = YtTextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Download Offline Button
                IconButton(
                    onClick = {
                        if (isDownloaded) {
                            val size = DownloadManager.getSongFileSize(song)
                            val msg = if (size != null) "Saved offline ($size)" else "Already saved for offline playback"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        } else if (!isDownloading) {
                            Toast.makeText(context, "Downloading audio...", Toast.LENGTH_SHORT).show()
                            DownloadManager.downloadSong(context, song) { success ->
                                Toast.makeText(
                                    context,
                                    if (success) "Downloaded for offline playback!" else "Download failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    val downloadPct = downloadProgress[song.id]
                    if (isDownloading && downloadPct != null) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { downloadPct },
                                modifier = Modifier.size(24.dp),
                                color = YtPrimaryRed,
                                strokeWidth = 2.5.dp
                            )
                            Text(
                                text = "${(downloadPct * 100).toInt()}%",
                                fontSize = 8.sp,
                                color = YtPrimaryRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (isDownloaded) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = YtPrimaryRed
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download Offline",
                            tint = YtTextPrimary
                        )
                    }
                }

                // Favorite Button (saved in Library > Liked)
                IconButton(onClick = { LibraryStore.toggleLike(song) }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isFavorite) YtPrimaryRed else YtTextPrimary
                    )
                }
            }

            }

            Spacer(modifier = Modifier.height(18.dp))

            // Scrub Bar (Slider) with Decoupled Scrubbing & Real-time Scrub Bubble
            val currentPlaybackRatio = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
            val displayRatio = if (isScrubbing) scrubRatio else currentPlaybackRatio
            val displayPos = if (isScrubbing) (scrubRatio * duration).toLong().coerceIn(0L, duration) else position
            val deltaSec = ((displayPos - position) / 1000L)

            Slider(
                value = displayRatio,
                onValueChange = { newRatio ->
                    isScrubbing = true
                    scrubRatio = newRatio
                    val targetMs = (newRatio * duration).toLong()
                    val targetMinute = (targetMs / 60_000L).toInt()
                    if (targetMinute != lastHapticMinute && duration > 60_000L) {
                        lastHapticMinute = targetMinute
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } catch (_: Exception) {}
                    }
                },
                onValueChangeFinished = {
                    val finalPos = (scrubRatio * duration).toLong().coerceIn(0L, duration)
                    MusicPlayerManager.seekTo(finalPos)
                    isScrubbing = false
                    lastHapticMinute = -1
                },
                colors = SliderDefaults.colors(
                    thumbColor = YtPrimaryRed,
                    activeTrackColor = YtPrimaryRed,
                    inactiveTrackColor = YtProgressTrack
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Time indicators & Scrub preview bubble
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDuration(displayPos),
                        color = if (isScrubbing) YtPrimaryRed else YtTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isScrubbing) FontWeight.Bold else FontWeight.Normal
                    )
                    if (isScrubbing && kotlin.math.abs(deltaSec) >= 2) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = YtPrimaryRed.copy(alpha = 0.2f)
                        ) {
                            val sign = if (deltaSec > 0) "+" else ""
                            Text(
                                text = "$sign${deltaSec}s",
                                color = YtPrimaryRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(text = formatDuration(duration), color = YtTextSecondary, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Inline Retry banner when network/stream fails (never auto-skips)
            if (playbackError != null && !isBuffering) {
                Surface(
                    color = YtPrimaryRed.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = playbackError ?: "Connection issue",
                            color = YtTextPrimary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                MusicPlayerManager.retryCurrentSong()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = YtPrimaryRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Retry",
                                color = YtPrimaryRed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}; MusicPlayerManager.toggleShuffle() }) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleOn) YtPrimaryRed else YtTextSecondary
                    )
                }

                IconButton(onClick = { try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}; MusicPlayerManager.playPrevious() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = YtTextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Big Play/Pause Button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(YtSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            color = YtPrimaryRed,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        IconButton(
                            onClick = { try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}; MusicPlayerManager.togglePlayPause() },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    playbackError != null -> Icons.Default.Refresh
                                    isPlaying -> Icons.Default.Pause
                                    else -> Icons.Default.PlayArrow
                                },
                                contentDescription = when {
                                    playbackError != null -> "Retry"
                                    isPlaying -> "Pause"
                                    else -> "Play"
                                },
                                tint = if (playbackError != null) YtPrimaryRed else YtTextPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                IconButton(onClick = { try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}; MusicPlayerManager.playNext() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = YtTextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = { try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}; MusicPlayerManager.cycleRepeat() }) {
                    Icon(
                        imageVector = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeatMode == RepeatMode.OFF) YtTextSecondary else YtPrimaryRed
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Small handle: hints that you can swipe up for the queue
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(YtTextSecondary.copy(alpha = 0.6f))
            )

            // Lyrics, playback speed, queue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { showLyrics = !showLyrics },
                    colors = ButtonDefaults.buttonColors(containerColor = if (showLyrics) YtPrimaryRed else YtChipBackground),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Subject,
                        contentDescription = null,
                        tint = YtTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "LYRICS", color = YtTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                val pitch by MusicPlayerManager.pitchSemitones.collectAsState()
                val pitchSpeedLabel = when {
                    speed == 1.25f && pitch == 2 -> "Nightcore"
                    speed == 0.85f && pitch == -2 -> "Slowed"
                    pitch != 0 -> "${speedLabel(speed)} • ${if (pitch > 0) "+$pitch" else "$pitch"}st"
                    speed != 1.0f -> speedLabel(speed)
                    else -> "1.0x"
                }

                Button(
                    onClick = { showPitchSpeedDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = if (speed != 1.0f || pitch != 0) YtPrimaryRed.copy(alpha = 0.25f) else YtChipBackground),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = pitchSpeedLabel,
                        color = if (speed != 1.0f || pitch != 0) YtPrimaryRed else YtTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { showQueue = true },
                    colors = ButtonDefaults.buttonColors(containerColor = YtChipBackground),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = YtTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "UP NEXT (${queue.size})",
                        color = YtTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (showQueue) {
            QueuePanel(onClose = { showQueue = false })
        }
    }

    if (showPicker) {
        PlaylistPickerDialog(song = song, onDismiss = { showPicker = false })
    }

    if (showOutputDevicesDialog) {
        AudioOutputDevicesDialog(onDismiss = { showOutputDevicesDialog = false })
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

fun openAudioOutputSwitcher(context: android.content.Context, onFallback: () -> Unit = {}) {
    // 1. Android 14+ / 15 / 17 (API 34+) Official MediaRouter2 System Output Switcher
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        try {
            val mediaRouter2 = context.getSystemService(android.media.MediaRouter2::class.java)
                ?: android.media.MediaRouter2.getInstance(context)
            mediaRouter2.showSystemOutputSwitcher()
            return
        } catch (e: Exception) {
            android.util.Log.w("AudioOutput", "MediaRouter2 showSystemOutputSwitcher failed: " + e.message)
        }
    }

    // 2. Android 11-13 System Media Output Panel with valid MediaSession token
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        try {
            val intent = android.content.Intent("android.settings.panel.action.MEDIA_OUTPUT").apply {
                putExtra("android.provider.extra.PACKAGE_NAME", context.packageName)
                putExtra("package_name", context.packageName)
                putExtra("com.android.systemui.action.MEDIA_OUTPUT", true)
                // Package name allows SystemUI to bind the active playback session
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("AudioOutput", "Legacy settings panel failed: " + e.message)
        }
    }

    // 3. Fallback to in-app Audio Output Devices Dialog
    onFallback()
}

@Composable
fun AudioOutputDevicesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager }
    val devices = remember {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YtSurface,
        titleContentColor = YtTextPrimary,
        textContentColor = YtTextSecondary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cast, contentDescription = null, tint = YtPrimaryRed, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Audio Output", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Connected audio output devices:",
                    color = YtTextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (devices.isEmpty()) {
                    Text("Playing on Phone Speaker", color = YtTextPrimary, fontSize = 14.sp)
                } else {
                    devices.distinctBy { it.productName.toString() }.forEach { dev ->
                        val isSpeaker = dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        val isBluetooth = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                          dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                          (android.os.Build.VERSION.SDK_INT >= 31 && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                        val icon = when {
                            isBluetooth -> Icons.Default.Headphones
                            isSpeaker -> Icons.Default.PhoneAndroid
                            else -> Icons.Default.Speaker
                        }
                        val label = when {
                            isSpeaker -> "Phone Speaker"
                            dev.productName.isNotBlank() -> dev.productName.toString()
                            isBluetooth -> "Bluetooth Headphones"
                            else -> "External Audio"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(YtSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, contentDescription = null, tint = YtPrimaryRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, color = YtTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (isBluetooth) "Bluetooth Audio" else "Device Output", color = YtTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            val btIntent = android.content.Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(btIntent)
                        } catch (_: Exception) {}
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = YtTextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect New Bluetooth Device", color = YtTextPrimary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = YtTextPrimary)
            }
        }
    )
}



private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format("%d:%02d", mins, secs)
}
