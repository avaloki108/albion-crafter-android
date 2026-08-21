package com.dokholliday.albioncrafter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFF0BD58)
val GoldDark = Color(0xFFC89432)
val BgDark = Color(0xFF171A21)
val SurfaceDark = Color(0xFF1D212A)
val SurfaceVariantDark = Color(0xFF222630)
val SidebarDark = Color(0xFF11141A)
val TextMuted = Color(0xFF929AAA)
val GreenFresh = Color(0xFF75D8A2)
val AmberAging = Color(0xFFFFCB6B)
val RedStale = Color(0xFFFF8585)

val AlbionColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF11141A),
    primaryContainer = GoldDark,
    background = BgDark,
    onBackground = Color(0xFFE5E9F0),
    surface = SurfaceDark,
    onSurface = Color(0xFFE5E9F0),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextMuted,
    secondary = GoldDark,
    error = RedStale,
)

@Composable
fun AlbionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AlbionColorScheme,
        content = content,
    )
}

fun Long.toMoney(): String {
    val negative = this < 0
    val abs = kotlin.math.abs(this)
    val text = when {
        abs >= 1_000_000_000 -> String.format("%.2fB", abs / 1_000_000_000.0)
        abs >= 1_000_000 -> String.format("%.2fM", abs / 1_000_000.0)
        abs >= 10_000 -> String.format("%.1fK", abs / 1_000.0)
        else -> String.format("%,d", abs)
    }
    return if (negative) "-$text" else text
}

fun Double?.toMoney(): String =
    if (this == null) "—" else String.format("%,.0f", this)

fun Double?.toPercent(): String =
    if (this == null) "—" else String.format("%.1f%%", this * 100)
