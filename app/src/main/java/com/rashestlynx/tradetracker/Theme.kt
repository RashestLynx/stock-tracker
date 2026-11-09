package com.rashestlynx.tradetracker

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3DF5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF1A0B66),
    secondary = Color(0xFF5E5A72),
    secondaryContainer = Color(0xFFE7E3F5),
    onSecondaryContainer = Color(0xFF1B1830),
    tertiary = Color(0xFFD6336C),
    tertiaryContainer = Color(0xFFFFE0EA),
    onTertiaryContainer = Color(0xFF3E0019),
    background = Color.White,
    surface = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F7FB),
    surfaceContainer = Color(0xFFF3F2F8),
    surfaceContainerHigh = Color(0xFFEFEEF5),
    surfaceContainerHighest = Color(0xFFE8E6F0),
    outlineVariant = Color(0xFFDDDAE6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB1A2FF),
    onPrimary = Color(0xFF22107A),
    primaryContainer = Color(0xFF3B26B8),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFFC9C4DD),
    secondaryContainer = Color(0xFF2E2B40),
    onSecondaryContainer = Color(0xFFE7E3F5),
    tertiary = Color(0xFFFF8FB4),
    tertiaryContainer = Color(0xFF5C1131),
    onTertiaryContainer = Color(0xFFFFE0EA),
    background = Color(0xFF0B0B10),
    surface = Color(0xFF0B0B10),
    surfaceContainerLowest = Color(0xFF07070A),
    surfaceContainerLow = Color(0xFF121218),
    surfaceContainer = Color(0xFF16161D),
    surfaceContainerHigh = Color(0xFF1D1D26),
    surfaceContainerHighest = Color(0xFF25252F),
    outlineVariant = Color(0xFF34333F),
)

/** Accent colors shared across the app: the story-ring gradient and the like heart. */
object Brand {
    val story = listOf(Color(0xFFFF8A3D), Color(0xFFE8358E), Color(0xFF7B3FF2), Color(0xFF3D7BFF))
    val like = Color(0xFFF0386B)
}

/** Green for buys, red for sells, tuned for light and dark backgrounds. */
object TradeColors {
    @Composable fun buy() = if (isSystemInDarkTheme()) Color(0xFF6DD58C) else Color(0xFF1B7F3B)
    @Composable fun sell() = if (isSystemInDarkTheme()) Color(0xFFFF8A80) else Color(0xFFC62828)
    @Composable fun buyContainer() = if (isSystemInDarkTheme()) Color(0xFF0F3D1E) else Color(0xFFD7F5DE)
    @Composable fun sellContainer() = if (isSystemInDarkTheme()) Color(0xFF4A1414) else Color(0xFFFDE0DE)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // One fixed brand look in light and dark, rather than wallpaper-based colors.
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
