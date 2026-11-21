package com.ytmusic.clone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = YtPrimaryRed,
    secondary = YtTextSecondary,
    background = YtBackground,
    surface = YtSurface,
    surfaceVariant = YtSurfaceVariant,
    onPrimary = YtTextPrimary,
    onSecondary = YtTextPrimary,
    onBackground = YtTextPrimary,
    onSurface = YtTextPrimary
)

@Composable
fun YTMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
