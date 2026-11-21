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
fun PitchSpeedStudioDialog(
    onDismiss: () -> Unit
) {
    val speed by MusicPlayerManager.playbackSpeed.collectAsState()
    val pitch by MusicPlayerManager.pitchSemitones.collectAsState()
    val haptic = LocalHapticFeedback.current

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
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = YtPrimaryRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Pitch & Speed Studio",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtTextPrimary
                            )
                            Text(
                                text = "Media3 Sonic DSP Engine",
                                fontSize = 11.sp,
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

                Spacer(modifier = Modifier.height(16.dp))

                // Presets Row
                Text(
                    text = "STUDIO PRESETS",
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
                    listOf(
                        Triple("Normal", 1.0f, 0),
                        Triple("Nightcore", 1.25f, 2),
                        Triple("Slowed", 0.85f, -2),
                        Triple("Vocal Up", 1.0f, 1),
                        Triple("Vocal Down", 1.0f, -1)
                    ).forEach { (name, sp, pt) ->
                        val isSelected = (speed == sp && pitch == pt)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) YtPrimaryRed else YtChipBackground)
                                .clickable {
                                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                    MusicPlayerManager.setSpeedAndPitch(sp, pt)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = name,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else YtTextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Playback Speed Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Playback Speed",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = YtTextPrimary
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.2fx", speed),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (speed != 1.0f) YtPrimaryRed else YtTextSecondary
                    )
                }

                var lastSpeedHaptic by remember { mutableStateOf(speed) }
                Slider(
                    value = speed,
                    onValueChange = { newSpeed ->
                        if ((lastSpeedHaptic < 1.0f && newSpeed >= 1.0f) || (lastSpeedHaptic > 1.0f && newSpeed <= 1.0f) || newSpeed == 1.0f) {
                            if (lastSpeedHaptic != newSpeed) {
                                try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                            }
                        }
                        lastSpeedHaptic = newSpeed
                        MusicPlayerManager.setPlaybackSpeed(newSpeed)
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    colors = SliderDefaults.colors(
                        thumbColor = YtPrimaryRed,
                        activeTrackColor = YtPrimaryRed,
                        inactiveTrackColor = YtProgressTrack
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Musical Pitch Shift Slider (-6 to +6 semitones)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Musical Pitch Shift",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = YtTextPrimary
                    )
                    Text(
                        text = if (pitch == 0) "Original (0 st)" else if (pitch > 0) "+$pitch semitones" else "$pitch semitones",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (pitch != 0) YtPrimaryRed else YtTextSecondary
                    )
                }

                var lastPitchHaptic by remember { mutableStateOf(pitch) }
                Slider(
                    value = pitch.toFloat(),
                    onValueChange = { newPitchFloat ->
                        val newPitch = newPitchFloat.toInt()
                        if (newPitch != lastPitchHaptic) {
                            try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                            lastPitchHaptic = newPitch
                        }
                        MusicPlayerManager.setPitchSemitones(newPitch)
                    },
                    valueRange = -6f..6f,
                    steps = 11,
                    colors = SliderDefaults.colors(
                        thumbColor = YtPrimaryRed,
                        activeTrackColor = YtPrimaryRed,
                        inactiveTrackColor = YtProgressTrack
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Reset Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                            MusicPlayerManager.resetSpeedAndPitch()
                        }
                    ) {
                        Text("Reset to 1.0x & 0 st", color = YtTextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}


