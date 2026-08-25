package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object StrawberryColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFF6B9D), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFFC2185B), onPrimaryContainer = Color(0xFFF8BBD0),
        secondary = Color(0xFFFF8A80), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF4A148C), onSecondaryContainer = Color(0xFFFF8A80),
        tertiary = Color(0xFF80CBC4), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF004D40), onTertiaryContainer = Color(0xFFB2DFDB),
        error = Color(0xFFEF5350), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB71C1C), onErrorContainer = Color(0xFFFFCDD2),
        background = Color(0xFF1A1016), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF221820), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF322030), onSurfaceVariant = Color(0xFFCCBBCC),
        outline = Color(0xFFFF6B9D), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFFC2185B),
        surfaceDim = Color(0xFF1A1016), surfaceBright = Color(0xFF382830),
        surfaceContainerLowest = Color(0xFF1A1016), surfaceContainerLow = Color(0xFF221820),
        surfaceContainer = Color(0xFF2A2028), surfaceContainerHigh = Color(0xFF322830),
        surfaceContainerHighest = Color(0xFF3E3038),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFFC2185B), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFF6B9D), onPrimaryContainer = Color(0xFF880E4F),
        secondary = Color(0xFFE91E63), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF8BBD0), onSecondaryContainer = Color(0xFF880E4F),
        tertiary = Color(0xFF00897B), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB2DFDB), onTertiaryContainer = Color(0xFF004D40),
        error = Color(0xFFD32F2F), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFFB71C1C),
        background = Color(0xFFFFF5F8), onBackground = Color(0xFF212121),
        surface = Color(0xFFFFF5F8), onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFFCE4EC), onSurfaceVariant = Color(0xFF5D4037),
        outline = Color(0xFFC2185B), outlineVariant = Color(0xFFBCAAA4),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFFFF6B9D),
        surfaceDim = Color(0xFFE8D4DC), surfaceBright = Color(0xFFFFF5F8),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F0F2),
        surfaceContainer = Color(0xFFF0EAE8), surfaceContainerHigh = Color(0xFFEAE4E2),
        surfaceContainerHighest = Color(0xFFE4DED8),
    )
}
