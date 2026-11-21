package com.ytmusic.clone

import com.ytmusic.clone.data.network.OfficialVideoMatcher
import com.ytmusic.clone.data.network.OfficialVideoMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialVideoMatcherTest {

    private val OMV = "MUSIC_VIDEO_TYPE_OMV"
    private val OSM = "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC"
    private val UGC = "MUSIC_VIDEO_TYPE_UGC"

    private fun cand(id: String, title: String, artist: String, dur: Long, type: String? = OMV, verified: Boolean = false, artistChannel: Boolean = false) =
        Candidate(id, title, artist, dur, type, verified, artistChannel)

    // ---- title cleaning -------------------------------------------------

    @Test fun cleanTitleRemovesFromSuffix() {
        assertEquals("Kesariya", OfficialVideoMatcher.cleanSongTitle("Kesariya - From \"Brahmastra\""))
        assertEquals("Tum Hi Ho", OfficialVideoMatcher.cleanSongTitle("Tum Hi Ho - From \"Aashiqui 2\""))
    }

    @Test fun cleanTitleRemovesRemasterSuffix() {
        assertEquals("Bohemian Rhapsody", OfficialVideoMatcher.cleanSongTitle("Bohemian Rhapsody - Remastered 2011"))
    }

    @Test fun cleanTitleKeepsPlainTitle() {
        assertEquals("Blinding Lights", OfficialVideoMatcher.cleanSongTitle("Blinding Lights"))
    }

    // ---- title matching (the reported "official one rejected" bug) ------

    @Test fun movieSuffixSongMatchesDecoratedOfficialTitle() {
        assertTrue(OfficialVideoMatcher.titlesMatch(
            "Kesariya - From \"Brahmastra\"", "Kesariya (Full Video) | Brahmastra | Ranbir | Arijit Singh", lenient = true))
    }

    @Test fun plainSongMatchesOfficialMusicVideoTitle() {
        assertTrue(OfficialVideoMatcher.titlesMatch("Blinding Lights", "The Weeknd - Blinding Lights (Official Video)", lenient = true))
    }

    @Test fun partTwoDoesNotMatchOriginal() {
        assertFalse(OfficialVideoMatcher.titlesMatch("Kesariya", "Kesariya Part 2 (Official Video)", lenient = true))
    }

    @Test fun remixDoesNotMatchOriginalEvenWhenParenthesised() {
        assertFalse(OfficialVideoMatcher.titlesMatch("Closer", "Closer (Remix) (Official Video)", lenient = true))
    }

    @Test fun remixSongDoesNotMatchOriginalVideo() {
        assertFalse(OfficialVideoMatcher.titlesMatch("Closer (Remix)", "Closer (Official Video)", lenient = true))
    }

    @Test fun differentSongDoesNotMatch() {
        assertFalse(OfficialVideoMatcher.titlesMatch("Kesariya", "Apna Bana Le (Official Video)", lenient = true))
    }

    @Test fun songNamedWithNoiseWordStillMatches() {
        assertTrue(OfficialVideoMatcher.titlesMatch("New", "New (Official Video)", lenient = true))
    }

    @Test fun numberedSequelDoesNotMatch() {
        assertFalse(OfficialVideoMatcher.titlesMatch("Waka Waka", "Waka Waka 2 (Official Video)", lenient = true))
    }

    // ---- reject words ---------------------------------------------------

    @Test fun aliveAndDiscoverAreNotRejected() {
        assertFalse(OfficialVideoMatcher.hasRejectedWord("Alive (Official Video)", "Alive"))
        assertFalse(OfficialVideoMatcher.hasRejectedWord("Discover (Official Video)", "Discover"))
    }

    @Test fun lyricVideoIsRejected() {
        assertTrue(OfficialVideoMatcher.hasRejectedWord("Kesariya (Lyrical Video)", "Kesariya"))
    }

    @Test fun rejectWordInsideSongTitleIsAllowed() {
        assertFalse(OfficialVideoMatcher.hasRejectedWord("Live Forever (Official Video)", "Live Forever"))
    }

    // ---- artist ---------------------------------------------------------

    @Test fun artistFoundInTitle() {
        assertTrue(OfficialVideoMatcher.artistMatches("The Weeknd", "Some Channel", "The Weeknd - Blinding Lights"))
    }

    @Test fun artistListMatchesAnyName() {
        assertTrue(OfficialVideoMatcher.artistMatches("Arijit Singh, Shreya Ghoshal", "Arijit Singh", "x"))
    }

    @Test fun artistListInChannelName() {
        assertTrue(OfficialVideoMatcher.artistMatches("Arijit Singh", "Arijit Singh, Pritam", "x", channelOnly = true))
    }

    @Test fun channelOnlyIgnoresTitle() {
        assertFalse(OfficialVideoMatcher.artistMatches("Arijit Singh", "Random Fan", "Arijit Singh - Kesariya", channelOnly = true))
    }

    @Test fun topicAndVevoSuffixIgnored() {
        assertTrue(OfficialVideoMatcher.artistMatches("Adele - Topic", "AdeleVEVO", "Hello"))
    }

    // ---- duration -------------------------------------------------------

    @Test fun officialVideoMayBeMuchLonger() {
        assertTrue(OfficialVideoMatcher.durationClose(240, 330, official = true))
    }

    @Test fun officialVideoMayNotBeMuchShorter() {
        assertFalse(OfficialVideoMatcher.durationClose(240, 150, official = true))
    }

    @Test fun unknownDurationPasses() {
        assertTrue(OfficialVideoMatcher.durationClose(0, 200, official = true))
    }

    // ---- selection: YouTube Music search --------------------------------

    @Test fun picksOmvWithDecoratedTitle() {
        val got = OfficialVideoMatcher.pickFromMusicSearch(
            "songId", "Kesariya - From \"Brahmastra\"", "Arijit Singh", 268,
            listOf(cand("v1", "Kesariya (Full Video) | Brahmastra | Ranbir | Alia", "Sony Music India", 280, OMV))
        )
        assertEquals("v1", got?.id)
    }

    @Test fun neverReturnsTheSongItself() {
        assertNull(OfficialVideoMatcher.pickFromMusicSearch("a", "Song", "Artist", 200, listOf(cand("a", "Song", "Artist", 200))))
    }

    @Test fun rejectsUgcAndAtvTypes() {
        val got = OfficialVideoMatcher.pickFromMusicSearch(
            "s", "Song", "Artist", 200,
            listOf(cand("u", "Song", "Artist", 200, UGC), cand("a", "Song", "Artist", 200, "MUSIC_VIDEO_TYPE_ATV"))
        )
        assertNull(got)
    }

    @Test fun rejectsCoverEvenIfTypedOsm() {
        val got = OfficialVideoMatcher.pickFromMusicSearch(
            "s", "Song", "Artist", 200, listOf(cand("c", "Song (Cover)", "Artist", 200, OSM)))
        assertNull(got)
    }

    @Test fun prefersOmvOverOsm() {
        val got = OfficialVideoMatcher.pickFromMusicSearch(
            "s", "Song", "Artist", 200,
            listOf(cand("osm", "Song", "Artist", 200, OSM), cand("omv", "Song (Official Video)", "Artist", 205, OMV))
        )
        assertEquals("omv", got?.id)
    }

    @Test fun rejectsRemixOmv() {
        val got = OfficialVideoMatcher.pickFromMusicSearch(
            "s", "Closer", "The Chainsmokers", 245,
            listOf(cand("r", "Closer (Remix)", "The Chainsmokers", 240, OMV)))
        assertNull(got)
    }

    @Test fun labelChannelAcceptedOnlyWithTightDuration() {
        val ok = OfficialVideoMatcher.pickFromMusicSearch(
            "s", "Song", "Singer", 200, listOf(cand("l", "Song", "T-Series", 203, OSM)))
        val bad = OfficialVideoMatcher.pickFromMusicSearch(
            "s", "Song", "Singer", 200, listOf(cand("l", "Song", "T-Series", 290, OSM)))
        assertEquals("l", ok?.id)
        assertNull(bad)
    }

    @Test fun untypedResultAcceptedWithChannelDurationAndTitle() {
        val got = OfficialVideoMatcher.pickFromMusicSearch(
            "s", "Blinding Lights", "The Weeknd", 200,
            listOf(cand("n", "Blinding Lights (Official Video)", "The Weeknd", 202, null)))
        assertEquals("n", got?.id)
    }

    @Test fun untypedFanUploadRejected() {
        val got = OfficialVideoMatcher.pickFromMusicSearch(
            "s", "Blinding Lights", "The Weeknd", 200,
            listOf(cand("n", "The Weeknd - Blinding Lights", "RandomFan99", 200, null)))
        assertNull(got)
    }

    // ---- selection: regular YouTube -------------------------------------

    @Test fun youtubeArtistChannelNeedsNoOfficialWord() {
        val got = OfficialVideoMatcher.pickFromYouTube(
            "s", "Blinding Lights", "The Weeknd", 200,
            listOf(cand("y", "Blinding Lights", "The Weeknd", 262, null, verified = true, artistChannel = true)))
        assertEquals("y", got?.id)
    }

    @Test fun youtubeUnverifiedChannelRejected() {
        val got = OfficialVideoMatcher.pickFromYouTube(
            "s", "Blinding Lights", "The Weeknd", 200,
            listOf(cand("y", "Blinding Lights (Official Video)", "The Weeknd", 200, null, verified = false)))
        assertNull(got)
    }

    @Test fun youtubeVerifiedLabelWithPlainTitleAndSameDurationAccepted() {
        val got = OfficialVideoMatcher.pickFromYouTube(
            "s", "Tum Hi Ho - From \"Aashiqui 2\"", "Arijit Singh", 262,
            listOf(cand("y", "Tum Hi Ho", "T-Series", 264, null, verified = true)))
        assertEquals("y", got?.id)
    }

    @Test fun youtubeVerifiedButLyricRejected() {
        val got = OfficialVideoMatcher.pickFromYouTube(
            "s", "Blinding Lights", "The Weeknd", 200,
            listOf(cand("y", "Blinding Lights (Official Lyric Video)", "The Weeknd", 200, null, verified = true, artistChannel = true)))
        assertNull(got)
    }

    @Test fun youtubeVerifiedChannelWrongArtistRejected() {
        val got = OfficialVideoMatcher.pickFromYouTube(
            "s", "Hello", "Adele", 295,
            listOf(cand("y", "Hello (Official Video)", "Lionel Richie", 300, null, verified = true, artistChannel = true)))
        assertNull(got)
    }

    @Test fun rejectionReasonsAreLogged() {
        val reasons = mutableListOf<String>()
        val got = OfficialVideoMatcher.pickFromYouTube(
            "s", "Blinding Lights", "The Weeknd", 200,
            listOf(
                cand("u1", "Blinding Lights (Official Video)", "The Weeknd", 200, null, verified = false),
                cand("u2", "Blinding Lights (Official Lyric Video)", "The Weeknd", 200, null, verified = true, artistChannel = true)
            ),
            rejectLog = { c, r -> reasons.add("${c.id}:$r") }
        )
        assertNull(got)
        assertEquals(2, reasons.size)
        assertTrue(reasons[0].startsWith("u1:unverified_channel"))
        assertEquals("u2:rejected_word", reasons[1])
    }

    @Test fun apnaBanaLeCoverRejectedAndOfficialPicked() {
        val cover = cand("cov1", "Apna Bana Le", "Female Singer", 219, OMV)
        val official = cand("off1", "Apna Bana Le (Full Video) | Bhediya | Varun, Kriti | Arijit Singh", "Arijit Singh", 265, OMV)
        assertNull(OfficialVideoMatcher.pickFromMusicSearch("s", "Apna Bana Le (From \"Bhediya\")", "Arijit Singh", 261, listOf(cover)))
        val got = OfficialVideoMatcher.pickFromMusicSearch("s", "Apna Bana Le (From \"Bhediya\")", "Arijit Singh", 261, listOf(cover, official))
        assertEquals("off1", got?.id)
    }

    @Test fun omvCoverFemaleVersionStudioSessionRejected() {
        val cands = listOf(
            cand("c1", "Apna Bana Le (Cover)", "Arijit Singh", 261, OMV),
            cand("c2", "Apna Bana Le | Female Version", "Arijit Singh", 261, OMV),
            cand("c3", "Apna Bana Le (Studio Session)", "Arijit Singh", 261, OMV)
        )
        assertNull(OfficialVideoMatcher.pickFromMusicSearch("s", "Apna Bana Le", "Arijit Singh", 261, cands))
    }

    @Test fun unknownSongDurationRequiresStrictArtistAndTitle() {
        val wrongArtist = cand("w1", "Apna Bana Le (Full Video)", "Random Cover Artist", 219, OMV)
        assertNull(OfficialVideoMatcher.pickFromMusicSearch("s", "Apna Bana Le", "Arijit Singh", 0L, listOf(wrongArtist)))
    }

    @Test fun counterpartValidationRejectsMismatchedCover() {
        val badCp = cand("cp1", "Apna Bana Le (Female Version)", "Someone Else", 219, OMV)
        assertFalse(OfficialVideoMatcher.isValidCounterpart("s", "Apna Bana Le (From \"Bhediya\")", "Arijit Singh", 261, badCp))
    }

    @Test fun mergedSearchPrefersOmvOverUntypedFanUpload() {
        val fan = cand("f1", "Apna Bana Le (Official Video)", "Arijit Singh", 261, null)
        val omv = cand("o1", "Apna Bana Le (Full Video) | Bhediya", "Arijit Singh", 265, OMV)
        val got = OfficialVideoMatcher.pickFromMusicSearch("s", "Apna Bana Le (From \"Bhediya\")", "Arijit Singh", 261, listOf(fan, omv))
        assertEquals("o1", got?.id)
    }
}
