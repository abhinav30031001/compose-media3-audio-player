package com.ytmusic.clone.data.network

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory local diagnostics logger (max 50 entries).
 * Absolutely NEVER logs full URLs, tokens, visitorData, or cookies.
 */
data class DiagnosticEntry(
    val timestampMs: Long,
    val tierId: String,
    val status: String,     // SUCCESS, FAILURE, BLOCKED, SKIPPED
    val probeResult: String  // Latency, status code, format, sanitized error
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}

object DiagnosticLogger {
    private const val MAX_ENTRIES = 50
    private val entries = ConcurrentLinkedDeque<DiagnosticEntry>()

    fun log(tierId: String, status: String, probeResult: String) {
        val sanitized = DiagnosticSanitizer.sanitize(probeResult)
        entries.addLast(DiagnosticEntry(System.currentTimeMillis(), tierId, status, sanitized))
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }
    }

    fun getRecentEntries(): List<DiagnosticEntry> = entries.toList().reversed()

    fun clear() {
        entries.clear()
    }

    /**
     * Strips any URLs, tokens, visitorData, auth, or cookies from strings.
     */
    private fun sanitize(input: String): String {
        return input
            .replace(Regex("https?://[^\\s\"',]+"), "[URL_REDACTED]")
            .replace(Regex("(?i)visitorData=?[^&\\s\"',]+"), "visitorData=[REDACTED]")
            .replace(Regex("(?i)X-Goog-Visitor-Id:?\\s*[^&\\s\"',]+"), "X-Goog-Visitor-Id=[REDACTED]")
            .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+"), "bearer [REDACTED]")
    }
}
