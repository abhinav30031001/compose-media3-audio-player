package com.ytmusic.clone.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.imageLoader
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.ExtractorPrefs
import com.ytmusic.clone.player.AudioCache
import com.ytmusic.clone.player.PrefetchManager
import com.ytmusic.clone.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object StorageUtils {
    fun getImageCacheSizeBytes(context: Context): Long {
        return try {
            val dir = File(context.cacheDir, "image_cache")
            if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        } catch (_: Exception) { 0L }
    }

    fun getStreamCacheSizeBytes(context: Context): Long {
        return try {
            val dir = File(context.cacheDir, "media3_audio_cache")
            if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        } catch (_: Exception) { 0L }
    }

    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    fun clearImageCache(context: Context) {
        try {
            context.imageLoader.diskCache?.clear()
            context.imageLoader.memoryCache?.clear()
        } catch (_: Exception) {
            val dir = File(context.cacheDir, "image_cache")
            dir.deleteRecursively()
        }
    }

    fun clearTemporaryCaches(context: Context) {
        AudioCache.clear(context)
        clearImageCache(context)
        PrefetchManager.clearCache()
    }
}

@Composable
fun StorageManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadedSongs by DownloadManager.downloadedSongs.collectAsState()

    var streamCacheBytes by remember { mutableLongStateOf(StorageUtils.getStreamCacheSizeBytes(context)) }
    var imageCacheBytes by remember { mutableLongStateOf(StorageUtils.getImageCacheSizeBytes(context)) }
    val downloadBytes = remember(downloadedSongs) { DownloadManager.getTotalDownloadedBytes(context) }

    var showClearDownloadsConfirm by remember { mutableStateOf(false) }

    fun refreshSizes() {
        streamCacheBytes = StorageUtils.getStreamCacheSizeBytes(context)
        imageCacheBytes = StorageUtils.getImageCacheSizeBytes(context)
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
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = YtPrimaryRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Storage & Memory",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = YtTextPrimary
                        )
                        val totalAppUsed = downloadBytes + streamCacheBytes + imageCacheBytes
                        Text(
                            text = "App storage used: ${StorageUtils.formatBytes(totalAppUsed)}",
                            fontSize = 12.sp,
                            color = YtTextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = YtTextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Storage Breakdown Bar
                val totalUsed = (downloadBytes + streamCacheBytes + imageCacheBytes).coerceAtLeast(1L).toFloat()
                val downloadRatio = (downloadBytes.toFloat() / totalUsed).coerceIn(0f, 1f)
                val streamRatio = (streamCacheBytes.toFloat() / totalUsed).coerceIn(0f, 1f)
                val imageRatio = (imageCacheBytes.toFloat() / totalUsed).coerceIn(0f, 1f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(YtSurfaceVariant)
                ) {
                    if (downloadRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(downloadRatio.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(YtPrimaryRed)
                        )
                    }
                    if (streamRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(streamRatio.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(Color(0xFFFFA000)) // Amber
                        )
                    }
                    if (imageRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(imageRatio.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(Color(0xFF29B6F6)) // Light Blue
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LegendItem("Downloads", StorageUtils.formatBytes(downloadBytes), YtPrimaryRed)
                    LegendItem("Audio Cache", StorageUtils.formatBytes(streamCacheBytes), Color(0xFFFFA000))
                    LegendItem("Artwork", StorageUtils.formatBytes(imageCacheBytes), Color(0xFF29B6F6))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Card 1: Temporary Caches (Safe to clear)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Temporary Stream & Image Cache",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = YtTextPrimary
                                )
                                Text(
                                    text = "${StorageUtils.formatBytes(streamCacheBytes + imageCacheBytes)} can be freed safely",
                                    fontSize = 12.sp,
                                    color = YtTextSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            StorageUtils.clearTemporaryCaches(context)
                                        }
                                        refreshSizes()
                                        Toast.makeText(context, "Temporary cache cleared!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(32.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = YtPrimaryRed)
                            ) {
                                Text("Clear Cache", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Frees up storage without deleting any of your saved offline songs or playlists.",
                            fontSize = 11.sp,
                            color = YtTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Card 2: Offline Downloads Management
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Offline Downloads",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = YtTextPrimary
                                )
                                Text(
                                    text = "${downloadedSongs.size} songs • ${StorageUtils.formatBytes(downloadBytes)}",
                                    fontSize = 12.sp,
                                    color = YtTextSecondary
                                )
                            }
                            OutlinedButton(
                                onClick = { showClearDownloadsConfirm = true },
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(32.dp),
                                enabled = downloadedSongs.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = if (downloadedSongs.isNotEmpty()) YtPrimaryRed else YtTextSecondary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear All", fontSize = 12.sp, color = if (downloadedSongs.isNotEmpty()) YtPrimaryRed else YtTextSecondary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Downloaded audio files saved on your phone for offline playback.",
                            fontSize = 11.sp,
                            color = YtTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Done Button
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = YtSurfaceVariant),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done", color = YtTextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showClearDownloadsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearDownloadsConfirm = false },
            containerColor = YtSurface,
            titleContentColor = YtTextPrimary,
            textContentColor = YtTextSecondary,
            title = { Text("Delete all downloads?") },
            text = { Text("This will remove all ${downloadedSongs.size} offline audio files from your device. Your liked tracks and playlists will not be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    DownloadManager.clearAllDownloads(context)
                    refreshSizes()
                    showClearDownloadsConfirm = false
                    Toast.makeText(context, "All downloads deleted", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Delete All", color = YtPrimaryRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDownloadsConfirm = false }) {
                    Text("Cancel", color = YtTextPrimary)
                }
            }
        )
    }
}

@Composable
private fun LegendItem(label: String, size: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(label, color = YtTextSecondary, fontSize = 10.sp)
            Text(size, color = YtTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
