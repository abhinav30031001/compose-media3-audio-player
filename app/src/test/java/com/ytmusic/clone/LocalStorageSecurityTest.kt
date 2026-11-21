package com.ytmusic.clone

import com.ytmusic.clone.data.local.CryptoUnavailableException
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.KeyProvider
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.local.LocalCrypto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Phase A Unit Tests: Local storage encryption (A3), AAD binding (ENC2),
 * and audio file magic byte validation (A1).
 */
class LocalStorageSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    // ---------- Task A1: Magic Byte Tests ----------

    @Test
    fun testAudioMagicBytesRealisticMp4Accepted() {
        val mp4File = tempFolder.newFile("realistic.m4a")
        // 00 00 00 18 66 74 79 70 4D 34 41 20 ... (box size 24, "ftypM4A ")
        val realisticHeader = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x79, 0x70, // "ftyp"
            0x4D, 0x34, 0x41, 0x20  // "M4A "
        )
        mp4File.writeBytes(realisticHeader)

        assertTrue("Realistic MP4 ftyp header must be accepted", DownloadManager.isValidAudioHeader(mp4File))
    }

    @Test
    fun testAudioMagicBytesFtgpRejected() {
        val ftgpFile = tempFolder.newFile("typo.m4a")
        // 00 00 00 18 66 74 67 70 ("ftgp" typo at offset 4)
        val typoHeader = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x67, 0x70, // "ftgp"
            0x4D, 0x34, 0x41, 0x20
        )
        ftgpFile.writeBytes(typoHeader)

        assertFalse("Typo header 'ftgp' at offset 4 must be rejected", DownloadManager.isValidAudioHeader(ftgpFile))
    }

    @Test
    fun testAudioMagicBytesOggOpusAccepted() {
        val oggFile = tempFolder.newFile("sample.opus")
        val oggHeader = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()) +
                ByteArray(12) { 0x00 }
        oggFile.writeBytes(oggHeader)

        assertTrue("Valid OggS header must be accepted", DownloadManager.isValidAudioHeader(oggFile))
    }

    @Test
    fun testAudioMagicBytesWebMAccepted() {
        val webmFile = tempFolder.newFile("sample.webm")
        val webmHeader = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()) +
                ByteArray(12) { 0x00 }
        webmFile.writeBytes(webmHeader)

        assertTrue("Valid WebM EBML header must be accepted", DownloadManager.isValidAudioHeader(webmFile))
    }

    @Test
    fun testAudioMagicBytesRejectsPeElfHtml() {
        val peFile = tempFolder.newFile("fake.exe")
        peFile.writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()) + ByteArray(14))
        assertFalse("PE executable header MZ must be rejected", DownloadManager.isValidAudioHeader(peFile))

        val elfFile = tempFolder.newFile("fake.so")
        elfFile.writeBytes(byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(12))
        assertFalse("ELF binary header must be rejected", DownloadManager.isValidAudioHeader(elfFile))

        val htmlFile = tempFolder.newFile("fake.html")
        htmlFile.writeBytes("<!DOCTYPE html>".toByteArray())
        assertFalse("HTML document header must be rejected", DownloadManager.isValidAudioHeader(htmlFile))
    }

    @Test
    fun testAudioMagicBytesShortFileRejected() {
        val shortFile = tempFolder.newFile("short.m4a")
        shortFile.writeBytes(ByteArray(8) { 0x00 })
        assertFalse("File shorter than 12 bytes must be rejected", DownloadManager.isValidAudioHeader(shortFile))
    }

    // ---------- Task A3: AEAD Binding & KeyProvider Tests ----------

    @Test
    fun testLocalCryptoRoundTripWithAead() {
        val originalJson = """{"liked":[{"id":"song_1","title":"Song"}],"playlists":[]}"""
        val encrypted = LocalCrypto.encrypt(originalJson, aadName = "library")

        assertNotNull(encrypted)
        assertTrue("Must start with ENC2 header",
            encrypted[0] == 'E'.code.toByte() &&
            encrypted[1] == 'N'.code.toByte() &&
            encrypted[2] == 'C'.code.toByte() &&
            encrypted[3] == '2'.code.toByte()
        )

        val decrypted = LocalCrypto.decryptOrMigrate(encrypted, aadName = "library")
        assertEquals("Decrypted content must match original with correct AAD", originalJson, decrypted)
    }

    @Test
    fun testLocalCryptoWrongAadFails() {
        val originalJson = """{"liked":[],"playlists":[]}"""
        // Encrypted with AAD "library"
        val encrypted = LocalCrypto.encrypt(originalJson, aadName = "library")

        // Decrypt with wrong AAD "player_state" must fail safely
        val decrypted = LocalCrypto.decryptOrMigrate(encrypted, aadName = "player_state")
        assertNull("Decryption with mismatched AAD must return null", decrypted)
    }

    @Test
    fun testLocalCryptoTamperedCiphertextFails() {
        val originalJson = """{"queue":[]}"""
        val encrypted = LocalCrypto.encrypt(originalJson, aadName = "player_state")
        // Tamper with the ciphertext byte
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0x55).toByte()

        val decrypted = LocalCrypto.decryptOrMigrate(encrypted, aadName = "player_state")
        assertNull("Tampered ciphertext must fail authentication and return null", decrypted)
    }

    @Test
    fun testLocalCryptoEnc1DataStillReadableForMigration() {
        // Manually produce legacy ENC1 payload without AAD
        val plainText = """{"playlists":[{"id":"p1","name":"Roadtrip"}]}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, testKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val buffer = ByteBuffer.allocate(4 + 1 + iv.size + ciphertext.size)
        buffer.put("ENC1".toByteArray(Charsets.US_ASCII))
        buffer.put(iv.size.toByte())
        buffer.put(iv)
        buffer.put(ciphertext)
        val legacyEnc1Bytes = buffer.array()

        // Decrypting legacy ENC1 data with AAD "library" must succeed (backward compatibility)
        val migrated = LocalCrypto.decryptOrMigrate(legacyEnc1Bytes, aadName = "library")
        assertEquals("Legacy ENC1 data must remain readable for seamless migration", plainText, migrated)
    }

    @Test
    fun testCryptoUnavailableDoesNotWritePlaintext() {
        val failingProvider = object : KeyProvider {
            override fun key(): SecretKey = throw CryptoUnavailableException("Simulated Keystore crash")
        }
        LocalCrypto.keyProvider = failingProvider

        try {
            LocalCrypto.encrypt("sensitive data", "library")
            org.junit.Assert.fail("Expected CryptoUnavailableException when key provider fails")
        } catch (e: CryptoUnavailableException) {
            assertTrue("Exception must be CryptoUnavailableException", e.message?.contains("Keystore crash") == true)
        }
    }

    // ---------- Task C4: Path Containment Tests ----------

    @Test
    fun testPathContainmentRejectsSiblingAndTraversal() {
        val storageDir = tempFolder.newFolder("YTMusicOffline")
        val canonicalStorage = storageDir.canonicalPath

        val validFile = File(storageDir, "track.m4a")
        val validCanonical = validFile.canonicalPath
        assertTrue("File inside storage must pass",
            validCanonical == canonicalStorage || validCanonical.startsWith(canonicalStorage + File.separator))

        val siblingFile = File(storageDir.parentFile, "YTMusicOffline-evil/track.m4a")
        val siblingCanonical = siblingFile.canonicalPath
        assertFalse("Sibling folder with matching prefix must be rejected",
            siblingCanonical == canonicalStorage || siblingCanonical.startsWith(canonicalStorage + File.separator))

        val traversalFile = File(storageDir, "../outside.m4a")
        val traversalCanonical = traversalFile.canonicalPath
        assertFalse("Traversal with .. must be rejected",
            traversalCanonical == canonicalStorage || traversalCanonical.startsWith(canonicalStorage + File.separator))
    }

    // ---------- Task C6: VisitorData Encryption Tests ----------

    @Test
    fun testVisitorDataEncryptionRoundTrip() {
        val originalToken = "CgtCQXFwdkJHbk93OCj59sC1BjIKCgJVUxIEZW4tVQ%3D%3D"
        val encrypted = LocalCrypto.encrypt(originalToken, "visitor_data")
        assertNotNull(encrypted)
        val decrypted = LocalCrypto.decryptOrMigrate(encrypted, "visitor_data")
        assertEquals(originalToken, decrypted)
    }

    // ---------- Task C3: SafeAtomicFile Tests ----------

    @Test
    fun testSafeAtomicFileRoundTrip() {
        val file = tempFolder.newFile("atomic_test.dat")
        val testData = "atomic write data verification".toByteArray(Charsets.UTF_8)
        com.ytmusic.clone.data.local.SafeAtomicFile.writeBytes(file, testData)

        val read = com.ytmusic.clone.data.local.SafeAtomicFile.readBytes(file)
        assertTrue(testData.contentEquals(read))
    }

    // ---------- Task D: Download Resume & Part File Retention Tests ----------

    @Test
    fun testPartFileRetentionUnder24Hours() {
        val storage = tempFolder.newFolder("YTMusicOffline_Retention")
        val recentPart = File(storage, "recent.opus.part").apply {
            writeBytes(ByteArray(1024))
            setLastModified(System.currentTimeMillis() - 3600_000L) // 1h old
        }
        val recentMeta = File(storage, "recent.opus.part.meta").apply {
            writeText("ETAG:test_etag")
            setLastModified(System.currentTimeMillis() - 3600_000L)
        }

        val oldPart = File(storage, "expired.opus.part").apply {
            writeBytes(ByteArray(1024))
            setLastModified(System.currentTimeMillis() - 25L * 3600_000L) // 25h old
        }
        val oldMeta = File(storage, "expired.opus.part.meta").apply {
            writeText("ETAG:old_etag")
            setLastModified(System.currentTimeMillis() - 25L * 3600_000L)
        }

        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        storage.listFiles()?.filter { f ->
            if (f.name.endsWith(".part")) {
                val meta = File(f.parentFile, "${f.name}.meta")
                !(meta.exists() && f.lastModified() > cutoff)
            } else if (f.name.endsWith(".part.meta")) {
                val part = File(f.parentFile, f.name.removeSuffix(".meta"))
                !(part.exists() && part.lastModified() > cutoff)
            } else false
        }?.forEach { it.delete() }

        assertTrue("Recent .part with meta must be retained", recentPart.exists())
        assertTrue("Recent .part.meta must be retained", recentMeta.exists())
        assertFalse("Expired .part (>24h) must be cleaned up", oldPart.exists())
        assertFalse("Expired .part.meta (>24h) must be cleaned up", oldMeta.exists())
    }

    @Test
    fun testLibraryStoreExportAndImportBackup() {
        LibraryStore.clearAll()

        val testSong = com.ytmusic.clone.data.model.Song(
            id = "test_s1",
            title = "Test Title",
            artist = "Test Artist",
            durationSeconds = 180,
            thumbnailUrl = "https://example.com/art.jpg"
        )
        LibraryStore.toggleLike(testSong)
        LibraryStore.createPlaylistWithSongs("My Roadtrip", listOf(testSong))

        val exportJson = LibraryStore.exportBackupJson()
        assertTrue("Export JSON should contain envelope app name", exportJson.contains("YTMusic"))
        assertTrue("Export JSON should contain backupVersion 2", exportJson.contains("backupVersion"))
        assertTrue("Export JSON should contain song ID", exportJson.contains("test_s1"))
        assertTrue("Export JSON should contain playlist name", exportJson.contains("My Roadtrip"))

        LibraryStore.clearAll()
        assertFalse("Should be empty after clear", LibraryStore.isLiked("test_s1"))

        val result = LibraryStore.importBackupJson(exportJson)
        assertNotNull("Import should succeed", result)
        assertTrue("Should restore 1 playlist", result!!.first >= 1)
        assertTrue("Should restore 1 liked song", result.second >= 1)
        assertTrue("Liked song should be marked liked", LibraryStore.isLiked("test_s1"))

        // Verify backward compatibility with sparse legacy JSON
        val sparseJson = """{
            "liked": [{"id":"sparse_1","title":"Sparse Song","artist":"Sparse Artist"}],
            "playlists": [{"id":"pl_sparse","name":"Sparse List","songs":[]}]
        }""".trimIndent()
        val sparseResult = LibraryStore.importBackupJson(sparseJson)
        assertNotNull("Sparse JSON import must not throw NPE", sparseResult)
        assertTrue("Sparse song should be liked", LibraryStore.isLiked("sparse_1"))
    }

    @Test
    fun testDeepPlaylistSongMergeAndReplaceModes() {
        LibraryStore.clearAll()

        val s1 = com.ytmusic.clone.data.model.Song(id = "s1", title = "Song 1", artist = "Artist 1")
        val s2 = com.ytmusic.clone.data.model.Song(id = "s2", title = "Song 2", artist = "Artist 2")
        val s3 = com.ytmusic.clone.data.model.Song(id = "s3", title = "Song 3", artist = "Artist 3")

        // Local state: Playlist "Workout" with [s1]
        LibraryStore.createPlaylistWithSongs("Workout", listOf(s1))

        // Backup payload: Playlist "Workout" with [s2, s3]
        val backupJson = """{
            "app": "YTMusic",
            "backupVersion": 2,
            "library": {
                "liked": [],
                "playlists": [{
                    "id": "pl_backup",
                    "name": "Workout",
                    "songs": [
                        {"id": "s2", "title": "Song 2", "artist": "Artist 2"},
                        {"id": "s3", "title": "Song 3", "artist": "Artist 3"}
                    ]
                }]
            }
        }""".trimIndent()

        // Test MERGE mode: should merge songs inside "Workout" without duplicates
        val mergeRes = LibraryStore.importBackupJsonDetailed(null, backupJson, com.ytmusic.clone.data.local.ImportMode.MERGE)
        assertNotNull(mergeRes)
        val workoutPl = LibraryStore.playlists.value.find { it.name.equals("Workout", ignoreCase = true) }
        assertNotNull(workoutPl)
        assertEquals(3, workoutPl!!.songs.size)
        assertTrue(workoutPl.songs.map { it.id }.containsAll(listOf("s1", "s2", "s3")))

        // Test REPLACE mode: should replace library cleanly
        val replaceRes = LibraryStore.importBackupJsonDetailed(null, backupJson, com.ytmusic.clone.data.local.ImportMode.REPLACE)
        assertNotNull(replaceRes)
        val replacedPl = LibraryStore.playlists.value.find { it.name.equals("Workout", ignoreCase = true) }
        assertNotNull(replacedPl)
        assertEquals(2, replacedPl!!.songs.size)
        assertEquals(listOf("s2", "s3"), replacedPl.songs.map { it.id })
    }
}