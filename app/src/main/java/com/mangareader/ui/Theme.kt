package com.mangareader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.mangareader.ui.theme.*

enum class AppTheme(val label: String) {
    BLUE("蓝色"), GREEN("绿色"), PURPLE("紫色"), ORANGE("橙色"), PINK("粉色"),
    TACHYOMI("默认"), CATPPUCCIN("猫布丁"), NORD("北欧"), YOTSUBA("四叶"),
    STRAWBERRY("草莓"), GREEN_APPLE("青苹果"), LAVENDER("薰衣草"), MIDNIGHT_DUSK("午夜"),
    TAKO("章鱼"), TEAL("青绿"), TIDAL("海浪"), YINYANG("阴阳"), MONOCHROME("单色")
}

private val colorSchemes: Map<AppTheme, BaseColorScheme> = mapOf(
    AppTheme.BLUE to object : BaseColorScheme() {
        override val darkScheme = androidx.compose.material3.darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF64B5F6), onPrimary = androidx.compose.ui.graphics.Color.Black,
            secondary = androidx.compose.ui.graphics.Color(0xFF80CBC4), onSecondary = androidx.compose.ui.graphics.Color.Black,
            background = androidx.compose.ui.graphics.Color(0xFF121212), surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            onBackground = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2C2C), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
            error = androidx.compose.ui.graphics.Color(0xFFEF5350),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFF1565C0), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFBBDEFB),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFF00695C), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFB2DFDB),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF4527A0), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A2A2A), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF353535),
        )
        override val lightScheme = androidx.compose.material3.lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF1976D2), onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFF1976D2), onSecondary = androidx.compose.ui.graphics.Color.White,
            background = androidx.compose.ui.graphics.Color(0xFFF5F5F5), surface = androidx.compose.ui.graphics.Color.White,
            onBackground = androidx.compose.ui.graphics.Color(0xFF212121), onSurface = androidx.compose.ui.graphics.Color(0xFF212121),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF616161),
            error = androidx.compose.ui.graphics.Color(0xFFD32F2F),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFBBDEFB), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF0D47A1),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFBBDEFB), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF0D47A1),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF311B92),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE8E8E8), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFD0D0D0),
        )
    },
    AppTheme.GREEN to object : BaseColorScheme() {
        override val darkScheme = androidx.compose.material3.darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF81C784), onPrimary = androidx.compose.ui.graphics.Color.Black,
            secondary = androidx.compose.ui.graphics.Color(0xFFA5D6A7), onSecondary = androidx.compose.ui.graphics.Color.Black,
            background = androidx.compose.ui.graphics.Color(0xFF121212), surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            onBackground = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2C2C), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
            error = androidx.compose.ui.graphics.Color(0xFFEF5350),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFF2E7D32), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFC8E6C9),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFF2E7D32), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFC8E6C9),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF4527A0), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A2A2A), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF353535),
        )
        override val lightScheme = androidx.compose.material3.lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF388E3C), onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFF66BB6A), onSecondary = androidx.compose.ui.graphics.Color.White,
            background = androidx.compose.ui.graphics.Color(0xFFF5F5F5), surface = androidx.compose.ui.graphics.Color.White,
            onBackground = androidx.compose.ui.graphics.Color(0xFF212121), onSurface = androidx.compose.ui.graphics.Color(0xFF212121),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF616161),
            error = androidx.compose.ui.graphics.Color(0xFFD32F2F),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFC8E6C9), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF1B5E20),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFC8E6C9), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF1B5E20),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF311B92),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE8E8E8), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFD0D0D0),
        )
    },
    AppTheme.PURPLE to object : BaseColorScheme() {
        override val darkScheme = androidx.compose.material3.darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFCE93D8), onPrimary = androidx.compose.ui.graphics.Color.Black,
            secondary = androidx.compose.ui.graphics.Color(0xFFE1BEE7), onSecondary = androidx.compose.ui.graphics.Color.Black,
            background = androidx.compose.ui.graphics.Color(0xFF121212), surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            onBackground = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2C2C), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
            error = androidx.compose.ui.graphics.Color(0xFFEF5350),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFF6A1B9A), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE1BEE7),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFF6A1B9A), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFF3E5F5),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF4527A0), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A2A2A), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF353535),
        )
        override val lightScheme = androidx.compose.material3.lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF7B1FA2), onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFFAB47BC), onSecondary = androidx.compose.ui.graphics.Color.White,
            background = androidx.compose.ui.graphics.Color(0xFFF5F5F5), surface = androidx.compose.ui.graphics.Color.White,
            onBackground = androidx.compose.ui.graphics.Color(0xFF212121), onSurface = androidx.compose.ui.graphics.Color(0xFF212121),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF616161),
            error = androidx.compose.ui.graphics.Color(0xFFD32F2F),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFE1BEE7), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF4A148C),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFF3E5F5), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF4A148C),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF311B92),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE8E8E8), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFD0D0D0),
        )
    },
    AppTheme.ORANGE to object : BaseColorScheme() {
        override val darkScheme = androidx.compose.material3.darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFFFCC80), onPrimary = androidx.compose.ui.graphics.Color.Black,
            secondary = androidx.compose.ui.graphics.Color(0xFFFFE0B2), onSecondary = androidx.compose.ui.graphics.Color.Black,
            background = androidx.compose.ui.graphics.Color(0xFF121212), surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            onBackground = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2C2C), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
            error = androidx.compose.ui.graphics.Color(0xFFEF5350),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFE65100), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFE0B2),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE65100), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFE0B2),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF4527A0), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A2A2A), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF353535),
        )
        override val lightScheme = androidx.compose.material3.lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFF57C00), onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFFFFB74D), onSecondary = androidx.compose.ui.graphics.Color.Black,
            background = androidx.compose.ui.graphics.Color(0xFFF5F5F5), surface = androidx.compose.ui.graphics.Color.White,
            onBackground = androidx.compose.ui.graphics.Color(0xFF212121), onSurface = androidx.compose.ui.graphics.Color(0xFF212121),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF616161),
            error = androidx.compose.ui.graphics.Color(0xFFD32F2F),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFFFE0B2), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE65100),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFE0B2), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE65100),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF311B92),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE8E8E8), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFD0D0D0),
        )
    },
    AppTheme.PINK to object : BaseColorScheme() {
        override val darkScheme = androidx.compose.material3.darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFF48FB1), onPrimary = androidx.compose.ui.graphics.Color.Black,
            secondary = androidx.compose.ui.graphics.Color(0xFFF8BBD0), onSecondary = androidx.compose.ui.graphics.Color.Black,
            background = androidx.compose.ui.graphics.Color(0xFF121212), surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            onBackground = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2C2C), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
            error = androidx.compose.ui.graphics.Color(0xFFEF5350),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFAD1457), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFF8BBD0),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFAD1457), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFFCE4EC),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF4527A0), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A2A2A), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF353535),
        )
        override val lightScheme = androidx.compose.material3.lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFC2185B), onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFFEC407A), onSecondary = androidx.compose.ui.graphics.Color.White,
            background = androidx.compose.ui.graphics.Color(0xFFF5F5F5), surface = androidx.compose.ui.graphics.Color.White,
            onBackground = androidx.compose.ui.graphics.Color(0xFF212121), onSurface = androidx.compose.ui.graphics.Color(0xFF212121),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE0E0E0), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF616161),
            error = androidx.compose.ui.graphics.Color(0xFFD32F2F),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFF8BBD0), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF880E4F),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFFCE4EC), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF880E4F),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD1C4E9), onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF311B92),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE8E8E8), surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFD0D0D0),
        )
    },
    AppTheme.TACHYOMI to TachiyomiColorScheme,
    AppTheme.CATPPUCCIN to CatppuccinColorScheme,
    AppTheme.NORD to NordColorScheme,
    AppTheme.YOTSUBA to YotsubaColorScheme,
    AppTheme.STRAWBERRY to StrawberryColorScheme,
    AppTheme.GREEN_APPLE to GreenAppleColorScheme,
    AppTheme.LAVENDER to LavenderColorScheme,
    AppTheme.MIDNIGHT_DUSK to MidnightDuskColorScheme,
    AppTheme.TAKO to TakoColorScheme,
    AppTheme.TEAL to TealTurquoiseColorScheme,
    AppTheme.TIDAL to TidalWaveColorScheme,
    AppTheme.YINYANG to YinYangColorScheme,
    AppTheme.MONOCHROME to MonochromeColorScheme,
)

@Composable
fun Theme(
    dark: Boolean = isSystemInDarkTheme(),
    appTheme: AppTheme = AppTheme.BLUE,
    isAmoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = colorSchemes[appTheme]?.getColorScheme(dark, isAmoled)
        ?: colorSchemes[AppTheme.BLUE]!!.getColorScheme(dark, isAmoled)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 获取指定主题的主色（用于设置页预览） */
fun getThemePrimaryColor(theme: AppTheme, isDark: Boolean): androidx.compose.ui.graphics.Color {
    return colorSchemes[theme]?.getColorScheme(isDark, false)?.primary
        ?: colorSchemes[AppTheme.BLUE]!!.getColorScheme(isDark, false).primary
}
