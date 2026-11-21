package com.ytmusic.clone.data.model

import java.io.Serializable

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long = 0,
    val thumbnailUrl: String = "",
    val streamUrl: String = "",
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
    val format: String = "Opus (160kbps)",
    val album: String = "",
    val loudnessDb: Float? = null
) : Serializable {
    val durationFormatted: String
        get() {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}

object ThumbnailUtils {
    /**
     * Upgrades any YouTube thumbnail URL to ultra-high-definition master artwork:
     * - googleusercontent.com / ggpht.com: =w1200-h1200-l90-rj (1200x1200 lossless master)
     * - i.ytimg.com: maxresdefault.jpg (1280x720 Full HD without query signatures)
     */
    fun upgrade(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        var url = rawUrl.trim()
        if (url.startsWith("//")) {
            url = "https:$url"
        }
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            val base = url.substringBefore("=").substringBefore("?")
            return "$base=w1200-h1200-l90-rj"
        }
        if (url.contains("i.ytimg.com")) {
            val videoId = Regex("""vi/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
            if (videoId != null) {
                return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            }
            return url.substringBefore("?").replace(Regex("""(hqdefault|sddefault|default|mqdefault)\.jpg"""), "maxresdefault.jpg")
        }
        return url
    }
}
