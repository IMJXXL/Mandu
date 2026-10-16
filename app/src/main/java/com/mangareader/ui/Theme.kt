package com.mangareader.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mangareader.ui.theme.*

enum class AppTheme(val label: String) {
    BLUE("默认"), GREEN("绿色"), PURPLE("紫色"), ORANGE("橙色"), PINK("粉色"),
    TACHYOMI("经典蓝"), CATPPUCCIN("猫布丁"), NORD("北欧"), YOTSUBA("四叶"),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF191919), surfaceContainer = androidx.compose.ui.graphics.Color(0xFF242424),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF7F7F7), surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEFEFEF),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF191919), surfaceContainer = androidx.compose.ui.graphics.Color(0xFF242424),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF7F7F7), surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEFEFEF),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF191919), surfaceContainer = androidx.compose.ui.graphics.Color(0xFF242424),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF7F7F7), surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEFEFEF),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF191919), surfaceContainer = androidx.compose.ui.graphics.Color(0xFF242424),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF7F7F7), surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEFEFEF),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF191919), surfaceContainer = androidx.compose.ui.graphics.Color(0xFF242424),
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
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF7F7F7), surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEFEFEF),
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
    dynamicColor: Boolean = false,
    customPrimary: Int? = null,
    content: @Composable () -> Unit
) {
    val scheme = when {
        customPrimary != null -> tintedColorScheme(dark, customPrimary)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        else ->
            colorSchemes[appTheme]?.getColorScheme(dark, isAmoled)
                ?: colorSchemes[AppTheme.BLUE]!!.getColorScheme(dark, isAmoled)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * 整体染色生成器（自定义主色专用，预设主题不经过此函数）：给一个强调色，派生整套同色相色板。
 * 防止"整界面就一个色"的分层策略：
 * · 明度阶梯——背景最暗（暗色 8%→10%），卡片容器五档递进到 25%；亮色从纯白到 89% 浅染，层级靠明度差而不是靠色差
 * · 强度阶梯——背景只带 35% 饱和度的淡淡色相，卡片/选中容器渐浓，强调位最浓；次要文字用色相浅调，正文近白/近黑只微染 10%，保住可读性
 * · tertiary 取色相偏移 +40° 的邻近色，强调位之外再多一层颜色变化
 * · error 槽位保持标准红，不随主题漂移；onX 文字色按强调色亮度自动取黑/白
 */
internal fun tintedColorScheme(dark: Boolean, accent: Int): androidx.compose.material3.ColorScheme {
    val hsv = floatArrayOf(0f, 0f, 0f)
    android.graphics.Color.colorToHSV(accent, hsv)
    val h = hsv[0]
    val s = hsv[1].coerceIn(0f, 1f)
    val h3 = (h + 40f) % 360f
    val lum = 0.299f * ((accent shr 16) and 0xFF) + 0.587f * ((accent shr 8) and 0xFF) + 0.114f * (accent and 0xFF)
    val onAccent = if (lum > 140f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
    fun c(sv: Float, v: Float, hue: Float = h) = androidx.compose.ui.graphics.Color(
        android.graphics.Color.HSVToColor(floatArrayOf(hue, sv.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
    )
    return if (dark) androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(accent), onPrimary = onAccent,
        primaryContainer = c(s, 0.42f), onPrimaryContainer = c(s * 0.55f, 0.92f),
        secondary = c(s * 0.70f, 0.84f), onSecondary = onAccent,
        secondaryContainer = c(s, 0.30f), onSecondaryContainer = c(s * 0.55f, 0.92f),
        tertiary = c(s * 0.55f, 0.80f, h3), onTertiary = onAccent,
        tertiaryContainer = c(s * 0.60f, 0.30f, h3), onTertiaryContainer = c(s * 0.45f, 0.88f, h3),
        error = androidx.compose.ui.graphics.Color(0xFFEF5350), onError = androidx.compose.ui.graphics.Color.Black,
        errorContainer = androidx.compose.ui.graphics.Color(0xFFB71C1C), onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFCDD2),
        background = c(s * 0.35f, 0.10f), onBackground = c(s * 0.10f, 0.90f),
        surface = c(s * 0.30f, 0.14f), onSurface = c(s * 0.10f, 0.90f),
        surfaceVariant = c(s * 0.28f, 0.20f), onSurfaceVariant = c(kotlin.math.min(s, 0.6f) * 0.75f, 0.78f),
        outline = c(s * 0.80f, 0.62f), outlineVariant = c(s * 0.20f, 0.34f),
        scrim = androidx.compose.ui.graphics.Color.Black,
        inverseSurface = c(s * 0.12f, 0.92f), inverseOnSurface = c(s * 0.20f, 0.16f),
        inversePrimary = c(s, 0.50f),
        surfaceDim = c(s * 0.35f, 0.09f), surfaceBright = c(s * 0.25f, 0.24f),
        surfaceContainerLowest = c(s * 0.35f, 0.08f),
        surfaceContainerLow = c(s * 0.30f, 0.13f),
        surfaceContainer = c(s * 0.28f, 0.17f),
        surfaceContainerHigh = c(s * 0.26f, 0.21f),
        surfaceContainerHighest = c(s * 0.24f, 0.25f),
    ) else androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(accent), onPrimary = onAccent,
        primaryContainer = c(s * 0.55f, 0.82f), onPrimaryContainer = c(s * 0.90f, 0.22f),
        secondary = c(s * 0.75f, 0.50f), onSecondary = onAccent,
        secondaryContainer = c(s * 0.45f, 0.90f), onSecondaryContainer = c(s * 0.90f, 0.25f),
        tertiary = c(s * 0.60f, 0.55f, h3), onTertiary = onAccent,
        tertiaryContainer = c(s * 0.40f, 0.88f, h3), onTertiaryContainer = c(s * 0.70f, 0.25f, h3),
        error = androidx.compose.ui.graphics.Color(0xFFD32F2F), onError = androidx.compose.ui.graphics.Color.White,
        errorContainer = androidx.compose.ui.graphics.Color(0xFFFFCDD2), onErrorContainer = androidx.compose.ui.graphics.Color(0xFFB71C1C),
        background = c(s * 0.10f, 0.975f), onBackground = c(s * 0.50f, 0.16f),
        surface = c(s * 0.10f, 0.975f), onSurface = c(s * 0.50f, 0.16f),
        surfaceVariant = c(s * 0.16f, 0.945f), onSurfaceVariant = c(s * 0.65f, 0.34f),
        outline = c(s * 0.70f, 0.45f), outlineVariant = c(s * 0.35f, 0.82f),
        scrim = androidx.compose.ui.graphics.Color.Black,
        inverseSurface = c(s * 0.30f, 0.18f), inverseOnSurface = c(s * 0.10f, 0.95f),
        inversePrimary = c(s * 0.60f, 0.75f),
        surfaceDim = c(s * 0.18f, 0.87f), surfaceBright = c(s * 0.10f, 0.985f),
        surfaceContainerLowest = androidx.compose.ui.graphics.Color.White,
        surfaceContainerLow = c(s * 0.08f, 0.955f),
        surfaceContainer = c(s * 0.10f, 0.935f),
        surfaceContainerHigh = c(s * 0.12f, 0.915f),
        surfaceContainerHighest = c(s * 0.14f, 0.895f),
    )
}

/** 获取指定主题的主色（用于设置页预览） */
fun getThemePrimaryColor(theme: AppTheme, isDark: Boolean): androidx.compose.ui.graphics.Color {
    return colorSchemes[theme]?.getColorScheme(isDark, false)?.primary
        ?: colorSchemes[AppTheme.BLUE]!!.getColorScheme(isDark, false).primary
}
