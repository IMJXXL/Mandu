package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object MidnightDuskColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFFBB86FC), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF6200EE), onPrimaryContainer = Color(0xFFE8DEF8),
        secondary = Color(0xFFCF6679), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF3700B3), onSecondaryContainer = Color(0xFFCF6679),
        tertiary = Color(0xFF03DAC6), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF018786), onTertiaryContainer = Color(0xFFB2DFDB),
        error = Color(0xFFCF6679), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB00020), onErrorContainer = Color(0xFFF2B8B5),
        background = Color(0xFF121212), onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF1E1E1E), onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2C2C2C), onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFFBB86FC), outlineVariant = Color(0xFF757575),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF5F5F5), inverseOnSurface = Color(0xFF212121),
        inversePrimary = Color(0xFF6200EE),
        surfaceDim = Color(0xFF121212), surfaceBright = Color(0xFF383838),
        surfaceContainerLowest = Color(0xFF121212), surfaceContainerLow = Color(0xFF1E1E1E),
        surfaceContainer = Color(0xFF222222), surfaceContainerHigh = Color(0xFF2C2C2C),
        surfaceContainerHighest = Color(0xFF383838),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF6200EE), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBB86FC), onPrimaryContainer = Color(0xFF3700B3),
        secondary = Color(0xFF03DAC6), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF018786), onSecondaryContainer = Color(0xFFB2DFDB),
        tertiary = Color(0xFFBB86FC), onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFFE8DEF8), onTertiaryContainer = Color(0xFF3700B3),
        error = Color(0xFFB00020), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF2B8B5), onErrorContainer = Color(0xFF601410),
        background = Color(0xFFFFFBFE), onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFE), onSurface = Color(0xFF1C1B1F),
        surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF6200EE), outlineVariant = Color(0xFFCAC4D0),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF313033), inverseOnSurface = Color(0xFFF4EFF4),
        inversePrimary = Color(0xFFBB86FC),
        surfaceDim = Color(0xFFDED8E1), surfaceBright = Color(0xFFFFFBFE),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F2FA),
        surfaceContainer = Color(0xFFF1ECF4), surfaceContainerHigh = Color(0xFFECE6F0),
        surfaceContainerHighest = Color(0xFFE6E0E9),
    )
}
