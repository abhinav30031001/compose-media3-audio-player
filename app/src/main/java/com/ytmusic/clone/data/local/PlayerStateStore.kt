package com.ytmusic.clone.data.local

import android.content.Context
import com.google.gson.Gson
import com.ytmusic.clone.data.model.Song
import java.io.File

/** What we remember about the player between app launches. */
data class SavedPlayerState(
    val song: Song? = null,
    val queue: List<Song>? = null,
    val originalQueue: List<Song>? = null,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: String = "OFF"
)

/**
 * Saves "what was I listening to" (current song, queue, position, shuffle, repeat) as one
 * small JSON file in the app's private storage. It never leaves the phone.
 * Like LibraryStore, saved songs never keep temporary stream links or download paths.
 */
object PlayerStateStore {
    private const val FILE_NAME = "player_state.json"
    private const val AAD_NAME = "player_state"
    private const val MAX_QUEUE = 200

    private val gson = Gson()
    private val lock = Any()
    private var file: File? = null
    private var loadFailed = false

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE_NAME)
    }

    private fun clean(song: Song) = song.copy(streamUrl = "", isDownloaded = false, localFilePath = null)

    /** Writes the file. Safe to call from any thread. Uses a temp file so it can't be half-written. */
    fun save(state: SavedPlayerState) {
        if (loadFailed) {
            android.util.Log.w("PlayerStateStore", "Skipping save: loadFailed is true, avoiding overwriting unreadable data")
            return
        }
        val f = file ?: return
        val cleaned = state.copy(
            song = state.song?.let { clean(it) },
            queue = state.queue?.take(MAX_QUEUE)?.map { clean(it) },
            originalQueue = state.originalQueue?.take(MAX_QUEUE)?.map { clean(it) }
        )
        synchronized(lock) {
            try {
                val encrypted = LocalCrypto.encrypt(gson.toJson(cleaned), AAD_NAME)
                SafeAtomicFile.writeBytes(f, encrypted)
            } catch (e: CryptoUnavailableException) {
                android.util.Log.w("PlayerStateStore", e.javaClass.simpleName)
            } catch (e: Exception) {
                android.util.Log.w("PlayerStateStore", e.javaClass.simpleName)
            }
        }
    }

    /** Returns null if nothing is saved, or the file is broken. */
    fun load(): SavedPlayerState? {
        val f = file ?: return null
        synchronized(lock) {
            if (!f.exists()) return null
            return try {
                val rawBytes = SafeAtomicFile.readBytes(f)
                val text = LocalCrypto.decryptOrMigrate(rawBytes, AAD_NAME)
                if (text == null) {
                    loadFailed = true
                    val corruptFile = File(f.parentFile, "$FILE_NAME.corrupt")
                    if (!corruptFile.exists()) {
                        f.renameTo(corruptFile)
                    }
                    android.util.Log.w("PlayerStateStore", "Decryption failed, preserving corrupt file and skipping overwrites")
                    return null
                }
                loadFailed = false
                val s = gson.fromJson(text, SavedPlayerState::class.java) ?: return null
                val song = s.song
                if (song == null || song.id.isBlank()) return null
                s.copy(
                    queue = s.queue?.filter { it.id.isNotBlank() },
                    originalQueue = s.originalQueue?.filter { it.id.isNotBlank() }
                )
            } catch (e: Exception) {
                android.util.Log.w("PlayerStateStore", e.javaClass.simpleName)
                null
            }
        }
    }

    fun clear() {
        loadFailed = false
        val f = file ?: return
        synchronized(lock) {
            if (f.exists()) f.delete()
            val corruptFile = File(f.parentFile, "$FILE_NAME.corrupt")
            if (corruptFile.exists()) corruptFile.delete()
        }
    }
}
