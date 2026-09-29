package com.mangareader.ui.theme

import com.mangareader.ui.tintedColorScheme

object MonochromeColorScheme : BaseColorScheme() {
    // 整体染色：主色为灰 → 生成纯中性灰度阶梯，保持单色概念
    override val darkScheme = tintedColorScheme(dark = true, accent = 0xFFBDBDBD.toInt())
    override val lightScheme = tintedColorScheme(dark = false, accent = 0xFF000000.toInt())
}
