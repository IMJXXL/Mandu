package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object LavenderColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFFCE93D8), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF7B1FA2), onPrimaryContainer = Color(0xFFE1BEE7),
        secondary = Color(0xFFE1BEE7), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF4A148C), onSecondaryContainer = Color(0xFFE1BEE7),
        tertiary = Color(0xFF80CBC4), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF004D40), onTertiaryContainer = Color(0xFFB2DFDB),
        error = Color(0xFFEF5350), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB71C1C), onErrorContainer = Color(0xFFFFCDD2),
        background = Color(0xFF15101E), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF1E1828), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2A2038), onSurfaceVariant = Color(0xFFD1C4E9),
        outline = Color(0xFFCE93D8), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFF7B1FA2),
        surfaceDim = Color(0xFF15101E), surfaceBright = Color(0xFF302840),
        surfaceContainerLowest = Color(0xFF15101E), surfaceContainerLow = Color(0xFF1E1828),
        surfaceContainer = Color(0xFF262030), surfaceContainerHigh = Color(0xFF2E2838),
        surfaceContainerHighest = Color(0xFF363040),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF7B1FA2), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCE93D8), onPrimaryContainer = Color(0xFF4A148C),
        secondary = Color(0xFF9C27B0), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE1BEE7), onSecondaryContainer = Color(0xFF4A148C),
        tertiary = Color(0xFF00897B), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB2DFDB), onTertiaryContainer = Color(0xFF004D40),
        error = Color(0xFFD32F2F), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFCDD2), onErrorContainer = Color(0xFFB71C1C),
        background = Color(0xFFF8F5FF), onBackground = Color(0xFF212121),
        surface = Color(0xFFF8F5FF), onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFF3E5F5), onSurfaceVariant = Color(0xFF4A148C),
        outline = Color(0xFF7B1FA2), outlineVariant = Color(0xFFCE93D8),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFFCE93D8),
        surfaceDim = Color(0xFFDDD4E8), surfaceBright = Color(0xFFF8F5FF),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2EFF8),
        surfaceContainer = Color(0xFFEDEAF4), surfaceContainerHigh = Color(0xFFE7E4EE),
        surfaceContainerHighest = Color(0xFFE1DEE8),
    )
}
