package com.mangareader.ui.theme

import com.mangareader.ui.tintedColorScheme

object YotsubaColorScheme : BaseColorScheme() {
    // 整体染色：由原主色派生全套同色相色板
    override val darkScheme = tintedColorScheme(dark = true, accent = 0xFFFFB74D.toInt())
    override val lightScheme = tintedColorScheme(dark = false, accent = 0xFFE65100.toInt())
}
