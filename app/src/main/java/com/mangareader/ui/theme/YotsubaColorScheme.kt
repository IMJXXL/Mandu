package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object YotsubaColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFFB74D), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFFE65100), onPrimaryContainer = Color(0xFFFFE0B2),
        secondary = Color(0xFFFFCC80), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF4E342E), onSecondaryContainer = Color(0xFFFFCC80),
        tertiary = Color(0xFF81C784), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF1B5E20), onTertiaryContainer = Color(0xFFC8E6C9),
        error = Color(0xFFEF5350), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB71C1C), onErrorContainer = Color(0xFFFFCDD2),
        background = Color(0xFF121212), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF1E1E1E), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2C2C2C), onSurfaceVariant = Color(0xFFBDBDBD),
        outline = Color(0xFFFFB74D), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFFE65100),
        surfaceDim = Color(0xFF121212), surfaceBright = Color(0xFF383838),
        surfaceContainerLowest = Color(0xFF121212), surfaceContainerLow = Color(0xFF1E1E1E),
        surfaceContainer = Color(0xFF222222), surfaceContainerHigh = Color(0xFF2C2C2C),
        surfaceContainerHighest = Color(0xFF383838),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFFE65100), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFB74D), onPrimaryContainer = Color(0xFF3E2723),
        secondary = Color(0xFFF57C00), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFE0B2), onSecondaryContainer = Color(0xFF3E2723),
        tertiary = Color(0xFF388E3C), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC8E6C9), onTertiaryContainer = Color(0xFF1B5E20),
        error = Color(0xFFD32F2F), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFFB71C1C),
        background = Color(0xFFFFFBF5), onBackground = Color(0xFF212121),
        surface = Color(0xFFFFFBF5), onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFF5E6D3), onSurfaceVariant = Color(0xFF5D4037),
        outline = Color(0xFFE65100), outlineVariant = Color(0xFFBCAAA4),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFFFFB74D),
        surfaceDim = Color(0xFFE8DDD4), surfaceBright = Color(0xFFFFFBF5),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F0EB),
        surfaceContainer = Color(0xFFF0EAE4), surfaceContainerHigh = Color(0xFFEAE4DE),
        surfaceContainerHighest = Color(0xFFE4DED8),
    )
}
