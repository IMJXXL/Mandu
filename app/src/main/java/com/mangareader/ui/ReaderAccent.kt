package com.mangareader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color

/**
 * ★ 快赢 #2（2026-10-11 报告 §一#2/§六F5）：阅读器 chrome 强调色跟随主题，带可读性兜底。
 *
 * 阅读器工具条的底色是"阅读背景色"而非主题表面色——主题 primary 直接压上去，
 * 遇到亮度接近的阅读背景（如浅色主题 primary 压白底/黑底）会看不清。
 * 兜底策略：primary 与底色亮度差过小时，按底色亮度取黑/白（与 textColor 同一推导公式）。
 * 未走主题的场景（AMOLED 下 primary 本就适配暗底）亮度差天然拉开，行为不变。
 */
@androidx.compose.runtime.Composable
fun readerAccentColor(readingBgColor: Color): Color {
    val primary = MaterialTheme.colorScheme.primary
    val bgLum = readingBgColor.red * 0.299f + readingBgColor.green * 0.587f + readingBgColor.blue * 0.114f
    val pLum = primary.red * 0.299f + primary.green * 0.587f + primary.blue * 0.114f
    return if (kotlin.math.abs(pLum - bgLum) < 0.2f) {
        if (bgLum > 0.5f) Color(0xFF1A1A1A) else Color.White
    } else {
        primary
    }
}
