package com.mangareader.panel

/**
 * 逐格检测管线编排（纯 JVM，评估报告 §五冻结稿）。
 * 灰度 IntArray → 二值化(+闭运算) → 连通域框级处理 → 阅读顺序 → 置信度/降级。
 * 参数全部集中在 [PanelTunables]。
 */
object PanelDetectorCore {

    /** 单元测试与调试入口 */
    fun fallbackResult(): PanelPageResult =
        PanelPageResult(listOf(PanelBox(0f, 0f, 1f, 1f, 0, 0f)), 0f, fallback = true)

    fun analyze(
        gray: IntArray,
        w: Int,
        h: Int,
        rtl: Boolean,
        confFail: Float = PanelTunables.CONF_FAIL,
        precision: Int = 1,
        rgb: IntArray? = null,
    ): PanelPageResult {
        if (w <= 0 || h <= 0 || gray.size != w * h) return fallbackResult()
        val pp = PanelTunables.precisionParams(precision)
        val mask = PanelBinarize.buildInkMask(gray, w, h, pp.bgSampleRatio, rgb)
        val closed = PanelBinarize.close(mask, w, h, PanelBinarize.closeKernelSize(w, h))
        val rects = PanelComponents.toPanelRects(closed, w, h, pp.frameTolerance, pp.frameMinSides)
        if (rects.isEmpty()) return fallbackResult()
        val ordered = PanelOrder.sortReadingOrder(rects, rtl)
        return PanelConfidence.evaluate(ordered, w, h, rtl, confFail, closed)
    }
}
