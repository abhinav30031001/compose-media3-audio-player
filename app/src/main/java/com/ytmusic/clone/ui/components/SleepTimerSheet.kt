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
fun SleepTimerSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val timerType by MusicPlayerManager.sleepTimerType.collectAsState()
    val remainingSeconds by MusicPlayerManager.sleepRemainingSeconds.collectAsState()
    val finishSongPref by MusicPlayerManager.finishCurrentSong.collectAsState()

    var selectedMinutes by remember { mutableFloatStateOf(30f) }
    var finishCurrentSong by remember { mutableStateOf(finishSongPref) }

    val presets = remember { listOf(5, 10, 15, 20, 30, 45, 60, 90) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = YtSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(YtPrimaryRed.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = null,
                                tint = YtPrimaryRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Sleep timer",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtTextPrimary
                            )
                            Text(
                                text = "Gentle fade-out at sleep time",
                                fontSize = 12.sp,
                                color = YtTextSecondary
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = YtTextSecondary
                        )
                    }
                }

                // Active status banner (if timer running)
                if (timerType != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = YtPrimaryRed.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                val statusText = when (timerType) {
                                    MusicPlayerManager.SleepTimerType.END_OF_TRACK -> "Active: End of song"
                                    MusicPlayerManager.SleepTimerType.DURATION -> {
                                        val sec = remainingSeconds ?: 0L
                                        val m = sec / 60
                                        val s = sec % 60
                                        "Active: ${m}m ${s}s remaining"
                                    }
                                    null -> ""
                                }
                                Text(
                                    text = statusText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = YtPrimaryRed
                                )
                                Text(
                                    text = if (finishSongPref && timerType == MusicPlayerManager.SleepTimerType.DURATION) "Will finish current song" else "Will fade out gently",
                                    fontSize = 11.sp,
                                    color = YtTextSecondary
                                )
                            }

                            Button(
                                onClick = {
                                    MusicPlayerManager.cancelSleepTimer()
                                    Toast.makeText(context, "Sleep timer turned off", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = YtPrimaryRed),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(text = "Turn off", fontSize = 11.sp, color = YtTextPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Option 1: End of track
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = if (timerType == MusicPlayerManager.SleepTimerType.END_OF_TRACK) YtPrimaryRed.copy(alpha = 0.25f) else YtSurfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            MusicPlayerManager.setSleepTimerEndOfTrack()
                            Toast.makeText(context, "Music will pause when current song finishes", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(YtSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = YtPrimaryRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "End of track",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtTextPrimary
                            )
                            Text(
                                text = "Pause as soon as the current song finishes",
                                fontSize = 12.sp,
                                color = YtTextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Option 2: Quick Presets
                Text(
                    text = "TIME PRESETS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = YtTextSecondary,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { mins ->
                        val isSelected = selectedMinutes.toInt() == mins
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) YtPrimaryRed else YtChipBackground)
                                .clickable {
                                    selectedMinutes = mins.toFloat()
                                    MusicPlayerManager.setSleepTimerDuration(mins, finishSong = finishCurrentSong)
                                    Toast.makeText(context, "Timer set for $mins minutes", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "$mins min",
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = YtTextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Custom Time Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Custom time",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = YtTextPrimary
                    )
                    Text(
                        text = "${selectedMinutes.toInt()} minutes",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = YtPrimaryRed
                    )
                }

                Slider(
                    value = selectedMinutes,
                    onValueChange = { selectedMinutes = it },
                    valueRange = 5f..120f,
                    steps = 22,
                    colors = SliderDefaults.colors(
                        thumbColor = YtPrimaryRed,
                        activeTrackColor = YtPrimaryRed,
                        inactiveTrackColor = YtProgressTrack
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Finish Current Song Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(YtSurfaceVariant)
                        .clickable { finishCurrentSong = !finishCurrentSong }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Finish current song",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = YtTextPrimary
                        )
                        Text(
                            text = "Wait for the track to complete before pausing",
                            fontSize = 11.sp,
                            color = YtTextSecondary
                        )
                    }
                    Switch(
                        checked = finishCurrentSong,
                        onCheckedChange = { finishCurrentSong = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = YtPrimaryRed,
                            checkedTrackColor = YtPrimaryRed.copy(alpha = 0.4f),
                            uncheckedThumbColor = YtTextSecondary,
                            uncheckedTrackColor = YtChipBackground
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Start Custom Timer Button
                Button(
                    onClick = {
                        val mins = selectedMinutes.toInt()
                        MusicPlayerManager.setSleepTimerDuration(mins, finishSong = finishCurrentSong)
                        Toast.makeText(context, "Timer set for $mins minutes", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = YtPrimaryRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = YtTextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Set timer for ${selectedMinutes.toInt()} minutes",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = YtTextPrimary
                    )
                }
            }
        }
    }
}
