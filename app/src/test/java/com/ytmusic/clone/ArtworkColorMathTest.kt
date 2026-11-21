package com.ytmusic.clone

import com.ytmusic.clone.ui.theme.ArtworkColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v7.20: the album-art "mood colour" picker (pure maths, no Android). */
class ArtworkColorMathTest {

    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test fun colourfulPixelsBeatGreyOnes() {
        // 90% grey, 10% strong blue: the result should still lean clearly blue
        val px = IntArray(100) { if (it < 10) argb(20, 40, 220) else argb(128, 128, 128) }
        val c = ArtworkColorMath.accentFromPixels(px)
        assertNotNull(c)
        assertTrue(blue(c!!) > red(c) + 20)
    }

    @Test fun resultIsDarkenedAndOpaque() {
        val c = ArtworkColorMath.accentFromPixels(IntArray(50) { argb(255, 255, 255) })
        assertNotNull(c)
        assertEquals(0xFF, (c!! ushr 24) and 0xFF)
        assertTrue(maxOf(red(c), green(c), blue(c)) <= 150)
    }

    @Test fun almostBlackArtGivesNullSoDefaultIsUsed() {
        assertNull(ArtworkColorMath.accentFromPixels(IntArray(50) { argb(5, 5, 5) }))
    }

    @Test fun transparentOrEmptyGivesNull() {
        assertNull(ArtworkColorMath.accentFromPixels(IntArray(0)))
        assertNull(ArtworkColorMath.accentFromPixels(IntArray(10) { argb(200, 0, 0, a = 0) }))
    }
}
