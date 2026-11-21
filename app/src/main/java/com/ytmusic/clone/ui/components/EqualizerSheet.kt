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
fun EqualizerSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val eqEnabled by MusicPlayerManager.eqEnabled.collectAsState()
    val bands by MusicPlayerManager.eqBands.collectAsState()
    val bassBoost by MusicPlayerManager.bassBoostStrength.collectAsState()
    val virtualizer by MusicPlayerManager.virtualizerStrength.collectAsState()
    val isNormEnabled by MusicPlayerManager.isNormalizationEnabledFlow.collectAsState()
    val crossfadeSec by MusicPlayerManager.crossfadeSeconds.collectAsState()
    val currentPreset by MusicPlayerManager.currentPreset.collectAsState()
    val haptic = LocalHapticFeedback.current

    val presets = remember {
        listOf("Flat", "Bass Boost", "Vocal Booster", "Rock", "Pop", "Electronic")
    }

    LaunchedEffect(Unit) {
        MusicPlayerManager.ensureEffectsInitialized(context)
    }

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
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = YtPrimaryRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Equalizer",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtTextPrimary
                            )
                            Text(
                                text = if (eqEnabled) "5-Band Studio DSP Active" else "Bypassed (Pure Original Audio)",
                                fontSize = 12.sp,
                                color = if (eqEnabled) YtPrimaryRed else YtTextSecondary
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                MusicPlayerManager.setEqPreset("Flat")
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset Flat",
                                tint = YtTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Reset", color = YtTextSecondary, fontSize = 12.sp)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = YtTextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Master Equalizer Enable / Bypass Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(YtSurfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Equalizer",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = YtTextPrimary
                        )
                        Text(
                            text = if (eqEnabled) "DSP active" else "Off — identical to official YT Music",
                            fontSize = 11.sp,
                            color = YtTextSecondary
                        )
                    }
                    Switch(
                        checked = eqEnabled,
                        onCheckedChange = {
                            try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                            MusicPlayerManager.setEqEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = YtPrimaryRed,
                            uncheckedThumbColor = YtTextSecondary,
                            uncheckedTrackColor = YtBackground
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Presets Horizontal Row
                Text(
                    text = "PRESETS",
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
                    presets.forEach { preset ->
                        val isSelected = currentPreset == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) YtPrimaryRed else YtChipBackground)
                                .clickable {
                                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                    MusicPlayerManager.setEqPreset(preset)
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = preset,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = YtTextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 5 Frequency Sliders
                Text(
                    text = "FREQUENCY BANDS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = YtTextSecondary,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (bands.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Play a song to activate equalizer sliders",
                            color = YtTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    bands.forEach { band ->
                        val levelDb = band.levelMillibels / 100
                        val freqText = when {
                            band.centerFreqHz >= 1000 -> "${band.centerFreqHz / 1000} kHz"
                            else -> "${band.centerFreqHz} Hz"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = freqText,
                                fontSize = 12.sp,
                                color = YtTextPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(64.dp)
                            )

                            var lastHapticLevel by remember(band.index) { mutableStateOf(band.levelMillibels) }
                            Slider(
                                value = band.levelMillibels.toFloat(),
                                onValueChange = { newVal ->
                                    val newLevel = newVal.toInt().toShort()
                                    if ((lastHapticLevel < 0 && newLevel >= 0) || (lastHapticLevel > 0 && newLevel <= 0) || newLevel == 0.toShort()) {
                                        if (lastHapticLevel != newLevel) {
                                            try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                        }
                                    }
                                    lastHapticLevel = newLevel
                                    MusicPlayerManager.setBandLevel(band.index, newLevel, saveToDisk = false, isManualAdjustment = true)
                                },
                                onValueChangeFinished = {
                                    MusicPlayerManager.saveEqSettings()
                                },
                                valueRange = -1500f..1500f,
                                colors = SliderDefaults.colors(
                                    thumbColor = YtPrimaryRed,
                                    activeTrackColor = YtPrimaryRed,
                                    inactiveTrackColor = YtProgressTrack
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = if (levelDb > 0) "+$levelDb dB" else "$levelDb dB",
                                fontSize = 11.sp,
                                color = if (levelDb != 0) YtPrimaryRed else YtTextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(48.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bass Boost Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = YtSurfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Bass Boost",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtTextPrimary
                            )
                            Text(
                                text = "${bassBoost / 10}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtPrimaryRed
                            )
                        }
                        var lastBassHaptic by remember { mutableStateOf(bassBoost) }
                        Slider(
                            value = bassBoost.toFloat(),
                            onValueChange = { newVal ->
                                val s = newVal.toInt().toShort()
                                if ((lastBassHaptic != 0.toShort() && s == 0.toShort()) || (lastBassHaptic != 500.toShort() && s in 490..510) || (lastBassHaptic != 1000.toShort() && s == 1000.toShort())) {
                                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                }
                                lastBassHaptic = s
                                MusicPlayerManager.setBassBoost(s, saveToDisk = false, isManualAdjustment = true)
                            },
                            onValueChangeFinished = {
                                MusicPlayerManager.saveEqSettings()
                            },
                            valueRange = 0f..1000f,
                            colors = SliderDefaults.colors(
                                thumbColor = YtPrimaryRed,
                                activeTrackColor = YtPrimaryRed,
                                inactiveTrackColor = YtProgressTrack
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Surround 3D Virtualizer Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = YtSurfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Surround Sound (3D)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtTextPrimary
                            )
                            Text(
                                text = "${virtualizer / 10}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = YtPrimaryRed
                            )
                        }
                        var lastVirtHaptic by remember { mutableStateOf(virtualizer) }
                        Slider(
                            value = virtualizer.toFloat(),
                            onValueChange = { newVal ->
                                val s = newVal.toInt().toShort()
                                if ((lastVirtHaptic != 0.toShort() && s == 0.toShort()) || (lastVirtHaptic != 500.toShort() && s in 490..510) || (lastVirtHaptic != 1000.toShort() && s == 1000.toShort())) {
                                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                }
                                lastVirtHaptic = s
                                MusicPlayerManager.setVirtualizer(s, saveToDisk = false, isManualAdjustment = true)
                            },
                            onValueChangeFinished = {
                                MusicPlayerManager.saveEqSettings()
                            },
                            valueRange = 0f..1000f,
                            colors = SliderDefaults.colors(
                                thumbColor = YtPrimaryRed,
                                activeTrackColor = YtPrimaryRed,
                                inactiveTrackColor = YtProgressTrack
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Sound Check / Volume Normalization Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = YtSurfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Volume Normalization",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = YtTextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = YtPrimaryRed.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "SOUND CHECK",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = YtPrimaryRed,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Maintains consistent perceived loudness across all songs without manual volume adjustments",
                                fontSize = 11.sp,
                                color = YtTextSecondary,
                                lineHeight = 15.sp
                            )
                        }

                        Switch(
                            checked = isNormEnabled,
                            onCheckedChange = {
                                try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                MusicPlayerManager.setVolumeNormalizationEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = YtPrimaryRed,
                                uncheckedThumbColor = YtTextSecondary,
                                uncheckedTrackColor = YtBackground
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Audio Crossfade & Gapless Playback Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = YtSurfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Crossfade & Gapless",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = YtTextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = YtPrimaryRed.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = if (crossfadeSec == 0) "GAPLESS" else "${crossfadeSec}s",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = YtPrimaryRed,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (crossfadeSec == 0) "Off" else "${crossfadeSec} sec",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (crossfadeSec > 0) YtPrimaryRed else YtTextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Fades between tracks with zero silence (Poweramp/Spotify studio curve)",
                            fontSize = 11.sp,
                            color = YtTextSecondary,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0 to "Off", 3 to "3s", 5 to "5s", 8 to "8s", 12 to "12s").forEach { (sec, label) ->
                                val isSelected = crossfadeSec == sec
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) YtPrimaryRed else YtChipBackground)
                                        .clickable {
                                        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                        MusicPlayerManager.setCrossfadeSeconds(sec)
                                    }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else YtTextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                        MusicPlayerManager.setEqPreset("Flat")
                    }) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = YtTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Reset Flat", color = YtTextSecondary, fontSize = 12.sp)
                    }

                    TextButton(onClick = { MusicPlayerManager.openSystemSoundSettings(context) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = YtTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Device Settings", color = YtTextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}


