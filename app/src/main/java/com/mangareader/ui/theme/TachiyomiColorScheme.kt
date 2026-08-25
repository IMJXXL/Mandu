package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object TachiyomiColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFF64B5F6), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF1565C0), onPrimaryContainer = Color(0xFFBBDEFB),
        secondary = Color(0xFF80CBC4), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF00695C), onSecondaryContainer = Color(0xFFB2DFDB),
        tertiary = Color(0xFFCE93D8), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF7B1FA2), onTertiaryContainer = Color(0xFFE1BEE7),
        error = Color(0xFFEF5350), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB71C1C), onErrorContainer = Color(0xFFFFCDD2),
        background = Color(0xFF121212), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF1E1E1E), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2C2C2C), onSurfaceVariant = Color(0xFFBDBDBD),
        outline = Color(0xFF64B5F6), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFF1565C0),
        surfaceDim = Color(0xFF121212), surfaceBright = Color(0xFF383838),
        surfaceContainerLowest = Color(0xFF121212), surfaceContainerLow = Color(0xFF1E1E1E),
        surfaceContainer = Color(0xFF222222), surfaceContainerHigh = Color(0xFF2C2C2C),
        surfaceContainerHighest = Color(0xFF383838),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF1976D2), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBBDEFB), onPrimaryContainer = Color(0xFF0D47A1),
        secondary = Color(0xFF00897B), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFB2DFDB), onSecondaryContainer = Color(0xFF004D40),
        tertiary = Color(0xFF7B1FA2), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE1BEE7), onTertiaryContainer = Color(0xFF4A148C),
        error = Color(0xFFD32F2F), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFFB71C1C),
        background = Color(0xFFF5F5F5), onBackground = Color(0xFF212121),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFE0E0E0), onSurfaceVariant = Color(0xFF616161),
        outline = Color(0xFF1976D2), outlineVariant = Color(0xFFBDBDBD),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFFBBDEFB),
        surfaceDim = Color(0xFFD6D6D6), surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F8F8),
        surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFECECEC),
        surfaceContainerHighest = Color(0xFFE6E6E6),
    )
}
