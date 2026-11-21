package com.ytmusic.clone.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.ui.theme.YtPrimaryRed
import com.ytmusic.clone.ui.theme.YtProgressTrack
import com.ytmusic.clone.ui.theme.YtSurface
import com.ytmusic.clone.ui.theme.YtTextPrimary
import com.ytmusic.clone.ui.theme.YtTextSecondary

/** Opens the Android share sheet with a link to the song. */
fun shareSong(context: Context, song: Song) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "${song.title} - https://music.youtube.com/watch?v=${song.id}")
    }
    val chooser = Intent.createChooser(send, "Share song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Nothing to share with", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YtSurface,
        titleContentColor = YtTextPrimary,
        textContentColor = YtTextSecondary,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 100) name = it },
                singleLine = true,
                placeholder = { Text("Playlist name", color = YtTextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = YtTextPrimary,
                    unfocusedTextColor = YtTextPrimary,
                    cursorColor = YtPrimaryRed,
                    focusedBorderColor = YtPrimaryRed,
                    unfocusedBorderColor = YtProgressTrack
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim().take(100)) }
            ) { Text("Create", color = if (name.isNotBlank()) YtPrimaryRed else YtTextSecondary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = YtTextPrimary) }
        }
    )
}

@Composable
fun PlaylistPickerDialog(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val playlists by LibraryStore.playlists.collectAsState()
    var showNew by remember { mutableStateOf(false) }

    if (showNew) {
        NewPlaylistDialog(
            onDismiss = { showNew = false },
            onCreate = { name ->
                val id = LibraryStore.createPlaylist(name)
                if (id != null) {
                    LibraryStore.addToPlaylist(id, song)
                    Toast.makeText(context, "Added to \"$name\"", Toast.LENGTH_SHORT).show()
                }
                showNew = false
                onDismiss()
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = YtSurface,
            titleContentColor = YtTextPrimary,
            textContentColor = YtTextSecondary,
            title = { Text("Add to playlist") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { showNew = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = YtPrimaryRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New playlist", color = YtPrimaryRed)
                    }
                    playlists.forEach { pl ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val added = LibraryStore.addToPlaylist(pl.id, song)
                                Toast.makeText(
                                    context,
                                    if (added) "Added to \"${pl.name}\"" else "Already in \"${pl.name}\"",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            }
                        ) {
                            Text("${pl.name} (${pl.songs.size})", color = YtTextPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Close", color = YtTextPrimary) }
            }
        )
    }
}
