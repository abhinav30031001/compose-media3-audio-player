package com.ytmusic.clone.data.model

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val isHeader: Boolean = false,
    val isSynced: Boolean = false
)

/** Result of asking YouTube Music for a song's lyrics (supports synchronized lines). */
sealed interface LyricsResult {
    data class Found(
        val text: String,
        val source: String?,
        val lines: List<LyricLine> = emptyList(),
        val isSynced: Boolean = false
    ) : LyricsResult
    /** YouTube has no lyrics for this track. */
    object NotAvailable : LyricsResult
    /** Network / parsing problem; safe to retry. */
    data class Error(val message: String) : LyricsResult
}
