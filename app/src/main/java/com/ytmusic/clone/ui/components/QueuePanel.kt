package com.ytmusic.clone.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import coil.compose.AsyncImage
import com.ytmusic.clone.data.model.*
import com.ytmusic.clone.data.network.*
import com.ytmusic.clone.data.local.*
import com.ytmusic.clone.player.*
import com.ytmusic.clone.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueuePanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val queue by MusicPlayerManager.queue.collectAsState()
    val current by MusicPlayerManager.currentSong.collectAsState()
    val currentIndex = queue.indexOfFirst { it.id == current?.id }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0)
    )
    var showSaveDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // ---- Drag & drop (v7.40) ----
    // The lifted row follows the finger exactly: its translation is (finger target - its REAL layout
    // position) read from the list's live layout info, so no hard-coded row height can drift.
    // Swaps and edge auto-scroll run in one frame-synchronous loop; the pointer handler only
    // reports where the finger is.
    val scope = rememberCoroutineScope()
    var draggingSongId by remember { mutableStateOf<String?>(null) }
    var grabOffsetY by remember { mutableFloatStateOf(0f) } // finger position inside the lifted row
    var fingerY by remember { mutableFloatStateOf(0f) }     // finger y in list coordinates
    val lastDragTranslation = remember { FloatArray(1) }
    var settleKey by remember { mutableStateOf<String?>(null) }
    val settleOffset = remember { Animatable(0f) }

    fun endDrag() {
        val key = draggingSongId ?: return
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        val residual = if (info != null) (fingerY - grabOffsetY) - info.offset else lastDragTranslation[0]
        draggingSongId = null
        MusicPlayerManager.commitQueueOrder()
        // Settle softly into the slot instead of snapping (like the official app)
        settleKey = key
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            settleOffset.snapTo(residual)
            settleOffset.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            )
            if (settleKey == key) settleKey = null
        }
    }

    LaunchedEffect(draggingSongId) {
        val key = draggingSongId ?: return@LaunchedEffect
        while (isActive && draggingSongId == key) {
            val layout = listState.layoutInfo
            val dragged = layout.visibleItemsInfo.firstOrNull { it.key == key }
            if (dragged != null) {
                val top = fingerY - grabOffsetY
                val center = top + dragged.size / 2f
                val fromIndex = queue.indexOfFirst { it.id == key }
                // Only swap when the layout matches the queue (skips the single frame after a swap)
                if (fromIndex == dragged.index) {
                    val target = layout.visibleItemsInfo.firstOrNull {
                        it.key != key && center >= it.offset && center < it.offset + it.size
                    }
                    if (target != null && target.index != fromIndex) {
                        MusicPlayerManager.moveQueueItem(fromIndex, target.index, saveToDisk = false)
                        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                    }
                }

                // Edge auto-scroll: speed grows the closer the finger is to the viewport edge
                val viewportStart = layout.viewportStartOffset
                val viewportEnd = layout.viewportEndOffset
                val zone = (viewportEnd - viewportStart) * 0.16f
                val delta = when {
                    fingerY < viewportStart + zone ->
                        -(6f + ((viewportStart + zone - fingerY) / zone).coerceIn(0f, 1f) * 24f)
                    fingerY > viewportEnd - zone ->
                        6f + ((fingerY - (viewportEnd - zone)) / zone).coerceIn(0f, 1f) * 24f
                    else -> 0f
                }
                if (delta != 0f) listState.scrollBy(delta)
            }
            withFrameNanos { }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(YtBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Queue Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Close queue",
                    tint = YtTextPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = "Up next (${queue.size})",
                color = YtTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { showSaveDialog = true },
                enabled = queue.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Save queue as playlist",
                    tint = if (queue.isNotEmpty()) YtTextPrimary else YtTextSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }
            TextButton(
                onClick = { MusicPlayerManager.clearUpcoming() },
                enabled = queue.isNotEmpty()
            ) {
                Text(
                    text = "Clear upcoming",
                    color = if (queue.isNotEmpty()) YtPrimaryRed else YtTextSecondary.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Infinite Autoplay (Similar Song Radio) Switch
        val autoplay by MusicPlayerManager.autoplayEnabled.collectAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(YtSurfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Autoplay",
                    tint = if (autoplay) YtPrimaryRed else YtTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Infinite Autoplay",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = YtTextPrimary
                    )
                    Text(
                        text = "Similar songs keep playing when queue ends",
                        fontSize = 10.sp,
                        color = YtTextSecondary
                    )
                }
            }
            Switch(
                checked = autoplay,
                onCheckedChange = { MusicPlayerManager.setAutoplayEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = YtPrimaryRed,
                    uncheckedThumbColor = YtTextSecondary,
                    uncheckedTrackColor = YtBackground
                )
            )
        }

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("The queue is empty", color = YtTextSecondary)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val handleWidthPx = with(density) { 68.dp.toPx() }
                        awaitEachGesture {
                            // Initial pass: we see the touch BEFORE the list's own scroll detector
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (down.position.x < size.width - handleWidthPx) return@awaitEachGesture
                            val touched = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                down.position.y >= it.offset && down.position.y <= it.offset + it.size
                            } ?: return@awaitEachGesture
                            val songId = touched.key as? String ?: return@awaitEachGesture
                            if (queue.none { it.id == songId }) return@awaitEachGesture

                            // The handle owns this gesture: no list scrolling, no row tap
                            down.consume()
                            var dragging = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    if (!dragging &&
                                        kotlin.math.abs(change.position.y - down.position.y) > viewConfiguration.touchSlop
                                    ) {
                                        dragging = true
                                        grabOffsetY = change.position.y - touched.offset
                                        fingerY = change.position.y
                                        lastDragTranslation[0] = 0f
                                        settleKey = null
                                        draggingSongId = songId
                                        try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                    }
                                    if (dragging) fingerY = change.position.y
                                    change.consume()
                                }
                            } finally {
                                if (dragging) endDrag()
                            }
                        }
                    }
            ) {
                itemsIndexed(
                    items = queue,
                    key = { _, item -> item.id }
                ) { index, item ->
                    val isCurrent = item.id == current?.id
                    val isDragging = item.id == draggingSongId
                    val isSettling = item.id == settleKey
                    val animatedElevation by animateDpAsState(
                        targetValue = if (isDragging) 12.dp else 0.dp,
                        label = "queueItemElevation"
                    )
                    val liftScale by animateFloatAsState(
                        targetValue = if (isDragging) 1.02f else 1f,
                        label = "queueItemScale"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .zIndex(if (isDragging || isSettling) 50f else 1f)
                            // The lifted row must NOT run placement animation (it follows the finger);
                            // every other row slides smoothly out of its way.
                            .then(
                                if (isDragging) Modifier
                                else Modifier.animateItemPlacement(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            )
                            .graphicsLayer {
                                translationY = when {
                                    isDragging -> {
                                        val info = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == item.id }
                                        val t = if (info != null) {
                                            (fingerY - grabOffsetY) - info.offset
                                        } else {
                                            lastDragTranslation[0]
                                        }
                                        lastDragTranslation[0] = t
                                        t
                                    }
                                    isSettling -> settleOffset.value
                                    else -> 0f
                                }
                                shadowElevation = animatedElevation.toPx()
                                shape = RoundedCornerShape(8.dp)
                                clip = false
                                scaleX = liftScale
                                scaleY = liftScale
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isDragging || isSettling -> YtSurfaceVariant
                                    isCurrent -> YtPrimaryRed.copy(alpha = 0.12f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable(enabled = !isDragging) { MusicPlayerManager.playSong(item) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = item.thumbnailUrl,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Playing",
                                        tint = YtPrimaryRed,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = if (isCurrent) YtPrimaryRed else YtTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isCurrent) "Now playing • ${item.artist}" else item.artist,
                                color = if (isCurrent) YtPrimaryRed.copy(alpha = 0.8f) else YtTextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (isCurrent) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = YtPrimaryRed.copy(alpha = 0.2f),
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Text(
                                    text = "PLAYING",
                                    color = YtPrimaryRed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!isCurrent) {
                                IconButton(
                                    onClick = { MusicPlayerManager.removeFromQueue(item.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove from queue",
                                        tint = YtTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .padding(end = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = if (isDragging) YtPrimaryRed else YtTextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        var playlistName by remember { mutableStateOf("Queue (${queue.size} songs)") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = {
                Text("Save Queue as Playlist", color = YtTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    Text(
                        "Save all ${queue.size} songs currently in your queue as a new playlist in your library.",
                        color = YtTextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Playlist Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = YtPrimaryRed,
                            unfocusedBorderColor = YtTextSecondary.copy(alpha = 0.5f),
                            focusedLabelColor = YtPrimaryRed,
                            cursorColor = YtPrimaryRed
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            val saved = MusicPlayerManager.saveQueueAsPlaylist(playlistName.trim())
                            if (saved) {
                                Toast.makeText(context, "Saved to library as '$playlistName'", Toast.LENGTH_SHORT).show()
                            }
                            showSaveDialog = false
                        }
                    }
                ) {
                    Text("Save", color = YtPrimaryRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = YtTextSecondary)
                }
            },
            containerColor = YtSurface
        )
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%d:%02d", min, sec)
}



