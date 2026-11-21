package com.ytmusic.clone.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.ui.theme.*

/**
 * v7.23: the mini player no longer animates the full player itself. Vertical drags are reported to the
 * host ([onPanelDragStart]/[onPanelDrag]/[onPanelDragEnd]) so the full player follows the finger 1:1,
 * exactly like the official app. Sideways drags still change the song.
 */
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPanelDragStart: () -> Unit = {},
    onPanelDrag: (Float) -> Unit = {},
    onPanelDragEnd: (Float) -> Unit = {},
    alphaProvider: () -> Float = { 1f }
) {
    val currentSong by MusicPlayerManager.currentSong.collectAsState()
    val isPlaying by MusicPlayerManager.isPlaying.collectAsState()
    val isBuffering by MusicPlayerManager.isBuffering.collectAsState()
    val playbackError by MusicPlayerManager.playbackError.collectAsState()
    val position by MusicPlayerManager.currentPosition.collectAsState()
    val duration by MusicPlayerManager.duration.collectAsState()

    var dragX by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val swipeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    if (currentSong == null) return

    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = alphaProvider() }
            .background(YtSurfaceVariant)
            .clickable { onClick() }
            .pointerInput(Unit) {
                var axis = PanelGesture.Axis.NONE
                var totalY = 0f
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        axis = PanelGesture.Axis.NONE
                        totalY = 0f
                        tracker.resetTracking()
                    },
                    onDragEnd = {
                        if (axis == PanelGesture.Axis.VERTICAL) {
                            onPanelDragEnd(tracker.calculateVelocity().y)
                        } else if (axis == PanelGesture.Axis.HORIZONTAL && !busy) {
                            val action = PanelGesture.decideMini(axis, dragX, 0f, swipeThresholdPx, Float.MAX_VALUE)
                            scope.launch {
                                busy = true
                                try {
                                    when (action) {
                                        PanelGesture.MiniPlayerAction.NEXT,
                                        PanelGesture.MiniPlayerAction.PREVIOUS -> {
                                            val dir = if (action == PanelGesture.MiniPlayerAction.NEXT) -1 else 1
                                            animate(dragX, dir * screenWidthPx * 0.4f, animationSpec = tween(110)) { v, _ -> dragX = v }
                                            if (dir < 0) MusicPlayerManager.playNext() else MusicPlayerManager.playPrevious(forcePrevious = true)
                                            dragX = -dir * screenWidthPx * 0.4f
                                            animate(dragX, 0f, animationSpec = tween(180)) { v, _ -> dragX = v }
                                        }
                                        else -> animate(dragX, 0f, animationSpec = spring()) { v, _ -> dragX = v }
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        axis = PanelGesture.Axis.NONE
                    },
                    onDragCancel = {
                        if (axis == PanelGesture.Axis.VERTICAL) onPanelDragEnd(0f)
                        else scope.launch { animate(dragX, 0f, animationSpec = spring()) { v, _ -> dragX = v } }
                        axis = PanelGesture.Axis.NONE
                    },
                    onDrag = { change, dragAmount ->
                        if (busy) return@detectDragGestures
                        if (axis == PanelGesture.Axis.NONE) {
                            axis = PanelGesture.lockAxis(dragAmount.x, dragAmount.y)
                            if (axis == PanelGesture.Axis.VERTICAL) onPanelDragStart()
                        }
                        change.consume()
                        if (axis == PanelGesture.Axis.HORIZONTAL) {
                            dragX = (dragX + dragAmount.x).coerceIn(-swipeThresholdPx * 2f, swipeThresholdPx * 2f)
                        } else {
                            totalY += dragAmount.y
                            tracker.addPosition(change.uptimeMillis, Offset(0f, totalY))
                            onPanelDrag(dragAmount.y)
                        }
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = dragX
                    alpha = (1f - kotlin.math.abs(dragX) / (screenWidthPx * 0.6f)).coerceIn(0.3f, 1f)
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = currentSong?.thumbnailUrl,
                contentDescription = currentSong?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = currentSong?.title ?: "",
                    color = YtTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (playbackError != null && !isBuffering) "${playbackError} · Tap to retry" else (currentSong?.artist ?: ""),
                    color = if (playbackError != null && !isBuffering) YtPrimaryRed else YtTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = { MusicPlayerManager.playPrevious() }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = YtTextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            if (isBuffering) {
                CircularProgressIndicator(
                    color = YtPrimaryRed,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(28.dp)
                        .padding(4.dp)
                )
            } else {
                IconButton(onClick = { MusicPlayerManager.togglePlayPause() }) {
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
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            IconButton(onClick = { MusicPlayerManager.playNext() }) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = YtTextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Thin progress bar at bottom of mini player
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = YtPrimaryRed,
            trackColor = YtProgressTrack,
        )
    }
}
