package com.mangareader.ui

import kotlin.math.abs

/**
 * 标签 12 色系统（design.md §六）：
 * - "自动"档：按标签名 hash 取槽位，渲染时按主题取 tone40（浅色）/tone80（深色）——不落库
 * - "手动"档：改色时把具体 ARGB 写进 tags.color（按当时主题档写死）
 * - 判别：tags.color 等于 Tag 默认值 AUTO_SENTINEL 即视为自动档
 */
object TagColors {

    /** Tag 实体默认色 = 自动档哨兵（避免加列，tags 表零迁移） */
    const val AUTO_SENTINEL: Long = 0xFF7C8CF8L

    // 12 色板（Material tone40 / tone80 同 hue 双档）
    private val LIGHT = longArrayOf(
        0xFFC62828, 0xFFAD1457, 0xFF6A1B9A, 0xFF1565C0, 0xFF00838F, 0xFF2E7D32,
        0xFF558B2F, 0xFFEF6C00, 0xFF6D4C41, 0xFF546E7A, 0xFF9E7C0C, 0xFF616161
    )
    private val DARK = longArrayOf(
        0xFFEF9A9A, 0xFFF48FB1, 0xFFCE93D8, 0xFF90CAF9, 0xFF80DEEA, 0xFFA5D6A7,
        0xFFC5E1A5, 0xFFFFCC80, 0xFFBCAAA4, 0xFFB0BEC5, 0xFFE6C76A, 0xFFBDBDBD
    )

    fun palette(darkTheme: Boolean): LongArray = if (darkTheme) DARK else LIGHT

    /** 名字 hash → 槽位（floorMod 对负 hash 也均匀分布） */
    fun slotOf(name: String): Int = Math.floorMod(name.hashCode(), LIGHT.size)

    fun autoColor(name: String, darkTheme: Boolean): Long =
        (if (darkTheme) DARK else LIGHT)[slotOf(name)]

    /** UI 渲染统一入口：自动档现算，手动档原样 */
    fun resolve(name: String, storedColor: Long, darkTheme: Boolean): Long =
        if (storedColor == AUTO_SENTINEL) autoColor(name, darkTheme) else storedColor

    /**
     * 对比度兜底（手动色可能和主题背景太接近）：与当前主题 surface 的相对亮度差过小时
     * 返回 true，调用方给色点描 1dp 轮廓。阈值 0.18（装饰性色点，够用即可）。
     */
    fun needsOutline(storedColor: Long, darkTheme: Boolean): Boolean {
        val surface = if (darkTheme) 0xFF141218 else 0xFFFEF7FF
        val y1 = relativeLuminance(storedColor)
        val y2 = relativeLuminance(surface)
        return abs(y1 - y2) < 0.18
    }

    private fun relativeLuminance(argb: Long): Double {
        fun ch(v: Long): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = ch((argb shr 16) and 0xFF)
        val g = ch((argb shr 8) and 0xFF)
        val b = ch(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
