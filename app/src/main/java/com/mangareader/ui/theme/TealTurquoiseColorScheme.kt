package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object TealTurquoiseColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFF80CBC4), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF00695C), onPrimaryContainer = Color(0xFFB2DFDB),
        secondary = Color(0xFF4DB6AC), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF004D40), onSecondaryContainer = Color(0xFFB2DFDB),
        tertiary = Color(0xFF81C784), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF1B5E20), onTertiaryContainer = Color(0xFFC8E6C9),
        error = Color(0xFFEF5350), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB71C1C), onErrorContainer = Color(0xFFFFCDD2),
        background = Color(0xFF0D1F1E), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF142C2B), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF1E3837), onSurfaceVariant = Color(0xFFB2DFDB),
        outline = Color(0xFF80CBC4), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFF00695C),
        surfaceDim = Color(0xFF0D1F1E), surfaceBright = Color(0xFF283E3D),
        surfaceContainerLowest = Color(0xFF0D1F1E), surfaceContainerLow = Color(0xFF142C2B),
        surfaceContainer = Color(0xFF1C3433), surfaceContainerHigh = Color(0xFF243C3B),
        surfaceContainerHighest = Color(0xFF2C4443),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF00695C), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF80CBC4), onPrimaryContainer = Color(0xFF004D40),
        secondary = Color(0xFF00897B), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFB2DFDB), onSecondaryContainer = Color(0xFF004D40),
        tertiary = Color(0xFF388E3C), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC8E6C9), onTertiaryContainer = Color(0xFF1B5E20),
        error = Color(0xFFD32F2F), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFFB71C1C),
        background = Color(0xFFF0FAF9), onBackground = Color(0xFF212121),
        surface = Color(0xFFF0FAF9), onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFE0F2F1), onSurfaceVariant = Color(0xFF004D40),
        outline = Color(0xFF00695C), outlineVariant = Color(0xFFB2DFDB),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFF80CBC4),
        surfaceDim = Color(0xFFD0E8E7), surfaceBright = Color(0xFFF0FAF9),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEAF6F5),
        surfaceContainer = Color(0xFFE4F0EF), surfaceContainerHigh = Color(0xFFDEEAEA),
        surfaceContainerHighest = Color(0xFFD8E4E3),
    )
}
