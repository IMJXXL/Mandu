package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object TidalWaveColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFF82B1FF), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF1565C0), onPrimaryContainer = Color(0xFFBBDEFB),
        secondary = Color(0xFF80D8FF), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF01579B), onSecondaryContainer = Color(0xFFB3E5FC),
        tertiary = Color(0xFF80CBC4), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF004D40), onTertiaryContainer = Color(0xFFB2DFDB),
        error = Color(0xFFEF5350), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB71C1C), onErrorContainer = Color(0xFFFFCDD2),
        background = Color(0xFF0D1B2A), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF1B2838), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF243448), onSurfaceVariant = Color(0xFFB3E5FC),
        outline = Color(0xFF82B1FF), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFF1565C0),
        surfaceDim = Color(0xFF0D1B2A), surfaceBright = Color(0xFF283848),
        surfaceContainerLowest = Color(0xFF0D1B2A), surfaceContainerLow = Color(0xFF1B2838),
        surfaceContainer = Color(0xFF243448), surfaceContainerHigh = Color(0xFF2C3C50),
        surfaceContainerHighest = Color(0xFF344458),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF1565C0), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF82B1FF), onPrimaryContainer = Color(0xFF0D47A1),
        secondary = Color(0xFF0288D1), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFB3E5FC), onSecondaryContainer = Color(0xFF01579B),
        tertiary = Color(0xFF00897B), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB2DFDB), onTertiaryContainer = Color(0xFF004D40),
        error = Color(0xFFD32F2F), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFFB71C1C),
        background = Color(0xFFF0F7FF), onBackground = Color(0xFF212121),
        surface = Color(0xFFF0F7FF), onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFE1F5FE), onSurfaceVariant = Color(0xFF0D47A1),
        outline = Color(0xFF1565C0), outlineVariant = Color(0xFFB3E5FC),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFF82B1FF),
        surfaceDim = Color(0xFFD0E0F0), surfaceBright = Color(0xFFF0F7FF),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEAF4FF),
        surfaceContainer = Color(0xFFE4EEFF), surfaceContainerHigh = Color(0xFFDEE8F8),
        surfaceContainerHighest = Color(0xFFD8E2F2),
    )
}
