package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object GreenAppleColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFF81C784), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF2E7D32), onPrimaryContainer = Color(0xFFC8E6C9),
        secondary = Color(0xFFA5D6A7), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF1B5E20), onSecondaryContainer = Color(0xFFC8E6C9),
        tertiary = Color(0xFF80CBC4), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF004D40), onTertiaryContainer = Color(0xFFB2DFDB),
        error = Color(0xFFEF5350), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB71C1C), onErrorContainer = Color(0xFFFFCDD2),
        background = Color(0xFF0D1F0D), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF142414), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF1E301E), onSurfaceVariant = Color(0xFFA5D6A7),
        outline = Color(0xFF81C784), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFF2E7D32),
        surfaceDim = Color(0xFF0D1F0D), surfaceBright = Color(0xFF283828),
        surfaceContainerLowest = Color(0xFF0D1F0D), surfaceContainerLow = Color(0xFF142414),
        surfaceContainer = Color(0xFF1C2C1C), surfaceContainerHigh = Color(0xFF243424),
        surfaceContainerHighest = Color(0xFF2C3C2C),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF2E7D32), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF81C784), onPrimaryContainer = Color(0xFF1B5E20),
        secondary = Color(0xFF43A047), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFC8E6C9), onSecondaryContainer = Color(0xFF1B5E20),
        tertiary = Color(0xFF00897B), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB2DFDB), onTertiaryContainer = Color(0xFF004D40),
        error = Color(0xFFD32F2F), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFFB71C1C),
        background = Color(0xFFF5FFF5), onBackground = Color(0xFF212121),
        surface = Color(0xFFF5FFF5), onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFE8F5E9), onSurfaceVariant = Color(0xFF33691E),
        outline = Color(0xFF2E7D32), outlineVariant = Color(0xFFA5D6A7),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFF81C784),
        surfaceDim = Color(0xFFD5E8D5), surfaceBright = Color(0xFFF5FFF5),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0FAF0),
        surfaceContainer = Color(0xFFEAF4EA), surfaceContainerHigh = Color(0xFFE4EEE4),
        surfaceContainerHighest = Color(0xFFDEE8DE),
    )
}
