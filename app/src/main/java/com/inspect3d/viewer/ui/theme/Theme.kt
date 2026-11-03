package com.inspect3d.viewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The viewer is always dark.
 *
 * This is not a stylistic preference: the viewport underneath the controls is a dark 3D
 * scene at all times, so a light scheme puts dark text and white panels on top of it —
 * unreadable, which is exactly what happened when the theme followed the system setting.
 * The scheme is therefore fixed rather than keyed off `isSystemInDarkTheme()`.
 */
private val Accent = Color(0xFF4FA3FF)
private val AccentDark = Color(0xFF0B3D73)
private val Amber = Color(0xFFFFB020)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00172E),
    primaryContainer = AccentDark,
    onPrimaryContainer = Color(0xFFD7E9FF),
    secondary = Amber,
    onSecondary = Color(0xFF2B1B00),
    background = Color(0xFF070A0F),
    onBackground = Color(0xFFE4E9F1),
    surface = Color(0xFF101620),
    onSurface = Color(0xFFE4E9F1),
    surfaceVariant = Color(0xFF1B2430),
    onSurfaceVariant = Color(0xFFAFBCCC),
    outline = Color(0xFF3A4654),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000),
)

@Composable
fun Inspect3DTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
