package com.mangareader.ui.theme

import com.mangareader.ui.tintedColorScheme

object YinYangColorScheme : BaseColorScheme() {
    // 整体染色：主色为灰 → 生成纯中性灰度阶梯（暗色纯黑底/亮色纯白底由明度阶梯给出）
    override val darkScheme = tintedColorScheme(dark = true, accent = 0xFFE0E0E0.toInt())
    override val lightScheme = tintedColorScheme(dark = false, accent = 0xFF212121.toInt())
}
