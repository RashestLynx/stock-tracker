package com.rashestlynx.tradetracker

import android.app.Activity
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    @Composable fun buy() = if (LocalDark.current) Color(0xFF6DD58C) else Color(0xFF1B7F3B)
    @Composable fun sell() = if (LocalDark.current) Color(0xFFFF8A80) else Color(0xFFC62828)
    @Composable fun buyContainer() = if (LocalDark.current) Color(0xFF0F3D1E) else Color(0xFFD7F5DE)
    @Composable fun sellContainer() = if (LocalDark.current) Color(0xFF4A1414) else Color(0xFFFDE0DE)
}

/** True when the app is showing dark colors (from the phone or the app's own setting). */
val LocalDark = compositionLocalOf { false }

/** Today's S&P 500 move (via SPY), used for the market-color background. Null until loaded. */
val LocalMarket = compositionLocalOf<LiveQuote?> { null }

/** Appearance settings, stored with the rest of the app's settings. */
object Appearance {
    const val MODE = "theme_mode"        // "system", "light" or "dark"
    const val MARKET_BG = "market_bg"    // tint the background green or red with the market
    fun mode(store: Store) = store.prefs.getString(MODE, "system") ?: "system"
    fun marketBg(store: Store) = store.prefs.getBoolean(MARKET_BG, true)
}

@Composable
fun AppTheme(store: Store, content: @Composable () -> Unit) {
    var mode by remember { mutableStateOf(Appearance.mode(store)) }
    var marketBg by remember { mutableStateOf(Appearance.marketBg(store)) }
    DisposableEffect(store) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Appearance.MODE) mode = Appearance.mode(store)
            if (key == Appearance.MARKET_BG) marketBg = Appearance.marketBg(store)
        }
        store.prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }

    // Check the market every 5 minutes while the app is open.
    var market by remember { mutableStateOf<LiveQuote?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { Live.quote("SPY", store) }.getOrNull() }?.let { market = it }
            delay(5 * 60_000L)
        }
    }

    var colors = if (dark) DarkColors else LightColors
    val q = market
    if (marketBg && q != null) {
        val bg = when {
            q.changePct >= 0 && dark -> Color(0xFF06180C)
            q.changePct >= 0 -> Color(0xFFE6F6EA)
            dark -> Color(0xFF1D0808)
            else -> Color(0xFFFCE9E8)
        }
        colors = colors.copy(background = bg, surface = bg)
    }

    // Status bar icons readable on the chosen background.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalDark provides dark, LocalMarket provides market) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
