package com.ytmusic.clone.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ytmusic.clone.data.model.LyricLine

/**
 * Pure JSON helpers for YouTube Music's lyrics tab (v7.20). No Android or network code, so it is
 * unit-testable on the JVM.
 *
 * Flow: "next" response -> lyrics browseId (starts with "MPLYt") -> "browse" response ->
 * musicDescriptionShelfRenderer.description.runs (the lyrics text).
 */
object LyricsParser {

    /** Finds the lyrics browseId anywhere in a "next" response, or null when the song has none. */
    fun findLyricsBrowseId(next: JsonElement?): String? {
        var found: String? = null
        walk(next) { obj ->
            if (found != null) return@walk
            val id = obj.get("browseEndpoint")
                ?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("browseId")?.takeIf { it.isJsonPrimitive }?.asString
            if (id != null && id.startsWith("MPLYt")) found = id
        }
        return found
    }

    /** Returns Pair(lyricsText, sourceLine?) from a "browse" response, or null when no lyrics text. */
    fun extractLyrics(browse: JsonElement?): Pair<String, String?>? {
        var result: Pair<String, String?>? = null
        walk(browse) { obj ->
            if (result != null) return@walk
            val shelf = obj.get("musicDescriptionShelfRenderer")
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return@walk
            val text = runsToText(shelf.get("description")).trim()
            if (text.isEmpty()) return@walk
            val source = runsToText(shelf.get("footer")).trim().ifEmpty { null }
            result = text to source
        }
        return result
    }

    private fun runsToText(el: JsonElement?): String {
        val runs: JsonArray = el?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("runs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        val sb = StringBuilder()
        for (run in runs) {
            val t = run.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            if (t != null) sb.append(t)
        }
        return sb.toString()
    }

    private fun walk(el: JsonElement?, visit: (JsonObject) -> Unit) {
        when {
            el == null -> return
            el.isJsonObject -> {
                val obj = el.asJsonObject
                visit(obj)
                for ((_, v) in obj.entrySet()) walk(v, visit)
            }
            el.isJsonArray -> for (v in el.asJsonArray) walk(v, visit)
            else -> return
        }
    }
}

object LyricsUtils {
    private val LRC_REGEX = Regex("""^\[(\d{1,2}):(\d{2})(?:[\.:](\d{1,3}))?\](.*)$""")

    fun parse(text: String, durationMs: Long = 180_000L): List<LyricLine> {
        val rawLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return emptyList()

        val parsedLrc = mutableListOf<LyricLine>()
        var hasLrc = false

        for (line in rawLines) {
            val match = LRC_REGEX.find(line)
            if (match != null) {
                hasLrc = true
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val millisPart = match.groupValues[3]
                val millis = when (millisPart.length) {
                    1 -> millisPart.toLong() * 100
                    2 -> millisPart.toLong() * 10
                    3 -> millisPart.toLong()
                    else -> 0L
                }
                val totalMs = minutes * 60_000L + seconds * 1000L + millis
                val lyricContent = match.groupValues[4].trim()
                if (lyricContent.isNotEmpty()) {
                    val isHeader = lyricContent.startsWith("[") && lyricContent.endsWith("]")
                    parsedLrc.add(LyricLine(timeMs = totalMs, text = lyricContent, isHeader = isHeader, isSynced = true))
                }
            }
        }

        if (hasLrc && parsedLrc.isNotEmpty()) {
            return parsedLrc.sortedBy { it.timeMs }
        }

        val cleanLines = rawLines.filter {
            !it.startsWith("[Source") && !it.startsWith("Source:") &&
            !it.startsWith("[Written by") && !it.startsWith("Written by:") &&
            !it.startsWith("[Produced by") && !it.startsWith("Produced by:")
        }
        if (cleanLines.isEmpty()) return emptyList()

        val effectiveDuration = if (durationMs > 30_000L) durationMs else 180_000L
        val introMs = (effectiveDuration * 0.08f).toLong().coerceIn(5_000L, 16_000L)
        val outroMs = (effectiveDuration * 0.08f).toLong().coerceIn(5_000L, 20_000L)
        val singingDuration = (effectiveDuration - introMs - outroMs).coerceAtLeast(10_000L)

        val weights = cleanLines.map { line ->
            if (line.startsWith("[") && line.endsWith("]")) 4 else line.length.coerceIn(10, 80)
        }
        val totalWeight = weights.sum().coerceAtLeast(1)

        var accumulatedTime = introMs
        return cleanLines.mapIndexed { index, lineStr ->
            val isHeader = lineStr.startsWith("[") && lineStr.endsWith("]")
            val lineDuration = (singingDuration * weights[index]) / totalWeight
            val lineTime = accumulatedTime
            accumulatedTime += lineDuration
            LyricLine(timeMs = lineTime, text = lineStr, isHeader = isHeader, isSynced = false)
        }
    }
}
