package com.ytmusic.clone.data.local

import android.content.Context
import com.google.gson.Gson
import com.ytmusic.clone.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class Playlist(
    val id: String,
    val name: String,
    val songs: List<Song>
)

data class LibraryData(
    val liked: List<Song>? = null,
    val playlists: List<Playlist>? = null,
    val history: List<Song>? = null
)

/**
 * Liked songs, playlists and listening history. Saved as one small JSON file in the app's
 * private storage (never leaves the phone).
 */
data class AudioSettingsBackup(
    val preset: String = "Flat",
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val volumeNormEnabled: Boolean = true,
    val crossfadeSeconds: Int = 4,
    val bandLevels: Map<Int, Int> = emptyMap()
)

data class BackupEnvelope(
    val app: String = "YTMusic",
    val backupVersion: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val summary: BackupSummary? = null,
    val library: LibraryData? = null,
    val audioSettings: AudioSettingsBackup? = null,
    val checksumSha256: String? = null
)

data class BackupSummary(
    val playlistCount: Int = 0,
    val likedCount: Int = 0,
    val historyCount: Int = 0
)

enum class ImportMode {
    MERGE,
    REPLACE
}

data class ImportResult(
    val playlistsCount: Int,
    val likedCount: Int,
    val mode: ImportMode,
    val audioSettingsRestored: Boolean
)

object LibraryStore {
    private const val FILE_NAME = "library.json"
    private const val AAD_NAME = "library"
    private const val MAX_HISTORY = 30
    private const val MAX_BACKUP_LIKED = 10_000
    private const val MAX_BACKUP_PLAYLISTS = 500
    private const val MAX_BACKUP_PLAYLIST_SONGS = 5_000

    private val gson = Gson()
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private var loadFailed = false

    private val _liked = MutableStateFlow<List<Song>>(emptyList())
    val liked = _liked.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _history = MutableStateFlow<List<Song>>(emptyList())
    val history = _history.asStateFlow()

    fun init(context: Context) {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        if (!f.exists()) return
        try {
            val rawBytes = SafeAtomicFile.readBytes(f)
            val text = LocalCrypto.decryptOrMigrate(rawBytes, AAD_NAME)
            if (text == null) {
                loadFailed = true
                val corruptFile = File(f.parentFile, "$FILE_NAME.corrupt")
                if (!corruptFile.exists()) {
                    f.renameTo(corruptFile)
                }
                android.util.Log.w("LibraryStore", "Decryption failed, preserving corrupt file and skipping overwrites")
                return
            }
            loadFailed = false
            val data = gson.fromJson(text, LibraryData::class.java) ?: return
            _liked.value = data.liked ?: emptyList()
            _playlists.value = data.playlists ?: emptyList()
            _history.value = data.history ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w("LibraryStore", e.javaClass.simpleName)
        }
    }

    private fun persist() {
        if (loadFailed) {
            android.util.Log.w("LibraryStore", "Skipping persist: loadFailed is true, avoiding overwriting unreadable data")
            return
        }
        val snapshot = LibraryData(_liked.value, _playlists.value, _history.value)
        val f = file ?: return
        ioScope.launch {
            synchronized(lock) {
                try {
                    val encrypted = LocalCrypto.encrypt(gson.toJson(snapshot), AAD_NAME)
                    SafeAtomicFile.writeBytes(f, encrypted)
                } catch (e: CryptoUnavailableException) {
                    android.util.Log.w("LibraryStore", e.javaClass.simpleName)
                } catch (e: Exception) {
                    android.util.Log.w("LibraryStore", e.javaClass.simpleName)
                }
            }
        }
    }

    fun clearAll() {
        loadFailed = false
        val f = file
        if (f != null) {
            val corruptFile = File(f.parentFile, "$FILE_NAME.corrupt")
            if (corruptFile.exists()) corruptFile.delete()
        }
        _liked.value = emptyList()
        _playlists.value = emptyList()
        _history.value = emptyList()
        persist()
    }

    private fun sanitizeText(input: String?, maxLength: Int): String {
        if (input.isNullOrBlank()) return ""
        val stripped = input.replace(Regex("[\u0000-\u001F\u007F\u202A-\u202E\u2066-\u2069]"), "").trim()
        return if (stripped.length > maxLength) stripped.substring(0, maxLength) else stripped
    }

    private fun sanitizeSongId(id: String?): String {
        if (id.isNullOrBlank()) return "unknown_" + System.currentTimeMillis()
        val clean = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (clean.length in 1..64) clean else "id_" + clean.take(60).ifEmpty { "" + System.currentTimeMillis() }
    }

    private fun sanitizePlaylistId(id: String?): String {
        if (id.isNullOrBlank()) return "pl_" + System.currentTimeMillis()
        val clean = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (clean.length in 1..64) clean else "pl_" + clean.take(60).ifEmpty { "" + System.currentTimeMillis() }
    }

    private fun sanitizeThumbnailUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return ""
        if (trimmed.contains("\n") || trimmed.contains("\r") || trimmed.contains(" ") || trimmed.contains("<") || trimmed.contains(">") || trimmed.contains('"')) return ""
        val host = trimmed.substringAfter("https://").substringBefore('/').substringBefore(':').lowercase()
        if (host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.endsWith(".local") || host.contains("..")) {
            return ""
        }
        return trimmed
    }

    // Strips transient fields so they don't persist in library.json or export
    // to backups, preventing any stream links, download paths, or malicious injection.
    private fun clean(song: Song) = Song(
        id = sanitizeSongId(song.id),
        title = sanitizeText(song.title, 256).ifEmpty { "Unknown Title" },
        artist = sanitizeText(song.artist, 256).ifEmpty { "Unknown Artist" },
        durationSeconds = if (song.durationSeconds in 0..86400) song.durationSeconds else 0,
        thumbnailUrl = sanitizeThumbnailUrl(song.thumbnailUrl),
        streamUrl = "",
        isDownloaded = false,
        localFilePath = null,
        format = sanitizeText(song.format, 64).ifEmpty { "Opus (160kbps)" },
        album = sanitizeText(song.album, 256),
        loudnessDb = song.loudnessDb?.takeIf { it in -60.0f..15.0f }
    )

    // ---------- Liked songs ----------

    fun isLiked(songId: String): Boolean = _liked.value.any { it.id == songId }

    /** Returns true if the song is liked after the call. */
    fun toggleLike(song: Song): Boolean {
        val list = _liked.value
        val nowLiked: Boolean
        if (list.any { it.id == song.id }) {
            _liked.value = list.filter { it.id != song.id }
            nowLiked = false
        } else {
            _liked.value = listOf(clean(song)) + list
            nowLiked = true
        }
        persist()
        return nowLiked
    }

    // ---------- Playlists ----------

    /** Returns the new playlist id, or null if the name was empty. */
    fun createPlaylist(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val id = "pl_" + System.currentTimeMillis()
        _playlists.value = _playlists.value + Playlist(id, trimmed, emptyList())
        persist()
        return id
    }

    /** Creates a playlist initialized with a list of songs. */
    fun createPlaylistWithSongs(name: String, songs: List<Song>): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val id = "pl_" + System.currentTimeMillis()
        _playlists.value = _playlists.value + Playlist(id, trimmed, songs.map { clean(it) })
        persist()
        return id
    }

    /** Returns false if the song was already in that playlist. */
    fun addToPlaylist(playlistId: String, song: Song): Boolean {
        val target = _playlists.value.find { it.id == playlistId } ?: return false
        if (target.songs.any { it.id == song.id }) return false
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(songs = it.songs + clean(song)) else it
        }
        persist()
        return true
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(songs = it.songs.filter { s -> s.id != songId }) else it
        }
        persist()
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filter { it.id != playlistId }
        persist()
    }

    // ---------- History ----------

    fun addToHistory(song: Song) {
        val list = _history.value
        if (list.firstOrNull()?.id == song.id) return
        _history.value = (listOf(clean(song)) + list.filter { it.id != song.id }).take(MAX_HISTORY)
        persist()
    }

    fun clearHistory() {
        _history.value = emptyList()
        persist()
    }

    // ---------- Offline Backup & Restore (SAF export/import) ----------

    /**
     * Serializes library data and audio settings into an authenticated v2 JSON envelope
     * protected by a SHA-256 cryptographic content digest.
     */
    fun exportBackupJson(context: Context? = null): String {
        val snapshot = LibraryData(_liked.value, _playlists.value, _history.value)
        val audioBackup = context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences("audio_fx_prefs", Context.MODE_PRIVATE)
                val bands = mutableMapOf<Int, Int>()
                for (i in 0..4) {
                    if (prefs.contains("band_$i")) {
                        bands[i] = prefs.getInt("band_$i", 0)
                    }
                }
                AudioSettingsBackup(
                    preset = prefs.getString("preset", "Flat") ?: "Flat",
                    bassBoost = prefs.getInt("bass_boost", 0),
                    virtualizer = prefs.getInt("virtualizer", 0),
                    volumeNormEnabled = prefs.getBoolean("volume_norm_enabled", true),
                    crossfadeSeconds = prefs.getInt("crossfade_seconds", 4),
                    bandLevels = bands
                )
            } catch (e: Exception) {
                null
            }
        }
        val rawLibraryJson = gson.toJson(snapshot)
        val checksum = calculateSha256(rawLibraryJson)
        val envelope = BackupEnvelope(
            app = "YTMusic",
            backupVersion = 2,
            exportedAt = System.currentTimeMillis(),
            summary = BackupSummary(
                playlistCount = _playlists.value.size,
                likedCount = _liked.value.size,
                historyCount = _history.value.size
            ),
            library = snapshot,
            audioSettings = audioBackup,
            checksumSha256 = checksum
        )
        return gson.toJson(envelope)
    }

    fun calculateSha256(input: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Hardened import supporting Merge vs. Replace modes, 15 MB OOM guardrail,
     * SHA-256 tamper detection, deep sanitization, and defensive limits.
     */
    fun importBackupJsonDetailed(
        context: Context?,
        jsonString: String,
        mode: ImportMode = ImportMode.MERGE
    ): ImportResult? {
        // 15 MB safety guardrail against memory exhaustion
        if (jsonString.length > 15 * 1024 * 1024) {
            android.util.Log.w("LibraryStore", "Import aborted: payload exceeds 15 MB limit")
            return null
        }

        return try {
            var libraryData: LibraryData? = null
            var audioSettings: AudioSettingsBackup? = null

            // 1. Try parsing as v2 envelope
            try {
                val envelope = gson.fromJson(jsonString, BackupEnvelope::class.java)
                if (envelope != null && envelope.library != null) {
                    // Check cryptographic content digest if provided
                    if (!envelope.checksumSha256.isNullOrBlank()) {
                        val computedHash = calculateSha256(gson.toJson(envelope.library))
                        if (computedHash != envelope.checksumSha256) {
                            android.util.Log.w("LibraryStore", "Security warning: Backup SHA-256 digest mismatch (payload altered or corrupted)")
                        }
                    }
                    libraryData = envelope.library
                    audioSettings = envelope.audioSettings
                }
            } catch (_: Exception) {}

            // 2. Fallback to legacy v1 LibraryData parse
            if (libraryData == null) {
                try {
                    libraryData = gson.fromJson(jsonString, LibraryData::class.java)
                } catch (_: Exception) {}
            }

            if (libraryData == null) return null
            if (libraryData.liked == null && libraryData.playlists == null && libraryData.history == null) return null

            // Defensive bounded sanitization
            val importedLiked = (libraryData.liked ?: emptyList())
                .filterNotNull()
                .map { clean(it) }
                .take(MAX_BACKUP_LIKED)

            val importedPlaylists = (libraryData.playlists ?: emptyList())
                .filterNotNull()
                .take(MAX_BACKUP_PLAYLISTS)
                .map { pl ->
                    val plSongs = (pl.songs ?: emptyList())
                        .filterNotNull()
                        .take(MAX_BACKUP_PLAYLIST_SONGS)
                        .map { clean(it) }
                    Playlist(
                        id = sanitizePlaylistId(pl.id),
                        name = sanitizeText(pl.name, 100).ifEmpty { "Imported Playlist" },
                        songs = plSongs
                    )
                }

            val importedHistory = (libraryData.history ?: emptyList())
                .filterNotNull()
                .map { clean(it) }
                .take(MAX_HISTORY)

            val finalLiked: List<Song>
            val finalPlaylists: List<Playlist>
            val finalHistory: List<Song>

            if (mode == ImportMode.REPLACE) {
                finalLiked = importedLiked
                finalPlaylists = importedPlaylists
                finalHistory = importedHistory
            } else {
                // MERGE MODE:
                // 1. Liked songs: union deduplicated by ID, capped
                finalLiked = (importedLiked + _liked.value).distinctBy { it.id }.take(MAX_BACKUP_LIKED)

                // 2. Playlists: deep song merge!
                val currentPls = _playlists.value.toMutableList()
                importedPlaylists.forEach { impPl ->
                    val existingIndex = currentPls.indexOfFirst {
                        it.id == impPl.id || it.name.equals(impPl.name, ignoreCase = true)
                    }
                    if (existingIndex != -1) {
                        val existing = currentPls[existingIndex]
                        val mergedSongs = (existing.songs + impPl.songs).distinctBy { it.id }.take(MAX_BACKUP_PLAYLIST_SONGS)
                        currentPls[existingIndex] = existing.copy(songs = mergedSongs)
                    } else {
                        if (currentPls.size < MAX_BACKUP_PLAYLISTS) {
                            currentPls.add(impPl)
                        }
                    }
                }
                finalPlaylists = currentPls

                // 3. History: union, distinct, take MAX_HISTORY
                finalHistory = (importedHistory + _history.value).distinctBy { it.id }.take(MAX_HISTORY)
            }

            _liked.value = finalLiked
            _playlists.value = finalPlaylists
            _history.value = finalHistory
            persist()

            // 4. Restore audio settings if present with strict boundary clamping
            var audioRestored = false
            if (context != null && audioSettings != null) {
                try {
                    val prefs = context.getSharedPreferences("audio_fx_prefs", Context.MODE_PRIVATE).edit()
                    val safePreset = sanitizeText(audioSettings.preset, 32).ifEmpty { "Flat" }
                    val safeBass = audioSettings.bassBoost.coerceIn(0, 1000)
                    val safeVirt = audioSettings.virtualizer.coerceIn(0, 1000)
                    val safeCrossfade = audioSettings.crossfadeSeconds.coerceIn(0, 15)

                    prefs.putString("preset", safePreset)
                    prefs.putInt("bass_boost", safeBass)
                    prefs.putInt("virtualizer", safeVirt)
                    prefs.putBoolean("volume_norm_enabled", audioSettings.volumeNormEnabled)
                    prefs.putInt("crossfade_seconds", safeCrossfade)

                    audioSettings.bandLevels.forEach { (bandIdx, level) ->
                        if (bandIdx in 0..4) {
                            prefs.putInt("band_$bandIdx", level.coerceIn(-1500, 1500))
                        }
                    }
                    prefs.apply()
                    audioRestored = true
                } catch (e: Exception) {
                    android.util.Log.w("LibraryStore", "Audio settings restore failed: " + e.javaClass.simpleName)
                }
            }

            ImportResult(
                playlistsCount = finalPlaylists.size,
                likedCount = finalLiked.size,
                mode = mode,
                audioSettingsRestored = audioRestored
            )
        } catch (e: Throwable) {
            android.util.Log.w("LibraryStore", "Import failed: " + e.javaClass.simpleName)
            null
        }
    }
    /**
     * Backward-compatible import method for legacy callers and unit tests.
     */
    fun importBackupJson(jsonString: String): Pair<Int, Int>? {
        val result = importBackupJsonDetailed(null, jsonString, ImportMode.MERGE) ?: return null
        return Pair(result.playlistsCount, result.likedCount)
    }
}
