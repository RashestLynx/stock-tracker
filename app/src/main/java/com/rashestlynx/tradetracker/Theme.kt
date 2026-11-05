package com.rashestlynx.tradetracker

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF146C2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA3F5B0),
    onPrimaryContainer = Color(0xFF002109),
    secondary = Color(0xFF516351),
    tertiary = Color(0xFF39656C),
    tertiaryContainer = Color(0xFFBCEBF3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF88D896),
    onPrimary = Color(0xFF003914),
    primaryContainer = Color(0xFF005321),
    onPrimaryContainer = Color(0xFFA3F5B0),
    secondary = Color(0xFFB8CCB6),
    tertiary = Color(0xFFA1CED6),
    tertiaryContainer = Color(0xFF1F4D54),
)

/** Green for buys, red for sells, tuned for light and dark backgrounds. */
object TradeColors {
    @Composable fun buy() = if (isSystemInDarkTheme()) Color(0xFF6DD58C) else Color(0xFF1B7F3B)
    @Composable fun sell() = if (isSystemInDarkTheme()) Color(0xFFFF8A80) else Color(0xFFC62828)
    @Composable fun buyContainer() = if (isSystemInDarkTheme()) Color(0xFF0F3D1E) else Color(0xFFD7F5DE)
    @Composable fun sellContainer() = if (isSystemInDarkTheme()) Color(0xFF4A1414) else Color(0xFFFDE0DE)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        // Android 12+: match the phone's wallpaper colors
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
