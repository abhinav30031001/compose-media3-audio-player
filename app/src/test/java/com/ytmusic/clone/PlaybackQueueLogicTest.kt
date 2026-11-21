package com.ytmusic.clone

import com.ytmusic.clone.data.local.KeyProvider
import com.ytmusic.clone.data.local.LocalCrypto
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.player.RepeatMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class PlaybackQueueLogicTest {

    private val testKey: SecretKey = SecretKeySpec(ByteArray(32) { 0x33 }, "AES")
    private val fakeKeyProvider = object : KeyProvider {
        override fun key(): SecretKey = testKey
    }

    @Before
    fun setUp() {
        LocalCrypto.keyProvider = fakeKeyProvider
    }

    @After
    fun tearDown() {
        LocalCrypto.keyProvider = com.ytmusic.clone.data.local.AndroidKeystoreKeyProvider
    }

    private fun sampleSong(id: String) = Song(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        durationSeconds = 200
    )

    @Test
    fun testShuffleStrictlyPreservesPlaylistBoundsAndRestoresOrder() {
        val s1 = sampleSong("s1")
        val s2 = sampleSong("s2")
        val s3 = sampleSong("s3")
        val playlist = listOf(s1, s2, s3)

        MusicPlayerManager.setQueueForTesting(playlist, s1)
        assertEquals(3, MusicPlayerManager.queue.value.size)

        // Turn shuffle ON
        MusicPlayerManager.toggleShuffle()
        assertTrue(MusicPlayerManager.shuffleEnabled.value)
        assertEquals(3, MusicPlayerManager.queue.value.size)
        assertEquals(s1.id, MusicPlayerManager.queue.value.first().id) // Current song stays at top
        assertTrue(MusicPlayerManager.queue.value.map { it.id }.containsAll(listOf("s1", "s2", "s3")))

        // Turn shuffle OFF -> restores exact original order
        MusicPlayerManager.toggleShuffle()
        assertFalse(MusicPlayerManager.shuffleEnabled.value)
        assertEquals(listOf("s1", "s2", "s3"), MusicPlayerManager.queue.value.map { it.id })
    }

    @Test
    fun testRepeatModeAllLoopsQueueWhileRepeatModeOffHaltsAtEnd() {
        val s1 = sampleSong("s1")
        val s2 = sampleSong("s2")
        val playlist = listOf(s1, s2)

        MusicPlayerManager.setQueueForTesting(playlist, s2)

        // When Repeat OFF: at the end of queue, getNextSongInQueue() returns null
        MusicPlayerManager.setRepeatMode(RepeatMode.OFF)
        assertEquals(RepeatMode.OFF, MusicPlayerManager.repeatMode.value)
        assertNull("At the end of queue with Repeat OFF, next song must be null", MusicPlayerManager.getNextSongInQueue())

        // When Repeat ALL: wraps back to track 0
        MusicPlayerManager.setRepeatMode(RepeatMode.ALL)
        assertEquals(RepeatMode.ALL, MusicPlayerManager.repeatMode.value)
        val nextInAll = MusicPlayerManager.getNextSongInQueue()
        assertNotNull(nextInAll)
        assertEquals("s1", nextInAll?.id)

        // When Repeat ONE: repeatMode is ONE
        MusicPlayerManager.setRepeatMode(RepeatMode.ONE)
        assertEquals(RepeatMode.ONE, MusicPlayerManager.repeatMode.value)
    }
}
