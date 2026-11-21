package com.ytmusic.clone.ui.screens

import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Storage
import com.ytmusic.clone.ui.components.StorageManagerDialog
import androidx.compose.material.icons.filled.Settings
import com.ytmusic.clone.ui.components.AppSettingsDialog
import com.ytmusic.clone.ui.components.EqualizerSheet
import com.ytmusic.clone.ui.components.PitchSpeedStudioDialog
import com.ytmusic.clone.data.local.ImportMode
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search

import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sort
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.local.Playlist
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.player.MusicPlayerManager
import androidx.compose.material.icons.filled.Tune
import com.ytmusic.clone.ui.components.ClientSettingsDialog
import com.ytmusic.clone.ui.components.NewPlaylistDialog
import com.ytmusic.clone.ui.components.SongListItem
import com.ytmusic.clone.ui.theme.*

enum class PlaylistSortOrder(val label: String) {
    DEFAULT("Default order"),
    RECENTLY_ADDED("Recently added"),
    TITLE_ASC("Title (A-Z)"),
    ARTIST_ASC("Artist (A-Z)")
}

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier
) {
    val downloadedSongs by DownloadManager.downloadedSongs.collectAsState()
    val likedSongs by LibraryStore.liked.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()

    var tab by remember { mutableStateOf(0) } // 0 = Downloads, 1 = Liked, 2 = Playlists
    var openPlaylistId by remember { mutableStateOf<String?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showClientSettings by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    val context = LocalContext.current
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showStorageManager by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    val libPrefs = remember(context) { context.getSharedPreferences("yt_library_prefs", Context.MODE_PRIVATE) }
    var playlistSortOrder by remember {
        val savedName = libPrefs.getString("playlist_sort_order", PlaylistSortOrder.DEFAULT.name)
        val initial = PlaylistSortOrder.entries.firstOrNull { it.name == savedName } ?: PlaylistSortOrder.DEFAULT
        mutableStateOf(initial)
    }
    var showPlaylistSortMenu by remember { mutableStateOf(false) }
    var isPlaylistSearchActive by remember { mutableStateOf(false) }
    var playlistSearchQuery by remember { mutableStateOf("") }
    var showEqualizer by remember { mutableStateOf(false) }
    var showPitchSpeed by remember { mutableStateOf(false) }
    var showImportModeDialog by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val scheme = uri.scheme
            val authority = uri.authority?.lowercase(java.util.Locale.US) ?: ""
            val allowedAuthorities = setOf(
                "com.android.externalstorage.documents",
                "com.android.providers.downloads.documents",
                "com.android.providers.media.documents"
            )
            val isKnownDocProvider = allowedAuthorities.contains(authority) || authority.endsWith(".documents")
            val isBlockedSystem = authority == "settings" || authority == "telephony" || authority == "contacts" || authority == "mms" || authority == "call_log"

            if (scheme != "content" || !isKnownDocProvider || isBlockedSystem) {
                Toast.makeText(context, "Export blocked: untrusted destination provider", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }

            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "wt")
                if (pfd != null) {
                    pfd.use { fd ->
                        java.io.FileOutputStream(fd.fileDescriptor).use { os ->
                            val jsonBytes = LibraryStore.exportBackupJson(context).toByteArray(Charsets.UTF_8)
                            os.write(jsonBytes)
                            os.flush()
                            fd.fileDescriptor.sync()
                        }
                    }
                    Toast.makeText(context, "Library exported successfully!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Could not open target for writing", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: " + e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val scheme = uri.scheme
            val authority = uri.authority?.lowercase(java.util.Locale.US) ?: ""
            val allowedAuthorities = setOf(
                "com.android.externalstorage.documents",
                "com.android.providers.downloads.documents",
                "com.android.providers.media.documents"
            )
            val isKnownDocProvider = allowedAuthorities.contains(authority) || authority.endsWith(".documents")
            val isBlockedSystem = authority == "settings" || authority == "telephony" || authority == "contacts" || authority == "mms" || authority == "call_log"

            if (scheme != "content" || !isKnownDocProvider || isBlockedSystem) {
                Toast.makeText(context, "Import blocked: untrusted source provider", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }

            try {
                val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val maxLimit = 15 * 1024 * 1024
                    val baos = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    var totalRead = 0
                    var n: Int
                    while (stream.read(chunk).also { n = it } != -1) {
                        totalRead += n
                        if (totalRead > maxLimit) {
                            return@use null
                        }
                        baos.write(chunk, 0, n)
                    }
                    if (totalRead == 0) return@use null
                    val bytes = baos.toByteArray()
                    var startIdx = 0
                    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                        startIdx = 3
                    }
                    while (startIdx < bytes.size && (bytes[startIdx] == ' '.code.toByte() || bytes[startIdx] == '\n'.code.toByte() || bytes[startIdx] == '\r'.code.toByte() || bytes[startIdx] == '\t'.code.toByte())) {
                        startIdx++
                    }
                    if (startIdx >= bytes.size || bytes[startIdx] != '{'.code.toByte()) {
                        return@use null
                    }
                    String(bytes, startIdx, bytes.size - startIdx, Charsets.UTF_8)
                }
                if (json != null) {
                    pendingImportJson = json
                    showImportModeDialog = true
                } else {
                    Toast.makeText(context, "Invalid or non-JSON backup file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Import read failed: " + e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filteredDownloads = remember(downloadedSongs, searchQuery) {
        if (searchQuery.isBlank()) downloadedSongs
        else downloadedSongs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
    }
    val filteredLiked = remember(likedSongs, searchQuery) {
        if (searchQuery.isBlank()) likedSongs
        else likedSongs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
    }
    val filteredPlaylists = remember(playlists, searchQuery) {
        if (searchQuery.isBlank()) playlists
        else playlists.filter { it.name.contains(searchQuery, true) || it.songs.any { s -> s.title.contains(searchQuery, true) || s.artist.contains(searchQuery, true) } }
    }


    if (showClientSettings) {
        ClientSettingsDialog(onDismiss = { showClientSettings = false })
    }

    val openPlaylist = playlists.firstOrNull { it.id == openPlaylistId }

    BackHandler(enabled = openPlaylist != null) {
        if (isPlaylistSearchActive) {
            isPlaylistSearchActive = false
            playlistSearchQuery = ""
        } else {
            openPlaylistId = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(YtBackground)
    ) {
        if (openPlaylist != null) {
            // ---------- One playlist ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    isPlaylistSearchActive = false
                    playlistSearchQuery = ""
                    openPlaylistId = null
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = YtTextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = openPlaylist.name,
                        color = YtTextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "${openPlaylist.songs.size} songs • ${playlistSortOrder.label}",
                        color = YtTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                IconButton(onClick = {
                    if (isPlaylistSearchActive) {
                        isPlaylistSearchActive = false
                        playlistSearchQuery = ""
                    } else {
                        isPlaylistSearchActive = true
                    }
                }) {
                    Icon(
                        if (isPlaylistSearchActive) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = "Search in playlist",
                        tint = YtTextSecondary
                    )
                }
                Box {
                    IconButton(onClick = { showPlaylistSortMenu = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort playlist", tint = YtTextPrimary)
                    }
                    DropdownMenu(
                        expanded = showPlaylistSortMenu,
                        onDismissRequest = { showPlaylistSortMenu = false },
                        modifier = Modifier.background(YtSurface)
                    ) {
                        PlaylistSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = order.label,
                                        color = if (playlistSortOrder == order) YtPrimaryRed else YtTextPrimary,
                                        fontWeight = if (playlistSortOrder == order) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                trailingIcon = if (playlistSortOrder == order) {
                                    { Icon(Icons.Default.Check, contentDescription = null, tint = YtPrimaryRed) }
                                } else null,
                                onClick = {
                                    playlistSortOrder = order
                                    libPrefs.edit().putString("playlist_sort_order", order.name).apply()
                                    showPlaylistSortMenu = false
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = { playlistToDelete = openPlaylist }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete playlist", tint = YtTextSecondary)
                }
            }
            if (isPlaylistSearchActive) {
                OutlinedTextField(
                    value = playlistSearchQuery,
                    onValueChange = { playlistSearchQuery = it },
                    placeholder = { Text("Filter songs in ${openPlaylist.name}...", color = YtTextSecondary, fontSize = 14.sp) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = YtTextSecondary) },
                    trailingIcon = if (playlistSearchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { playlistSearchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = YtTextSecondary)
                            }
                        }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = YtTextPrimary,
                        unfocusedTextColor = YtTextPrimary,
                        focusedBorderColor = YtPrimaryRed,
                        unfocusedBorderColor = YtSurfaceVariant,
                        cursorColor = YtPrimaryRed
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            val openPlaylistSongs = remember(openPlaylist, searchQuery, playlistSearchQuery, playlistSortOrder) {
                val all = openPlaylist?.songs ?: emptyList()
                val sorted = when (playlistSortOrder) {
                    PlaylistSortOrder.DEFAULT -> all
                    PlaylistSortOrder.RECENTLY_ADDED -> all.asReversed()
                    PlaylistSortOrder.TITLE_ASC -> all.sortedBy { it.title.lowercase() }
                    PlaylistSortOrder.ARTIST_ASC -> all.sortedBy { it.artist.lowercase() }
                }
                val q = if (playlistSearchQuery.isNotBlank()) playlistSearchQuery else searchQuery
                if (q.isBlank()) sorted
                else sorted.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
            }
            SongsTab(
                songs = openPlaylistSongs,
                emptyTitle = if (playlistSearchQuery.isNotBlank()) "No matching songs" else "This playlist is empty",
                emptyText = if (playlistSearchQuery.isNotBlank()) "Try a different title or artist." else "Open the three-dot menu on any song and choose \"Add to playlist\".",
                playlistId = openPlaylist.id,
                showDownloadAll = true
            )
        } else {
            // ---------- Library home ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Library",
                        color = YtTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${downloadedSongs.size} offline • ${likedSongs.size} liked • ${playlists.size} playlists",
                        color = YtTextSecondary,
                        fontSize = 12.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search library",
                            tint = if (isSearchActive) YtPrimaryRed else YtTextSecondary
                        )
                    }
                    IconButton(onClick = { showAppSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = YtTextSecondary
                        )
                    }
                }
            }

            if (isSearchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter offline library...", color = YtTextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = YtTextPrimary,
                        unfocusedTextColor = YtTextPrimary,
                        cursorColor = YtPrimaryRed,
                        focusedBorderColor = YtPrimaryRed,
                        unfocusedBorderColor = YtSurfaceVariant,
                        focusedContainerColor = YtSurfaceVariant,
                        unfocusedContainerColor = YtSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = YtTextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                listOf("Downloads", "Liked", "Playlists").forEachIndexed { index, label ->
                    val selected = tab == index
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(
                                if (selected) YtPrimaryRed else YtChipBackground,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { tab = index }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            color = YtTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (tab) {
                0 -> {
                    Column {
                        val activeProgress by DownloadManager.downloadProgress.collectAsState()
                        val activeQueue by DownloadManager.activeDownloadQueue.collectAsState()

                        // Storage & Cache management row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(YtSurfaceVariant.copy(alpha = 0.5f))
                                .clickable { showStorageManager = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Storage,
                                    contentDescription = null,
                                    tint = YtPrimaryRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Storage: " + DownloadManager.getFormattedTotalSize(LocalContext.current) + " • ${downloadedSongs.size} offline",
                                    color = YtTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Manage",
                                    color = YtPrimaryRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = YtPrimaryRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Official YouTube Music Styled Active Download Banner
                        if (activeQueue.isNotEmpty()) {
                            val currentDownloadingSong = activeQueue.firstOrNull { activeProgress.containsKey(it.id) } ?: activeQueue.firstOrNull()
                            val currentPct = currentDownloadingSong?.let { activeProgress[it.id] } ?: 0f
                            val pctInt = (currentPct * 100).toInt().coerceIn(0, 100)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = YtSurfaceVariant),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                color = YtPrimaryRed,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (activeQueue.size > 1) "Downloading 1 of " + activeQueue.size else "Downloading...",
                                                color = YtTextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Text(
                                            text = "$pctInt%",
                                            color = YtPrimaryRed,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (currentDownloadingSong != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${currentDownloadingSong.title} • ${currentDownloadingSong.artist}",
                                            color = YtTextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { currentPct },
                                        color = YtPrimaryRed,
                                        trackColor = YtBackground,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                        }

                        // Display both actively downloading and finished offline tracks
                        val displayedDownloads = remember(activeQueue, filteredDownloads) {
                            (activeQueue + filteredDownloads).distinctBy { it.id }
                        }

                        SongsTab(
                            songs = displayedDownloads,
                            emptyTitle = "No offline songs yet",
                            emptyText = "Tap the three dots on any song and choose Download to save it for offline listening.",
                            playlistId = null,
                            showDownloadAll = false
                        )
                    }
                }
                1 -> SongsTab(
                    songs = filteredLiked,
                    emptyTitle = "No liked songs yet",
                    emptyText = "Tap the heart in the player (or the three dots on a song) to like it.",
                    playlistId = null,
                    showDownloadAll = true
                )
                else -> PlaylistsTab(
                    playlists = filteredPlaylists,
                    onOpen = { openPlaylistId = it.id },
                    onNew = { showNewPlaylist = true }
                )
            }
        }
    }

    if (showNewPlaylist) {
        NewPlaylistDialog(
            onDismiss = { showNewPlaylist = false },
            onCreate = { name ->
                LibraryStore.createPlaylist(name)
                showNewPlaylist = false
            }
        )
    }

    
    if (showImportModeDialog && pendingImportJson != null) {
        AlertDialog(
            onDismissRequest = {
                showImportModeDialog = false
                pendingImportJson = null
            },
            containerColor = YtSurface,
            titleContentColor = YtTextPrimary,
            textContentColor = YtTextSecondary,
            title = { Text("Restore Library", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    Text(
                        "How would you like to restore this backup file?",
                        color = YtTextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val json = pendingImportJson ?: return@Button
                            showImportModeDialog = false
                            pendingImportJson = null
                            val res = LibraryStore.importBackupJsonDetailed(context, json, ImportMode.MERGE)
                            if (res != null) {
                                val msg = "Merged: " + res.playlistsCount + " playlists & " + res.likedCount + " liked songs" +
                                        (if (res.audioSettingsRestored) " (EQ restored)" else "")
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Invalid backup format", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = YtPrimaryRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Merge with Current Library", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Combines playlists & liked tracks without deleting existing songs.",
                        color = YtTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 12.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val json = pendingImportJson ?: return@OutlinedButton
                            showImportModeDialog = false
                            pendingImportJson = null
                            val res = LibraryStore.importBackupJsonDetailed(context, json, ImportMode.REPLACE)
                            if (res != null) {
                                val msg = "Clean restore: " + res.playlistsCount + " playlists & " + res.likedCount + " liked songs" +
                                        (if (res.audioSettingsRestored) " (EQ restored)" else "")
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Invalid backup format", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clean Restore (Replace Everything)", color = YtTextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Wipes current library and restores the exact backup snapshot.",
                        color = YtTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showImportModeDialog = false
                    pendingImportJson = null
                }) {
                    Text("Cancel", color = YtTextSecondary)
                }
            }
        )
    }

    if (showAppSettings) {
        AppSettingsDialog(
            onDismiss = { showAppSettings = false },
            onOpenStorageManager = { showAppSettings = false; showStorageManager = true },
            onOpenBackupDialog = { showAppSettings = false; showBackupDialog = true },
            onOpenEqualizer = { showAppSettings = false; showEqualizer = true },
            onOpenPitchSpeed = { showAppSettings = false; showPitchSpeed = true }
        )
    }

    if (showEqualizer) {
        EqualizerSheet(onDismiss = { showEqualizer = false })
    }

    if (showPitchSpeed) {
        PitchSpeedStudioDialog(onDismiss = { showPitchSpeed = false })
    }

    if (showStorageManager) {
        StorageManagerDialog(onDismiss = { showStorageManager = false })
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            containerColor = YtSurface,
            titleContentColor = YtTextPrimary,
            textContentColor = YtTextSecondary,
            title = { Text("Backup & Restore Library", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Export your playlists, liked songs, and listening history to an offline JSON file, or restore from a previous backup without cloud sync.",
                        fontSize = 13.sp,
                        color = YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            showBackupDialog = false
                            exportLauncher.launch("ytmusic_backup_" + System.currentTimeMillis() + ".json")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = YtPrimaryRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Backup to File", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showBackupDialog = false
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import Backup from File", color = YtTextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Close", color = YtTextPrimary)
                }
            }
        )
    }

    playlistToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = YtSurface,
            titleContentColor = YtTextPrimary,
            textContentColor = YtTextSecondary,
            title = { Text("Delete playlist?") },
            text = { Text("\"${target.name}\" will be deleted. The songs themselves are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    LibraryStore.deletePlaylist(target.id)
                    openPlaylistId = null
                    playlistToDelete = null
                }) { Text("Delete", color = YtPrimaryRed) }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) { Text("Cancel", color = YtTextPrimary) }
            }
        )
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    emptyTitle: String,
    emptyText: String,
    playlistId: String?,
    showDownloadAll: Boolean
) {
    val context = LocalContext.current

    if (songs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.DownloadDone,
                    contentDescription = null,
                    tint = YtTextSecondary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = emptyTitle,
                    color = YtTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = emptyText,
                    color = YtTextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { MusicPlayerManager.playSong(context, songs.first(), songs) },
            colors = ButtonDefaults.buttonColors(containerColor = YtPrimaryRed),
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = YtTextPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Play all", color = YtTextPrimary, fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = { MusicPlayerManager.shuffleAndPlay(context, songs) },
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(Icons.Default.Shuffle, contentDescription = null, tint = YtTextPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Shuffle", color = YtTextPrimary, fontSize = 13.sp)
        }
        if (showDownloadAll) {
            OutlinedButton(
                onClick = {
                    val queued = DownloadManager.downloadAll(context, songs)
                    Toast.makeText(
                        context,
                        if (queued == 0) "Everything is already downloaded" else "Downloading $queued songs...",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = YtTextPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Download all", color = YtTextPrimary, fontSize = 13.sp)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
    ) {
        itemsIndexed(songs, key = { idx, s -> "${s.id}_$idx" }) { _, song ->
            SongListItem(
                song = song,
                playlistId = playlistId,
                onClick = { MusicPlayerManager.playSong(context, song, songs) }
            )
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    onOpen: (Playlist) -> Unit,
    onNew: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = onNew,
            colors = ButtonDefaults.buttonColors(containerColor = YtChipBackground),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = YtTextPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("New playlist", color = YtTextPrimary, fontSize = 13.sp)
        }

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No playlists yet. Create one, then add songs from the three-dot menu.",
                    color = YtTextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
            ) {
                items(playlists) { pl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(pl) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(YtSurfaceVariant, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = YtTextSecondary)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = pl.name,
                                color = YtTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = "${pl.songs.size} songs",
                                color = YtTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
