package com.ytmusic.clone.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.pointer.*
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

@Composable
internal fun LyricsPanel(
    lyrics: LyricsResult?,
    accent: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val position by MusicPlayerManager.currentPosition.collectAsState()
    val duration by MusicPlayerManager.duration.collectAsState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Flagship Apple Music-style Animated Liquid Mesh Gradient Backdrop in Lyrics Panel
        val isPlaying by MusicPlayerManager.isPlaying.collectAsState()
        AnimatedLiquidArtworkBackground(
            accentColor = accent,
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxSize()
        )
        when (lyrics) {
            null -> CircularProgressIndicator(
                color = YtPrimaryRed,
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp)
            )
            is LyricsResult.Found -> {
                // If lyrics are already millisecond-synced (from LRC), use them directly!
                // If plain text and duration becomes known/updated, re-estimate using actual duration.
                val lines = remember(lyrics.text, lyrics.isSynced, if (lyrics.isSynced) 0L else duration) {
                    if (lyrics.lines.isNotEmpty() && lyrics.isSynced) {
                        lyrics.lines
                    } else {
                        LyricsUtils.parse(lyrics.text, if (duration > 0) duration else 180_000L)
                    }
                }

                // Accurate active line tracking:
                // Only highlight a line if position has reached its timeMs!
                // During instrumental interludes or long musical pauses, avoid keeping lines artificially highlighted.
                val activeIndex = remember(position, lines) {
                    if (lines.isEmpty()) -1
                    else {
                        val idx = lines.indexOfLast { position >= it.timeMs }
                        if (idx == -1) -1
                        else {
                            val activeLine = lines[idx]
                            val nextLine = lines.getOrNull(idx + 1)
                            val lineGap = if (nextLine != null) (nextLine.timeMs - activeLine.timeMs) else 9000L
                            val words = activeLine.text.split(Regex("""\s+""")).size
                            val maxSingingTime = (words * 400L + 2500L).coerceIn(3000L, 8500L)
                            if (lineGap > 9000L && position > activeLine.timeMs + maxSingingTime) {
                                -1 // Musical interlude: dim highlight until next line begins
                            } else {
                                idx
                            }
                        }
                    }
                }

                val listState = rememberLazyListState()

                // Auto-scroll to keep active line centered (with 2 preceding lines visible for context)
                LaunchedEffect(activeIndex) {
                    if (activeIndex >= 0 && lines.isNotEmpty()) {
                        val target = (activeIndex - 2).coerceAtLeast(0)
                        listState.animateScrollToItem(target)
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Top header badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = YtPrimaryRed,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (lyrics.isSynced) "SYNCED KARAOKE LYRICS" else "LYRICS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtTextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Text(
                            text = "Tap line to seek",
                            fontSize = 10.sp,
                            color = YtTextSecondary
                        )
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(lines) { index, line ->
                            val isActive = index == activeIndex
                            val animatedAlpha by animateFloatAsState(
                                targetValue = if (isActive) 1.0f else 0.35f,
                                animationSpec = tween(250),
                                label = "lyricAlpha"
                            )
                            val animatedScale by animateFloatAsState(
                                targetValue = if (isActive) 1.04f else 1.0f,
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f),
                                label = "lyricScale"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = animatedScale
                                        scaleY = animatedScale
                                        alpha = animatedAlpha
                                    }
                                    .clickable {
                                        MusicPlayerManager.seekTo(line.timeMs)
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = line.text,
                                    color = if (isActive) Color.White else YtTextSecondary,
                                    fontSize = if (isActive) 21.sp else 17.sp,
                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    lineHeight = if (isActive) 30.sp else 24.sp
                                )
                            }
                        }

                        if (!lyrics.source.isNullOrBlank()) {
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = lyrics.source,
                                    color = YtTextSecondary.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
            LyricsResult.NotAvailable -> Text(
                text = "No lyrics available for this song",
                color = YtTextPrimary,
                fontSize = 15.sp,
                modifier = Modifier.padding(24.dp)
            )
            is LyricsResult.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Couldn't load lyrics", color = YtTextPrimary, fontSize = 15.sp)
                TextButton(onClick = onRetry) { Text("Retry", color = YtPrimaryRed) }
            }
        }
    }
}


