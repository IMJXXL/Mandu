package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object MonochromeColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFFBDBDBD), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF616161), onPrimaryContainer = Color(0xFFF5F5F5),
        secondary = Color(0xFF9E9E9E), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF424242), onSecondaryContainer = Color(0xFFE0E0E0),
        tertiary = Color(0xFF757575), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF616161), onTertiaryContainer = Color(0xFFF5F5F5),
        error = Color(0xFFEF5350), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB71C1C), onErrorContainer = Color(0xFFFFCDD2),
        background = Color(0xFF121212), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF1E1E1E), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2C2C2C), onSurfaceVariant = Color(0xFFBDBDBD),
        outline = Color(0xFF9E9E9E), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFF616161),
        surfaceDim = Color(0xFF121212), surfaceBright = Color(0xFF383838),
        surfaceContainerLowest = Color(0xFF121212), surfaceContainerLow = Color(0xFF1E1E1E),
        surfaceContainer = Color(0xFF222222), surfaceContainerHigh = Color(0xFF2C2C2C),
        surfaceContainerHighest = Color(0xFF383838),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF424242), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE0E0E0), onPrimaryContainer = Color(0xFF212121),
        secondary = Color(0xFF616161), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF5F5F5), onSecondaryContainer = Color(0xFF424242),
        tertiary = Color(0xFF757575), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0E0E0), onTertiaryContainer = Color(0xFF424242),
        error = Color(0xFFD32F2F), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFFB71C1C),
        background = Color(0xFFFAFAFA), onBackground = Color(0xFF212121),
        surface = Color(0xFFFAFAFA), onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFF5F5F5), onSurfaceVariant = Color(0xFF616161),
        outline = Color(0xFF757575), outlineVariant = Color(0xFFBDBDBD),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFFE0E0E0),
        surfaceDim = Color(0xFFD6D6D6), surfaceBright = Color(0xFFFAFAFA),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F8F8),
        surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFECECEC),
        surfaceContainerHighest = Color(0xFFE6E6E6),
    )
}
