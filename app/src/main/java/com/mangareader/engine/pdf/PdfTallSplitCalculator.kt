package com.mangareader.engine.pdf

import kotlin.math.ceil

/**
 * PDF / 长图通用分割几何（纯 Kotlin，零 Android 依赖，可 JVM 单测）。
 *
 * ★ A1 均分语义（2026-09-25）：段矩形不再按 splitH 硬切，而是先算段数 n，再把整页
 *   均分为 n 段。修复两个旧算法缺陷：
 *   ① 尾段薄片——页高刚过 n×splitH 时旧算法切出极矮末段；
 *   ② 封顶截断——MAX_SEGMENTS 封顶后旧算法仍按原 splitH 切，超过 64×splitH 的页尾内容丢失。
 *   展开器（VirtualPageExpander）与渲染器（ArchiveEngine/ImageSegmentRenderer）都经由
 *   本对象取段几何，单一事实来源，永不分叉。
 */
object PdfTallSplitCalculator {
    const val TALL_RATIO_THRESHOLD = 3f
    const val MAX_SEGMENTS = 64

    /** 纯 Kotlin 段矩形（页面坐标系），替代 android.graphics.RectF 使几何层可 JVM 测试 */
    data class SplitRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    /**
     * ★ 段高 = 2 屏（2026-09-26 回退 889a49d 的 ×1）：×2 时一段≈一个原漫画页（实测 01.pdf
     *   expand 出 35 段=真实页数；×1 会把每页切成两半→69 页，页数语义错误且预载屏覆盖减半）。
     *   889a49d 的「渲染端几何错位」诊断有误——expand 与渲染端 n 实际一致（15:13/15:27 日志
     *   pdfSegOK 尺寸反推证实），页码回跳/恢复位丢失的真根因在显示层（updatePage 边界规则/
     *   锚点登记竞争），已由中心线判定+锚点落位确认独立修复，与段高无关。
     */
    fun optimalSplitHeightPx(screenMax: Int): Float =
        if (screenMax <= 0) 4096f else screenMax * 2f

    fun splitHeightPdf(screenMax: Int, screenWidth: Int, pageWidth: Float): Float {
        if (screenWidth <= 0 || pageWidth <= 0f) return optimalSplitHeightPx(screenMax)
        return optimalSplitHeightPx(screenMax) * pageWidth / screenWidth.toFloat()
    }

    fun isTallPage(pageWidth: Float, pageHeight: Float, splitHeightPdf: Float): Boolean {
        if (pageWidth <= 0f) return false
        return pageHeight / pageWidth > TALL_RATIO_THRESHOLD && pageHeight > splitHeightPdf
    }

    fun segmentCount(pageHeight: Float, splitHeightPdf: Float): Int {
        if (splitHeightPdf <= 0f || pageHeight <= 0f) return 1
        return ceil(pageHeight / splitHeightPdf).toInt().coerceIn(1, MAX_SEGMENTS)
    }

    /**
     * ★ 均分：n = segmentCount(pageHeight, splitHeightPdf)，段 i（1 基）覆盖
     *   [H×(i-1)/n, H×i/n] —— 全段等高、无缝无叠、联合恰好覆盖整页。
     *   segmentIndex 越界时钳制到 [1, n]（陈旧几何请求落末段而非越界）。
     */
    fun segmentRect(pageWidth: Float, pageHeight: Float, splitHeightPdf: Float, segmentIndex: Int): SplitRect {
        val n = segmentCount(pageHeight, splitHeightPdf)
        return segmentRectByCount(pageWidth, pageHeight, n, segmentIndex)
    }

    /**
     * ★ 按段数直接均分（2026-09-26）：渲染端不再自行重算 splitH/n——expand 规划段数时
     *   用的 screenMax/screenWidth 与渲染端任一时刻的 displayMetrics 可能不同（Pad 分屏/
     *   任务栏/多窗口），重算会得到不同的 n → 渲染切出的段与 expand 规划的段错位（段边界
     *   内容重复/缺失=「乱」，段位图超高挤爆位图缓存=「糊」）。段页 meta.segmentTotal 就是
     *   expand 的权威 n，渲染端直接用它均分——单一事实来源。
     */
    fun segmentRectByCount(pageWidth: Float, pageHeight: Float, segmentTotal: Int, segmentIndex: Int): SplitRect {
        val n = segmentTotal.coerceIn(1, MAX_SEGMENTS)
        val i = segmentIndex.coerceIn(1, n)
        val y0 = pageHeight * (i - 1) / n
        val y1 = pageHeight * i / n
        return SplitRect(0f, y0, pageWidth, y1)
    }
}
