package com.dannycom888.mylrcauto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PrimaryBlue = Color(0xFF0A4A8E)
private val SecondaryTeal = Color(0xFF9FDCE8)

private val DarkScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    secondary = SecondaryTeal,
    onSecondary = Color(0xFF06283F),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFF2F0F5),
    surface = Color(0xFF12181F),
    onSurface = Color(0xFFF2F0F5),
    surfaceVariant = Color(0xFF1E2833),
    onSurfaceVariant = Color(0xFFB9C4CC),
)

/** Minimal Material3 dark theme. No dynamic colour. */
@Composable
fun MyLrcAutoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography(),
        content = content,
    )
}
