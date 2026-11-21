package com.ytmusic.clone.ui.theme

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure colour math (v7.20, no Android classes, JVM-testable): picks one "mood" colour from pixels.
 * Colourful pixels count more than grey ones, and the result is darkened so white text stays readable.
 */
object ArtworkColorMath {
    private const val MAX_CHANNEL = 150   // darkest-friendly cap so white text keeps contrast
    private const val MIN_CHANNEL = 25    // below this the art is basically black: use the default

    /** [argbPixels] are 0xAARRGGBB. Returns an opaque 0xFFRRGGBB or null if no usable colour. */
    fun accentFromPixels(argbPixels: IntArray): Int? {
        var wSum = 0.0; var rSum = 0.0; var gSum = 0.0; var bSum = 0.0
        for (p in argbPixels) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val sat = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            val w = sat * sat + 0.05
            wSum += w; rSum += r * w; gSum += g * w; bSum += b * w
        }
        if (wSum <= 0.0) return null
        var r = rSum / wSum; var g = gSum / wSum; var b = bSum / wSum
        val mx = maxOf(r, g, b)
        if (mx < MIN_CHANNEL) return null
        if (mx > MAX_CHANNEL) { val f = MAX_CHANNEL / mx; r *= f; g *= f; b *= f }
        return (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }
}

/** Loads the artwork (Coil cache is reused) and returns its mood colour, or null while loading / on failure. */
@Composable
fun rememberArtworkAccent(url: String?): Color? {
    val context = LocalContext.current
    var accent by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) { accent = null; return@LaunchedEffect }
        accent = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(96).build()
                val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext null
                val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
                val px = IntArray(24 * 24)
                small.getPixels(px, 0, 24, 0, 0, 24, 24)
                ArtworkColorMath.accentFromPixels(px)?.let { Color(it) }
            } catch (e: Exception) {
                null
            }
        }
    }
    return accent
}
