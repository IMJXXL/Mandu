package com.mangareader.ui.theme

import com.mangareader.ui.tintedColorScheme

object TakoColorScheme : BaseColorScheme() {
    // 整体染色：由原主色派生全套同色相色板
    override val darkScheme = tintedColorScheme(dark = true, accent = 0xFFBB86FC.toInt())
    override val lightScheme = tintedColorScheme(dark = false, accent = 0xFF6750A4.toInt())
}
