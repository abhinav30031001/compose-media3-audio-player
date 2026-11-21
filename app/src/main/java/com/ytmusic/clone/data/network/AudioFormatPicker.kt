package com.ytmusic.clone.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Shared audio format selection for every progressive tier.
 *
 * Goals (match what the official YouTube Music app plays):
 *  - never pick a DRC (dynamic-range-compressed / "stable volume" baked-in) variant: it is louder
 *    and flatter than the studio master the official app plays;
 *  - never pick a dubbed / alternate-language audio track when the original is available;
 *  - rank by codec preference, then AVERAGE bitrate (peak bitrate is misleading for VBR Opus).
 */
object AudioFormatPicker {

    /** Set after a decoder failure so the next resolve prefers AAC over Opus. */
    @Volatile
    var preferAac: Boolean = false

    fun longOf(f: JsonObject, key: String): Long {
        val el: JsonElement = f.get(key) ?: return 0L
        if (!el.isJsonPrimitive) return 0L
        return runCatching { el.asLong }.getOrNull() ?: el.asString.toLongOrNull() ?: 0L
    }

    private fun mimeOf(f: JsonObject): String? =
        f.get("mimeType")?.takeIf { it.isJsonPrimitive }?.asString

    private fun urlOf(f: JsonObject): String? =
        f.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** True when the format is the plain, original, uncompressed audio. */
    fun isPreferredVariant(f: JsonObject): Boolean {
        val isDrc = f.get("isDrc")?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() == true
        if (isDrc) return false
        val xtags = f.get("xtags")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (xtags.contains("drc=1")) return false
        val track = f.get("audioTrack")?.takeIf { it.isJsonObject }?.asJsonObject
        if (track != null) {
            val isDefault = track.get("audioIsDefault")?.takeIf { it.isJsonPrimitive }
                ?.runCatching { asBoolean }?.getOrNull()
            if (isDefault == false) return false
        }
        return true
    }

    fun score(f: JsonObject, preferAacNow: Boolean = preferAac): Long {
        val mime = mimeOf(f).orEmpty()
        val isOpus = mime.contains("opus")
        val codecBonus = if (isOpus != preferAacNow) 1_000_000_000L else 0L
        val avg = longOf(f, "averageBitrate")
        val rate = if (avg > 0L) avg else longOf(f, "bitrate")
        return codecBonus + rate
    }

    /**
     * Picks the best audio format object. Falls back to any audio format if no preferred
     * (non-DRC, default-track) variant exists, so a track is never reported unplayable
     * only because of this filter.
     */
    fun pick(
        formats: JsonArray,
        preferAacNow: Boolean = preferAac,
        urlAllowed: (String) -> Boolean = { true }
    ): JsonObject? {
        val audio = formats.mapNotNull { el ->
            val f = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val mime = mimeOf(f) ?: return@mapNotNull null
            if (!mime.startsWith("audio/")) return@mapNotNull null
            val url = urlOf(f) ?: return@mapNotNull null
            if (!urlAllowed(url)) return@mapNotNull null
            f
        }
        val preferred = audio.filter { isPreferredVariant(it) }
        val pool = if (preferred.isNotEmpty()) preferred else audio
        return pool.maxByOrNull { score(it, preferAacNow) }
    }
}
