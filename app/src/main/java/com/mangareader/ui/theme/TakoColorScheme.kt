package com.mangareader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object TakoColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFFBB86FC), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF6750A4), onPrimaryContainer = Color(0xFFE8DEF8),
        secondary = Color(0xFFCF6679), onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF625B71), onSecondaryContainer = Color(0xFFE8DEF8),
        tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF7D5260), onTertiaryContainer = Color(0xFFFFD8E4),
        error = Color(0xFFCF6679), onError = Color(0xFF000000),
        errorContainer = Color(0xFFB3261E), onErrorContainer = Color(0xFFF9DEDC),
        background = Color(0xFF1C1B1F), onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF1C1B1F), onSurface = Color(0xFFE6E1E5),
        surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE6E1E5), inverseOnSurface = Color(0xFF313033),
        inversePrimary = Color(0xFF6750A4),
        surfaceDim = Color(0xFF141218), surfaceBright = Color(0xFF3B383E),
        surfaceContainerLowest = Color(0xFF0F0D13), surfaceContainerLow = Color(0xFF1D1B20),
        surfaceContainer = Color(0xFF211F26), surfaceContainerHigh = Color(0xFF2B2930),
        surfaceContainerHighest = Color(0xFF36343B),
    )
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
        tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
        error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        background = Color(0xFFFFFBFE), onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFE), onSurface = Color(0xFF1C1B1F),
        surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF313033), inverseOnSurface = Color(0xFFF4EFF4),
        inversePrimary = Color(0xFFE8DEF8),
        surfaceDim = Color(0xFFDED8E1), surfaceBright = Color(0xFFFFFBFE),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F2FA),
        surfaceContainer = Color(0xFFF1ECF4), surfaceContainerHigh = Color(0xFFECE6F0),
        surfaceContainerHighest = Color(0xFFE6E0E9),
    )
}
