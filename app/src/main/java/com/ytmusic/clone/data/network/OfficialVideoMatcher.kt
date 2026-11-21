package com.ytmusic.clone.data.network

/**
 * Pure (Android-free, network-free) rules for deciding whether a search result is the OFFICIAL video
 * of a song. Kept separate from YouTubeExtractor so every rule can be unit-tested.
 *
 * Design goals:
 *  - Never accept covers, lyric videos, remixes, live clips, female/male versions, or fan uploads.
 *  - Never reject a real official video only because its title is long or decorated, e.g.
 *    song "Kesariya - From \"Brahmastra\"" vs video "Kesariya (Full Video) | Ranbir | Arijit Singh".
 */
object OfficialVideoMatcher {

    const val MATCHER_VERSION = 8
    const val TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"

    class Candidate(
        val id: String,
        val title: String,
        val artist: String,
        val durationSec: Long,
        /** YouTube Music's own video type, or null when the result did not carry one. */
        val type: String?,
        val verified: Boolean = false,
        val artistChannel: Boolean = false
    )

    /** Only MUSIC_VIDEO_TYPE_OMV counts as a true official music video (OFFICIAL_SOURCE is often static art). */
    fun isOfficialVideoType(t: String?): Boolean = t == TYPE_OMV

    fun isOmv(t: String?): Boolean = t == TYPE_OMV

    // ---------------------------------------------------------------- titles

    private val noiseWords = setOf(
        "official", "video", "videos", "music", "mv", "hd", "4k", "hq", "audio", "lyrics", "lyric", "lyrical",
        "full", "song", "songs", "ft", "feat", "featuring", "with", "the", "a", "an", "and", "version",
        "remastered", "remaster", "new", "latest", "hindi", "original", "from"
    )

    /** Words that mean "a different recording / part" (includes "version" for conflict detection). */
    private val variantTokens = setOf(
        "part", "pt", "reprise", "remix", "mix", "cover", "live", "acoustic", "unplugged", "instrumental",
        "slowed", "reverb", "sped", "lofi", "karaoke", "mashup", "nightcore", "ii", "iii", "version",
        "female", "male", "duet", "session", "sessions", "studio", "rendition", "tribute", "recreated", "remake"
    )

    private val knownLabelChannels = setOf(
        "t-series", "tseries", "sony music", "zee music", "tips official", "yrf", "saregama",
        "speed records", "eros now", "shemaroo", "venus", "universal music", "warner music",
        "atlantic records", "republic records", "columbia records", "vevo"
    )

    fun isKnownLabel(channel: String): Boolean {
        val c = channel.lowercase()
        return knownLabelChannels.any { c.contains(it) }
    }

    // "Kesariya - From "Brahmastra"", "Song | From the film X", "Song - Film Version"
    private val fromSuffix = Regex("""(?i)\s*[-–—|]\s*(from|film|movie)\b.*$""")
    private val fromParen = Regex("""(?i)\s*[(\[]\s*(?:from|film|movie|original motion picture|ost|soundtrack)\b([^)\]]*)[)\]]""")
    private val editSuffix = Regex(
        """(?i)\s*[-–—]\s*(\d{4}\s+)?(re-?master(ed)?(\s+\d{4})?|single version|album version|radio edit|bonus track|deluxe.*)\s*$"""
    )

    /** Removes "- From "Movie"", "(From "Bhediya")", and "- Remastered 2011" tails from song titles. */
    fun cleanSongTitle(raw: String): String {
        val cleaned = raw
            .replace(fromParen, "")
            .replace(fromSuffix, "")
            .replace(editSuffix, "")
            .trim()
        return if (cleaned.isEmpty()) raw else cleaned
    }

    /** Extracts movie/film name from "(From "Bhediya")" or "- From "Brahmastra"" if present. */
    fun extractMovieHint(raw: String): String? {
        val parenMatch = fromParen.find(raw)?.groupValues?.getOrNull(1)
        val rawHint = parenMatch ?: fromSuffix.find(raw)?.value
            ?.replace(Regex("""(?i)^.*?\b(from|film|movie)\b"""), "")
        val cleaned = rawHint
            ?.replace(Regex("""["'“”‘’()\[\]]"""), " ")
            ?.replace(Regex("""(?i)\b(the\s+film|motion\s+picture|soundtrack|original)\b"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
        return cleaned?.takeIf { it.length >= 2 }
    }

    private fun norm(raw: String): String =
        raw.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun allWords(raw: String): Set<String> =
        norm(raw).split(" ").filter { it.isNotEmpty() }.toSet()

    fun coreTokens(raw: String): Set<String> {
        val noParens = raw.lowercase().replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
        val words = norm(noParens).split(" ").filter { it.isNotEmpty() }
        val core = words.filter { it !in noiseWords }.toSet()
        // A song literally called "New" or "Original" must still be matchable.
        return if (core.isEmpty()) words.toSet() else core
    }

    /** True when one side is a variant (remix/live/part/cover/version...) and the other is not. */
    fun hasVariantConflict(songTitle: String, candTitle: String): Boolean {
        val sw = allWords(songTitle)
        val cw = allWords(candTitle)
        if (cw.any { it in variantTokens && it !in sw }) return true
        if (sw.any { it in variantTokens && it !in cw }) return true
        return false
    }

    /**
     * strict  = both directions must overlap.
     * lenient = for candidates that are already trusted as official: the SONG's words must all be in
     *           the candidate (long decorated video titles are normal), and it must not be a variant.
     */
    fun titlesMatch(songTitle: String, candTitle: String, lenient: Boolean = false): Boolean {
        val cleanedSong = cleanSongTitle(songTitle)
        val a = coreTokens(cleanedSong)
        val b = coreTokens(candTitle)
        if (a.isEmpty() || b.isEmpty()) return false
        val common = a.intersect(b).size.toDouble()
        val forward = common / a.size
        if (!lenient) return forward >= 0.6 && common / b.size >= 0.5
        if (hasVariantConflict(cleanedSong, candTitle)) return false
        if (forward < (if (a.size >= 4) 0.7 else 0.99)) return false
        // "Song" must not match "Song 2".
        val extras = b - a
        val songHasNumber = a.any { it.all(Char::isDigit) }
        if (!songHasNumber && b.size <= a.size + 1 && extras.any { it.length <= 2 && it.all(Char::isDigit) }) return false
        return true
    }

    // ---------------------------------------------------------------- artists

    fun artistNames(raw: String): List<String> =
        raw.lowercase()
            .split(",", "&", " x ", " feat.", " feat ", " ft.", " ft ", " featuring ", " and ", " with ")
            .map { norm(it) }
            .filter { it.length >= 2 }

    /**
     * @param channelOnly when true the video TITLE is not searched for the artist name. Used for results
     *        that carry no official type or regular YouTube picks, where a fan upload called "Artist - Song" must not pass.
     */
    fun artistMatches(songArtist: String, candArtistOrChannel: String, candTitle: String, channelOnly: Boolean = false): Boolean {
        val cleaned = songArtist.replace(Regex("(?i)\\s*-\\s*topic$"), "").replace(Regex("(?i)\\s*vevo$"), "")
        val names = artistNames(cleaned)
        if (names.isEmpty() || songArtist.equals("Unknown Artist", true)) return false
        val hay = norm(if (channelOnly) candArtistOrChannel else "$candArtistOrChannel $candTitle")
        val hayCompact = hay.replace(" ", "")
        if (names.any { n -> hay.contains(n) || hayCompact.contains(n.replace(" ", "")) }) return true
        // Reverse direction: the video's artist list names one of the song's artists.
        val candCleaned = candArtistOrChannel.replace(Regex("(?i)\\s*-\\s*topic$"), "").replace(Regex("(?i)\\s*vevo$"), "")
        val songHay = norm(songArtist)
        return artistNames(candCleaned).any { it.length >= 3 && songHay.contains(it) }
    }

    // ---------------------------------------------------------------- duration

    /**
     * Official videos are often slightly longer than album audio (intros, skits) and rarely shorter.
     * Window for official: -8% .. +25% (min 12s shorter, max 60s longer).
     * When songSec <= 0 (unknown), callers must enforce stricter type/artist/title checks rather than skipping.
     */
    fun durationClose(songSec: Long, candSec: Long, official: Boolean = false, tight: Boolean = false): Boolean {
        if (songSec <= 0L || candSec <= 0L) return false
        val diff = candSec - songSec
        if (tight) return kotlin.math.abs(diff) <= maxOf(10L, songSec * 6 / 100)
        if (!official) return kotlin.math.abs(diff) <= maxOf(15L, songSec * 10 / 100)
        val shorter = maxOf(12L, songSec * 8 / 100)
        val longer = minOf(60L, maxOf(25L, songSec * 25 / 100))
        return diff in -shorter..longer
    }

    // ---------------------------------------------------------------- reject words

    private val videoRejectWords = setOf(
        "lyric", "lyrics", "lyrical", "audio", "cover", "reaction", "slowed", "reverb", "8d", "remix", "mashup",
        "lofi", "karaoke", "instrumental", "live", "acoustic", "concert", "nightcore", "teaser", "trailer",
        "snippet", "visualizer", "visualiser", "unplugged",
        "female", "male", "duet", "session", "sessions", "studio", "rendition", "tribute", "recreated",
        "remake", "piano", "guitar", "flute", "violin", "tutorial", "shorts", "short", "edit",
        "bass", "boosted", "choreography", "dance", "sing", "sung", "singing", "tiktok"
    )
    private val videoRejectPhrases = listOf(
        "sped up", "lo-fi", "behind the scenes", "making of", "dance practice", "full album",
        "female version", "male version", "studio session", "cover by", "sung by", "re-created",
        "bass boosted", "english version", "tamil version", "telugu version", "punjabi version",
        "hindi version", "malayalam version", "kannada version", "bengali version", "lofi version"
    )

    /** Whole-word match, so "Alive", "Discover" or "Oliver" are not rejected by "live"/"cover". */
    fun hasRejectedWord(candTitle: String, songTitle: String): Boolean {
        val st = allWords(songTitle)
        val ct = allWords(candTitle)
        if (videoRejectWords.any { it in ct && it !in st }) return true
        val sl = songTitle.lowercase()
        val cl = candTitle.lowercase()
        return videoRejectPhrases.any { cl.contains(it) && !sl.contains(it) }
    }

    // ---------------------------------------------------------------- selection

    private fun betterCandidate(a: Candidate, scoreA: Int, b: Candidate?, scoreB: Int, songSec: Long): Boolean {
        if (b == null) return true
        if (scoreA != scoreB) return scoreA > scoreB
        val typeRankA = if (isOmv(a.type)) 2 else if (a.verified) 1 else 0
        val typeRankB = if (isOmv(b.type)) 2 else if (b.verified) 1 else 0
        if (typeRankA != typeRankB) return typeRankA > typeRankB
        if (songSec > 0 && a.durationSec > 0 && b.durationSec > 0) {
            val dA = kotlin.math.abs(a.durationSec - songSec)
            val dB = kotlin.math.abs(b.durationSec - songSec)
            if (dA != dB) return dA < dB
        }
        return a.id < b.id
    }

    /**
     * Validates a YT Music counterpart candidate (from fetchPanelInfo) with full OMV rules.
     */
    fun isValidCounterpart(
        songId: String,
        songTitle: String,
        songArtist: String,
        songSec: Long,
        c: Candidate,
        rejectLog: ((Candidate, String) -> Unit)? = null
    ): Boolean {
        if (c.id == songId) {
            rejectLog?.invoke(c, "counterpart_same_id")
            return false
        }
        if (!isOmv(c.type)) {
            rejectLog?.invoke(c, "counterpart_not_omv:${c.type}")
            return false
        }
        return scoreTyped(c, songTitle, songArtist, songSec, rejectLog) != null
    }

    /**
     * Picks the best official video among YouTube Music search results, or null.
     *  - Typed official (OMV): must pass reject-words, lenient title match, artist match, and duration check.
     *  - Untyped (type == null): accepted only with a channel-name artist match, tight duration,
     *    a matching title, and no reject words. Explicit non-official types (UGC, ATV, OFFICIAL_SOURCE) are skipped.
     */
    fun pickFromMusicSearch(
        songId: String,
        songTitle: String,
        songArtist: String,
        songDurationSec: Long,
        cands: List<Candidate>,
        rejectLog: ((Candidate, String) -> Unit)? = null
    ): Candidate? {
        var best: Candidate? = null
        var bestScore = Int.MIN_VALUE
        for (c in cands) {
            if (c.id == songId) {
                rejectLog?.invoke(c, "same_id")
                continue
            }
            val typed = isOfficialVideoType(c.type)
            if (!typed && c.type != null) {
                rejectLog?.invoke(c, "non_official_type:${c.type}")
                continue
            }
            val score = if (typed) scoreTyped(c, songTitle, songArtist, songDurationSec, rejectLog)
                        else scoreUntyped(c, songTitle, songArtist, songDurationSec, rejectLog)
            if (score != null && betterCandidate(c, score, best, bestScore, songDurationSec)) {
                best = c
                bestScore = score
            }
        }
        return best
    }

    private fun scoreTyped(
        c: Candidate,
        songTitle: String,
        songArtist: String,
        songSec: Long,
        rejectLog: ((Candidate, String) -> Unit)? = null
    ): Int? {
        // Apply reject words to ALL typed candidates, including OMV (Issue 2)
        if (hasRejectedWord(c.title, songTitle)) {
            rejectLog?.invoke(c, "rejected_word")
            return null
        }
        // Strict title when songSec is unknown (Issue 4)
        if (!titlesMatch(songTitle, c.title, lenient = songSec > 0L)) {
            rejectLog?.invoke(c, "title_mismatch")
            return null
        }
        // Artist gate for OMV: different singer = different recording (Issue 2)
        if (!artistMatches(songArtist, c.artist, c.title)) {
            rejectLog?.invoke(c, "artist_mismatch:${c.artist}")
            return null
        }
        // Duration check (when songSec > 0 and candSec > 0)
        if (songSec > 0L && c.durationSec > 0L && !durationClose(songSec, c.durationSec, official = true)) {
            rejectLog?.invoke(c, "duration_mismatch(song=$songSec,cand=${c.durationSec})")
            return null
        }
        var sc = 100
        if (titlesMatch(songTitle, c.title)) sc += 15
        if (songSec > 0 && c.durationSec > 0) sc -= kotlin.math.abs(c.durationSec - songSec).toInt().coerceAtMost(40)
        return sc
    }

    private fun scoreUntyped(
        c: Candidate,
        songTitle: String,
        songArtist: String,
        songSec: Long,
        rejectLog: ((Candidate, String) -> Unit)? = null
    ): Int? {
        if (songSec <= 0 || c.durationSec <= 0) {
            rejectLog?.invoke(c, "missing_duration_untyped")
            return null
        }
        if (hasRejectedWord(c.title, songTitle)) {
            rejectLog?.invoke(c, "rejected_word_untyped")
            return null
        }
        if (!titlesMatch(songTitle, c.title, lenient = true)) {
            rejectLog?.invoke(c, "title_mismatch_untyped")
            return null
        }
        if (!artistMatches(songArtist, c.artist, c.title, channelOnly = true)) {
            rejectLog?.invoke(c, "artist_channel_mismatch_untyped")
            return null
        }
        if (!durationClose(songSec, c.durationSec, tight = true)) {
            rejectLog?.invoke(c, "duration_mismatch_untyped(song=$songSec,cand=${c.durationSec})")
            return null
        }
        return 30 - kotlin.math.abs(c.durationSec - songSec).toInt().coerceAtMost(25)
    }

    /**
     * Picks the best official video among regular YouTube results (verified channels only).
     * Requires artist match in channel name (or known label channel with tight duration),
     * plus an official/full-video hint or official artistChannel.
     */
    fun pickFromYouTube(
        songId: String,
        songTitle: String,
        songArtist: String,
        songDurationSec: Long,
        cands: List<Candidate>,
        rejectLog: ((Candidate, String) -> Unit)? = null
    ): Candidate? {
        var best: Candidate? = null
        var bestScore = Int.MIN_VALUE
        for (c in cands) {
            if (c.id == songId) {
                rejectLog?.invoke(c, "same_id")
                continue
            }
            if (!c.verified) {
                rejectLog?.invoke(c, "unverified_channel:${c.artist}")
                continue
            }
            val t = c.title.lowercase()
            val hint = t.contains("official") || t.contains("full video") || t.contains("video song") ||
                t.contains("music video") || t.contains("(video)") || t.contains("[video]")
            val trustedArtistChannel = (c.artistChannel || c.artist.endsWith("vevo", true)) &&
                artistMatches(songArtist, c.artist, c.title, channelOnly = true)
            val labelChannel = isKnownLabel(c.artist)
            val nearlySame = songDurationSec > 0 && c.durationSec > 0 && durationClose(songDurationSec, c.durationSec, tight = true)

            if (!(hint || trustedArtistChannel || (labelChannel && nearlySame))) {
                rejectLog?.invoke(c, "no_hint_or_trusted_channel")
                continue
            }
            if (hasRejectedWord(c.title, songTitle)) {
                rejectLog?.invoke(c, "rejected_word")
                continue
            }
            if (!titlesMatch(songTitle, c.title, lenient = true)) {
                rejectLog?.invoke(c, "title_mismatch")
                continue
            }
            val channelArtistOk = artistMatches(songArtist, c.artist, c.title, channelOnly = true)
            if (!channelArtistOk && !(labelChannel && nearlySame)) {
                rejectLog?.invoke(c, "artist_channel_mismatch:${c.artist}")
                continue
            }
            if (songDurationSec <= 0L || c.durationSec <= 0L || !durationClose(songDurationSec, c.durationSec, official = true)) {
                rejectLog?.invoke(c, "duration_mismatch(song=$songDurationSec,cand=${c.durationSec})")
                continue
            }
            var sc = 40
            if (t.contains("official music video") || t.contains("official video")) sc += 20
            if (trustedArtistChannel) sc += 15
            if (hint) sc += 5
            sc -= kotlin.math.abs(c.durationSec - songDurationSec).toInt().coerceAtMost(40)
            if (betterCandidate(c, sc, best, bestScore, songDurationSec)) {
                best = c
                bestScore = sc
            }
        }
        return best
    }
}
