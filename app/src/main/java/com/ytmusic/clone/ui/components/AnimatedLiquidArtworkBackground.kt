package com.ytmusic.clone.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import coil.compose.AsyncImage
import com.ytmusic.clone.data.model.*
import com.ytmusic.clone.data.network.*
import com.ytmusic.clone.data.local.*
import com.ytmusic.clone.player.*
import com.ytmusic.clone.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AnimatedLiquidArtworkBackground(
    accentColor: Color,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "liquidArtworkMotion")

    // Performance: Pause GPU shader animation cycle when playback is paused (0% GPU utilization)
    val animDuration1 = if (isPlaying) 10000 else 36000000
    val animDuration2 = if (isPlaying) 14000 else 36000000
    val animPulse = if (isPlaying) 7000 else 36000000

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = animDuration1, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "phase1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = animDuration2, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "phase2"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = animPulse, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val color1 = accentColor.copy(alpha = 0.85f)
    val color2 = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (accentColor.red * 255).toInt(),
            (accentColor.green * 255).toInt(),
            (accentColor.blue * 255).toInt(),
            hsv
        )
        hsv[0] = (hsv[0] + 45f) % 360f
        Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = 0.70f)
    }
    val color3 = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (accentColor.red * 255).toInt(),
            (accentColor.green * 255).toInt(),
            (accentColor.blue * 255).toInt(),
            hsv
        )
        hsv[0] = (hsv[0] + 315f) % 360f
        Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = 0.60f)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Deep tinted base
        drawRect(color = Color(0xFF0A0A0A))

        // 2. Fluid Aurora Blob 1 (Top-Left drifting to Center-Top)
        val center1 = Offset(
            x = w * (0.20f + 0.45f * phase1),
            y = h * (0.15f + 0.20f * phase2)
        )
        val radius1 = w * 0.90f * pulseScale
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color1, color1.copy(alpha = 0.35f), Color.Transparent),
                center = center1,
                radius = radius1
            ),
            center = center1,
            radius = radius1
        )

        // 3. Fluid Aurora Blob 2 (Mid-Right drifting downward)
        val center2 = Offset(
            x = w * (0.85f - 0.40f * phase2),
            y = h * (0.40f + 0.30f * phase1)
        )
        val radius2 = w * 0.85f * (2.15f - pulseScale)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color2, color2.copy(alpha = 0.25f), Color.Transparent),
                center = center2,
                radius = radius2
            ),
            center = center2,
            radius = radius2
        )

        // 4. Fluid Aurora Blob 3 (Bottom-Center ambient glow)
        val center3 = Offset(
            x = w * (0.30f + 0.40f * phase2),
            y = h * (0.75f - 0.20f * phase1)
        )
        val radius3 = w * 0.80f * pulseScale
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color3, color3.copy(alpha = 0.20f), Color.Transparent),
                center = center3,
                radius = radius3
            ),
            center = center3,
            radius = radius3
        )

        // 5. Light ambient vignette that protects control readability while letting the fluid colors shine through
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Black.copy(alpha = 0.10f),
                0.50f to Color.Black.copy(alpha = 0.35f),
                0.85f to Color.Black.copy(alpha = 0.65f),
                1.0f to Color(0xFF0A0A0A).copy(alpha = 0.85f)
            )
        )
    }
}
