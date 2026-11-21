package com.ytmusic.clone

import com.google.gson.JsonParser
import com.ytmusic.clone.data.network.LyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v7.20: parsing of YouTube Music "next" / "browse" responses for the lyrics tab (made-up sample JSON). */
class LyricsParserTest {

    private fun json(s: String) = JsonParser.parseString(s)

    @Test fun findsLyricsBrowseIdAnywhereInNextResponse() {
        val next = json("""
            {"contents":{"tabbedRenderer":{"tabs":[
              {"tabRenderer":{"title":"Up next","endpoint":{"browseEndpoint":{"browseId":"FEmusic_other"}}}},
              {"tabRenderer":{"title":"Lyrics","endpoint":{"browseEndpoint":{"browseId":"MPLYt_abc123"}}}}
            ]}}}
        """)
        assertEquals("MPLYt_abc123", LyricsParser.findLyricsBrowseId(next))
    }

    @Test fun noLyricsTabMeansNull() {
        val next = json("""{"tabs":[{"browseEndpoint":{"browseId":"FEmusic_related"}}]}""")
        assertNull(LyricsParser.findLyricsBrowseId(next))
        assertNull(LyricsParser.findLyricsBrowseId(null))
    }

    @Test fun extractsLyricsTextAndSource() {
        val browse = json("""
            {"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{
              "description":{"runs":[{"text":"First line\nSecond line"},{"text":"\nThird line"}]},
              "footer":{"runs":[{"text":"Source: Example Provider"}]}
            }}]}}}
        """)
        val result = LyricsParser.extractLyrics(browse)
        assertEquals("First line\nSecond line\nThird line", result?.first)
        assertEquals("Source: Example Provider", result?.second)
    }

    @Test fun missingFooterGivesNullSource() {
        val browse = json("""{"a":{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Only text"}]}}}}""")
        val result = LyricsParser.extractLyrics(browse)
        assertEquals("Only text", result?.first)
        assertNull(result?.second)
    }

    @Test fun emptyOrMissingDescriptionMeansNull() {
        assertNull(LyricsParser.extractLyrics(json("""{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"   "}]}}}""")))
        assertNull(LyricsParser.extractLyrics(json("""{"something":"else"}""")))
        assertNull(LyricsParser.extractLyrics(null))
    }
}
