package com.ytmusic.clone.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import coil.compose.AsyncImage
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.ui.theme.YtBackground
import com.ytmusic.clone.ui.theme.YtPrimaryRed
import com.ytmusic.clone.ui.theme.YtSurface
import com.ytmusic.clone.ui.theme.YtSurfaceVariant
import com.ytmusic.clone.ui.theme.YtTextPrimary
import com.ytmusic.clone.ui.theme.YtTextSecondary

/**
 * v7.20: song row with swipe gestures. Swipe right = "Play next", swipe left = "Add to queue".
 * The row always snaps back after the action. Tap, long menu etc. are unchanged (see [SongListItemBody]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListItem(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playlistId: String? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    // v7.23: must be false when the row sits inside a horizontally scrolling container
    // (e.g. the Quick picks carousel), otherwise the row steals the carousel's swipes.
    swipeToQueue: Boolean = true
) {
    if (!swipeToQueue) {
        Box(modifier = modifier.fillMaxWidth().background(YtBackground)) {
            SongListItemBody(
                song = song,
                onClick = onClick,
                playlistId = playlistId,
                horizontalPadding = horizontalPadding
            )
        }
        return
    }
    val context = LocalContext.current
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    MusicPlayerManager.playNextInQueue(song)
                    Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    val added = MusicPlayerManager.addToQueue(song)
                    Toast.makeText(context, if (added) "Added to queue" else "Already in queue", Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
            false // never dismiss: the row snaps back
        }
    )
    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier,
        backgroundContent = {
            val toRight = swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val toLeft = swipeState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (toRight || toLeft) YtSurfaceVariant else YtBackground)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toLeft) Arrangement.End else Arrangement.Start
            ) {
                if (toRight) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, tint = YtTextPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Play next", color = YtTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else if (toLeft) {
                    Text("Add to queue", color = YtTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = YtTextPrimary)
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(YtBackground)) {
            SongListItemBody(
                song = song,
                onClick = onClick,
                playlistId = playlistId,
                horizontalPadding = horizontalPadding
            )
        }
    }
}

/**
 * One song row. The three-dot menu is built in: play next, add to queue, like, add to playlist,
 * download / remove download, share (and "remove from playlist" when [playlistId] is given).
 */
@Composable
private fun SongListItemBody(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playlistId: String? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp
) {
    val context = LocalContext.current
    val downloaded by DownloadManager.downloadedSongs.collectAsState()
    val progress by DownloadManager.downloadProgress.collectAsState()
    val liked by LibraryStore.liked.collectAsState()

    val isDownloaded = downloaded.any { it.id == song.id }
    val downloadPct = progress[song.id]
    val isDownloading = downloadPct != null
    val isLiked = liked.any { it.id == song.id }
    val currentSong by MusicPlayerManager.currentSong.collectAsState()
    val isPlaying by MusicPlayerManager.isPlaying.collectAsState()
    val isCurrent = currentSong?.id == song.id

    var menuOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingExportSong by remember { mutableStateOf<Song?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/*")
    ) { uri ->
        val targetSong = pendingExportSong
        pendingExportSong = null
        if (uri != null && targetSong != null) {
            Toast.makeText(context, "Exporting \"${targetSong.title}\"...", Toast.LENGTH_SHORT).show()
            DownloadManager.exportSongToUri(context, targetSong, uri) { ok, err ->
                if (ok) {
                    Toast.makeText(context, "Exported successfully to your device!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, err ?: "Export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = song.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                        contentDescription = "Now Playing",
                        tint = YtPrimaryRed,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 4.dp)
                    )
                }
                Text(
                    text = song.title,
                    color = if (isCurrent) YtPrimaryRed else YtTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDownloading) {
                    val pct = ((downloadPct ?: 0f) * 100).toInt().coerceIn(0, 100)
                    CircularProgressIndicator(
                        progress = { downloadPct ?: 0f },
                        color = YtPrimaryRed,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "$pct%",
                        color = YtPrimaryRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                } else if (isDownloaded) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded Offline",
                        tint = YtPrimaryRed,
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 4.dp)
                    )
                }

                val fileSize = if (isDownloaded) DownloadManager.getSongFileSize(song) else null
                val subtitle = when {
                    isDownloading -> "Downloading..."
                    fileSize != null -> "${song.artist} • $fileSize"
                    song.durationSeconds > 0 -> "${song.artist} • ${song.durationFormatted}"
                    else -> song.artist
                }
                Text(
                    text = subtitle,
                    color = if (isDownloading) YtPrimaryRed else YtTextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = YtTextSecondary
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(YtSurfaceVariant)
            ) {
                SongMenuRow("Play next", Icons.Default.SkipNext) {
                    menuOpen = false
                    MusicPlayerManager.playNextInQueue(song)
                    Toast.makeText(context, "Will play next", Toast.LENGTH_SHORT).show()
                }
                SongMenuRow("Add to queue", Icons.Default.QueueMusic) {
                    menuOpen = false
                    val added = MusicPlayerManager.addToQueue(song)
                    Toast.makeText(
                        context,
                        if (added) "Added to queue" else "Already in queue",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                SongMenuRow(
                    if (isLiked) "Remove from liked songs" else "Like",
                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder
                ) {
                    menuOpen = false
                    LibraryStore.toggleLike(song)
                }
                SongMenuRow("Add to playlist", Icons.Default.Add) {
                    menuOpen = false
                    showPicker = true
                }
                if (playlistId != null) {
                    SongMenuRow("Remove from this playlist", Icons.Default.Close) {
                        menuOpen = false
                        LibraryStore.removeFromPlaylist(playlistId, song.id)
                    }
                }
                if (isDownloaded) {
                    SongMenuRow("Export to device (SAF)", Icons.Default.SaveAlt) {
                        menuOpen = false
                        val fullSong = downloaded.firstOrNull { it.id == song.id } ?: song
                        pendingExportSong = fullSong
                        val defaultName = DownloadManager.sanitizeExportFileName(fullSong)
                        exportLauncher.launch(defaultName)
                    }
                    SongMenuRow("Remove download", Icons.Default.Delete) {
                        menuOpen = false
                        showDeleteConfirm = true
                    }
                } else if (!isDownloading) {
                    SongMenuRow("Download", Icons.Default.Download) {
                        menuOpen = false
                        Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
                        DownloadManager.downloadSong(context, song) { ok ->
                            Toast.makeText(
                                context,
                                if (ok) "Downloaded for offline playback!" else "Download failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                SongMenuRow("Share", Icons.Default.Share) {
                    menuOpen = false
                    shareSong(context, song)
                }
            }
        }
    }

    if (showPicker) {
        PlaylistPickerDialog(song = song, onDismiss = { showPicker = false })
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = YtSurface,
            titleContentColor = YtTextPrimary,
            textContentColor = YtTextSecondary,
            title = { Text("Delete download?") },
            text = { Text("\"${song.title}\" will be removed from your phone. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    DownloadManager.deleteSong(context, song.id)
                    showDeleteConfirm = false
                }) { Text("Delete", color = YtPrimaryRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = YtTextPrimary)
                }
            }
        )
    }
}

@Composable
private fun SongMenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = YtTextPrimary) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = YtTextSecondary) },
        onClick = onClick
    )
}
