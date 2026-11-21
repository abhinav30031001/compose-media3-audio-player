package com.ytmusic.clone.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.local.PlayerStateStore
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.player.AudioCache
import com.ytmusic.clone.data.local.ExtractorPrefs
import com.ytmusic.clone.player.PrefetchManager
import com.ytmusic.clone.ui.theme.*

@Composable
fun AppSettingsDialog(
    onDismiss: () -> Unit,
    onOpenStorageManager: () -> Unit,
    onOpenBackupDialog: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenPitchSpeed: () -> Unit
) {
    val context = LocalContext.current
    var showDeveloperDiagnostics by remember { mutableStateOf(false) }

    val isNormEnabled by MusicPlayerManager.isNormalizationEnabledFlow.collectAsState()
    val autoplayEnabled by MusicPlayerManager.autoplayEnabled.collectAsState()
    val crossfadeSec by MusicPlayerManager.crossfadeSeconds.collectAsState()
    var lyricsEnabled by remember { mutableStateOf(ExtractorPrefs.isLyricsLookupEnabled()) }
    var showEraseAllConfirm by remember { mutableStateOf(false) }

    if (showDeveloperDiagnostics) {
        ClientSettingsDialog(onDismiss = { showDeveloperDiagnostics = false })
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = YtSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = YtPrimaryRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = YtTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = YtTextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 1: Playback & Sound
                SettingsSectionHeader("Playback & Sound")

                SettingsRowItem(
                    icon = Icons.Default.GraphicEq,
                    title = "Equalizer & Sound Effects",
                    subtitle = "5-band studio EQ, Bass Boost & 3D Virtualizer",
                    onClick = onOpenEqualizer
                )

                SettingsRowItem(
                    icon = Icons.Default.Speed,
                    title = "Pitch & Speed Studio",
                    subtitle = "Semitone pitch shifting and variable speed",
                    onClick = onOpenPitchSpeed
                )

                SettingsToggleRow(
                    icon = Icons.Default.VolumeUp,
                    title = "Volume Normalization",
                    subtitle = "Even out volume differences across songs",
                    checked = isNormEnabled,
                    onCheckedChange = { MusicPlayerManager.setVolumeNormalizationEnabled(it) }
                )

                SettingsToggleRow(
                    icon = Icons.Default.Refresh,
                    title = "Infinite Autoplay",
                    subtitle = "Keep music playing with similar songs at end of queue",
                    checked = autoplayEnabled,
                    onCheckedChange = { MusicPlayerManager.setAutoplayEnabled(it) }
                )

                SettingsRowItem(
                    icon = Icons.Default.SwapHoriz,
                    title = "Crossfade Duration",
                    subtitle = if (crossfadeSec == 0) "Gapless (0s)" else "${crossfadeSec}s smooth transition",
                    onClick = {
                        val nextSec = when (crossfadeSec) {
                            0 -> 3
                            3 -> 5
                            5 -> 8
                            8 -> 12
                            else -> 0
                        }
                        MusicPlayerManager.setCrossfadeSeconds(nextSec)
                    }
                )

                SettingsToggleRow(
                    icon = Icons.Default.MusicNote,
                    title = "Online Synced Lyrics",
                    subtitle = "Fetch synchronized karaoke lyrics from LRCLIB",
                    checked = lyricsEnabled,
                    onCheckedChange = {
                        lyricsEnabled = it
                        ExtractorPrefs.setLyricsLookupEnabled(it)
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 2: Storage & Data Portability
                SettingsSectionHeader("Storage & Data")

                SettingsRowItem(
                    icon = Icons.Default.Storage,
                    title = "Storage & Memory Manager",
                    subtitle = "Manage offline downloads, audio cache & artwork",
                    onClick = onOpenStorageManager
                )

                SettingsRowItem(
                    icon = Icons.Default.SaveAlt,
                    title = "Backup & Restore Library",
                    subtitle = "Export or restore playlists and liked songs via JSON",
                    onClick = onOpenBackupDialog
                )

                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 3: Privacy & Security
                SettingsSectionHeader("Privacy & Security")

                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = YtSurfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Defensive Protection Active", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = YtTextPrimary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Keystore AES-256-GCM AEAD encryption at rest\n• SafeDNS private IP & SSRF blocking\n• Zero telemetry, zero third-party analytics",
                            fontSize = 11.sp,
                            color = YtTextSecondary,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    ExtractorPrefs.clearVisitorData()
                                    PrefetchManager.clearCache()
                                    Toast.makeText(context, "Anonymous identity token reset", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset Identity", fontSize = 11.sp, color = YtTextPrimary)
                            }
                            OutlinedButton(
                                onClick = {
                                    LibraryStore.clearHistory()
                                    Toast.makeText(context, "Listening history cleared", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear History", fontSize = 11.sp, color = YtTextPrimary)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showEraseAllConfirm = true },
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Erase All App Data", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                // SECTION 4: Developer Labs & Network Diagnostics (Organized testing area)
                SettingsSectionHeader("Developer Labs & Diagnostics")

                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = YtSurfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = YtTextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Diagnostic Testing Suite", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = YtTextPrimary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "InnerTube client tiers, circuit breaker status, synthetic stream probes, and live network retry logs.",
                            fontSize = 11.sp,
                            color = YtTextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showDeveloperDiagnostics = true },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = YtSurfaceVariant),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = YtPrimaryRed, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Diagnostic Tools", fontSize = 11.sp, color = YtTextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Close
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = YtSurfaceVariant),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close", color = YtTextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    if (showEraseAllConfirm) {
        AlertDialog(
            onDismissRequest = { showEraseAllConfirm = false },
            containerColor = YtSurface,
            titleContentColor = YtTextPrimary,
            textContentColor = YtTextSecondary,
            title = { Text("Erase All App Data?") },
            text = { Text("This will permanently delete all playlists, liked tracks, listening history, downloaded offline files, audio caches, and reset your anonymous device identity.") },
            confirmButton = {
                TextButton(onClick = {
                    LibraryStore.clearAll()
                    PlayerStateStore.clear()
                    DownloadManager.clearAllDownloads(context)
                    AudioCache.clear(context)
                    PrefetchManager.clearCache()
                    ExtractorPrefs.clearVisitorData()
                    showEraseAllConfirm = false
                    Toast.makeText(context, "All app data, library, and downloads erased", Toast.LENGTH_LONG).show()
                }) {
                    Text("Erase Everything", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEraseAllConfirm = false }) {
                    Text("Cancel", color = YtTextPrimary)
                }
            }
        )
    }

}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = YtPrimaryRed,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = YtTextSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = YtTextPrimary)
            Text(subtitle, fontSize = 11.sp, color = YtTextSecondary)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = YtTextSecondary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = YtTextSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = YtTextPrimary)
            Text(subtitle, fontSize = 11.sp, color = YtTextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = YtPrimaryRed,
                uncheckedThumbColor = YtTextSecondary,
                uncheckedTrackColor = YtBackground
            )
        )
    }

}
