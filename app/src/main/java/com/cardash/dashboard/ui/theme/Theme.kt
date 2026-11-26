package com.cardash.dashboard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF3D8BFF)
val Good = Color(0xFF2FD27A)
val Warn = Color(0xFFEF9F27)
val Danger = Color(0xFFE24B4A)
val Bg = Color(0xFF0B0B0D)
val Surface1 = Color(0xFF121215)
val Tile = Color(0xFF1B1B20)
val Muted = Color(0xFF8A8A92)
val Faint = Color(0xFF5B5B63)
val Hairline = Color(0xFF26262B)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Good,
    onSecondary = Color.Black,
    background = Bg,
    onBackground = Color.White,
    surface = Surface1,
    onSurface = Color.White,
    surfaceVariant = Tile,
    onSurfaceVariant = Muted,
    error = Danger,
    onError = Color.White,
    outline = Hairline,
)

@Composable
fun CarDashboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
