package com.ytmusic.clone

import com.google.gson.Gson
import com.ytmusic.clone.data.local.LibraryData
import com.ytmusic.clone.data.local.Playlist
import com.ytmusic.clone.data.local.SavedPlayerState
import com.ytmusic.clone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies that all core data models preserved by ProGuard/R8 serialize
 * and deserialize through Gson with complete field fidelity.
 */
class ModelSerializationTest {
    private val gson = Gson()

    @Test
    fun testSongSerializationFidelity() {
        val original = Song(
            id = "kJQP7kiw5Fk",
            title = "Despacito",
            artist = "Luis Fonsi",
            durationSeconds = 229L,
            thumbnailUrl = "https://i.ytimg.com/vi/kJQP7kiw5Fk/maxresdefault.jpg",
            streamUrl = "https://rr1---sn.googlevideo.com/videoplayback",
            isDownloaded = true,
            localFilePath = "/data/user/0/com.ytmusic.clone/files/YTMusicOffline/kJQP7kiw5Fk.opus",
            format = "Opus (160kbps)",
            album = "Vida",
            loudnessDb = -4.5f
        )
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, Song::class.java)

        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.artist, restored.artist)
        assertEquals(original.durationSeconds, restored.durationSeconds)
        assertEquals(original.thumbnailUrl, restored.thumbnailUrl)
        assertEquals(original.streamUrl, restored.streamUrl)
        assertEquals(original.isDownloaded, restored.isDownloaded)
        assertEquals(original.localFilePath, restored.localFilePath)
        assertEquals(original.format, restored.format)
        assertEquals(original.album, restored.album)
        assertEquals(original.loudnessDb, restored.loudnessDb)
    }

    @Test
    fun testPlaylistAndLibraryDataSerialization() {
        val song = Song(id = "track1", title = "Track 1", artist = "Artist 1")
        val playlist = Playlist(id = "pl_1", name = "Favorites", songs = listOf(song))
        val libraryData = LibraryData(
            liked = listOf(song),
            playlists = listOf(playlist),
            history = listOf(song)
        )

        val json = gson.toJson(libraryData)
        val restored = gson.fromJson(json, LibraryData::class.java)

        assertNotNull(restored.liked)
        assertEquals(1, restored.liked!!.size)
        assertEquals("track1", restored.liked!![0].id)

        assertNotNull(restored.playlists)
        assertEquals(1, restored.playlists!!.size)
        assertEquals("pl_1", restored.playlists!![0].id)
        assertEquals("Favorites", restored.playlists!![0].name)

        assertNotNull(restored.history)
        assertEquals(1, restored.history!!.size)
    }

    @Test
    fun testSavedPlayerStateSerialization() {
        val song = Song(id = "current", title = "Current Song", artist = "Artist")
        val state = SavedPlayerState(
            song = song,
            queue = listOf(song),
            originalQueue = listOf(song),
            positionMs = 45000L,
            shuffle = true,
            repeat = "ALL"
        )

        val json = gson.toJson(state)
        val restored = gson.fromJson(json, SavedPlayerState::class.java)

        assertNotNull(restored.song)
        assertEquals("current", restored.song!!.id)
        assertEquals(45000L, restored.positionMs)
        assertEquals(true, restored.shuffle)
        assertEquals("ALL", restored.repeat)
    }
}
