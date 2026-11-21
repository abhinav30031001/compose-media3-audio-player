package com.ytmusic.clone

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ytmusic.clone.data.network.AudioFormatPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioFormatPickerTest {

    private fun fmt(
        mime: String,
        bitrate: Long,
        avg: Long? = null,
        xtags: String? = null,
        isDefaultTrack: Boolean? = null,
        id: String
    ): JsonObject = JsonObject().apply {
        addProperty("mimeType", mime)
        addProperty("url", "https://rr1.googlevideo.com/videoplayback?id=$id")
        addProperty("bitrate", bitrate)
        if (avg != null) addProperty("averageBitrate", avg)
        if (xtags != null) addProperty("xtags", xtags)
        if (isDefaultTrack != null) {
            add("audioTrack", JsonObject().apply { addProperty("audioIsDefault", isDefaultTrack) })
        }
    }

    private fun arr(vararg f: JsonObject) = JsonArray().apply { f.forEach { add(it) } }

    private fun idOf(f: JsonObject?) = f?.get("url")?.asString?.substringAfter("id=")

    @Test
    fun prefersOpusOverAac() {
        val best = AudioFormatPicker.pick(
            arr(
                fmt("audio/mp4; codecs=\"mp4a.40.2\"", 130_000, id = "aac"),
                fmt("audio/webm; codecs=\"opus\"", 140_000, id = "opus")
            ),
            preferAacNow = false
        )
        assertEquals("opus", idOf(best))
    }

    @Test
    fun skipsDrcVariants() {
        val best = AudioFormatPicker.pick(
            arr(
                fmt("audio/webm; codecs=\"opus\"", 160_000, xtags = "drc=1", id = "opus-drc"),
                fmt("audio/webm; codecs=\"opus\"", 140_000, id = "opus")
            ),
            preferAacNow = false
        )
        assertEquals("opus", idOf(best))
    }

    @Test
    fun skipsNonDefaultAudioTracks() {
        val best = AudioFormatPicker.pick(
            arr(
                fmt("audio/webm; codecs=\"opus\"", 160_000, isDefaultTrack = false, id = "dub"),
                fmt("audio/webm; codecs=\"opus\"", 140_000, isDefaultTrack = true, id = "original")
            ),
            preferAacNow = false
        )
        assertEquals("original", idOf(best))
    }

    @Test
    fun fallsBackToAnyAudioWhenOnlyDrcExists() {
        val best = AudioFormatPicker.pick(
            arr(fmt("audio/webm; codecs=\"opus\"", 160_000, xtags = "drc=1", id = "only")),
            preferAacNow = false
        )
        assertEquals("only", idOf(best))
    }

    @Test
    fun rankingUsesAverageBitrateWhenPresent() {
        val best = AudioFormatPicker.pick(
            arr(
                fmt("audio/webm; codecs=\"opus\"", 200_000, avg = 100_000, id = "low-avg"),
                fmt("audio/webm; codecs=\"opus\"", 150_000, avg = 130_000, id = "high-avg")
            ),
            preferAacNow = false
        )
        assertEquals("high-avg", idOf(best))
    }

    @Test
    fun preferAacFlagSwitchesCodec() {
        val best = AudioFormatPicker.pick(
            arr(
                fmt("audio/mp4; codecs=\"mp4a.40.2\"", 128_000, id = "aac"),
                fmt("audio/webm; codecs=\"opus\"", 160_000, id = "opus")
            ),
            preferAacNow = true
        )
        assertEquals("aac", idOf(best))
    }

    @Test
    fun ignoresVideoAndBlockedUrls() {
        val video = JsonObject().apply {
            addProperty("mimeType", "video/mp4")
            addProperty("url", "https://rr1.googlevideo.com/videoplayback?id=video")
        }
        assertNull(AudioFormatPicker.pick(arr(video)))
        val blocked = AudioFormatPicker.pick(
            arr(fmt("audio/webm; codecs=\"opus\"", 140_000, id = "x")),
            urlAllowed = { false }
        )
        assertNull(blocked)
        assertNotNull(AudioFormatPicker.pick(arr(fmt("audio/webm; codecs=\"opus\"", 1, id = "y"))))
    }
}
