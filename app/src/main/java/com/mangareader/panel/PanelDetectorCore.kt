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
        bubbleMerge: Boolean = false,
        engineMode: Int = 0,
    ): PanelPageResult {
        if (w <= 0 || h <= 0 || gray.size != w * h) return fallbackResult()
        if (engineMode == 0) {
            val mask = PanelBinarize.buildInkMask(gray, w, h)
            val closed = PanelBinarize.close(mask, w, h, PanelBinarize.closeKernelSize(w, h))
            val rects = PanelComponents.toPanelRects(closed, w, h, engineMode = 0)
            if (rects.isEmpty()) return fallbackResult()
            val ordered = PanelOrder.sortReadingOrder(rects, rtl)
            return PanelConfidence.evaluate(ordered, w, h, rtl, confFail, fullPageArea = PanelTunables.FULL_PAGE_AREA_SELF, engineMode = 0)
        }
        val pp = PanelTunables.precisionParams(precision)
        val mask = PanelBinarize.buildInkMask(gray, w, h, pp.bgSampleRatio, rgb)
        val closed = PanelBinarize.close(mask, w, h, PanelBinarize.closeKernelSize(w, h))
        val rawPanels = PanelXyCut.segment(closed, w, h)
        if (rawPanels.isEmpty()) return fallbackResult()
        if (!PanelSegmenter.accept(rawPanels, w, h, closed)) return fallbackResult()
        val ordered = PanelOrder.sortReadingOrder(rawPanels, rtl)
        val normalized = ordered.mapIndexed { i, b ->
            PanelBox(
                x = b.x0 / w.toFloat(),
                y = b.y0 / h.toFloat(),
                w = b.w / w.toFloat(),
                h = b.h / h.toFloat(),
                order = i,
                conf = 1f,
            )
        }
        var result = PanelPageResult(normalized, 1f, fallback = false)
        if (bubbleMerge && result.boxes.size >= 2) {
            result = result.copy(boxes = bubbleMergePass(result.boxes, closed, w, h))
        }
        return result
    }

    /**
     * 跨格气泡合并 Safe 策略：检查相邻分格边界处的墨水密度，
     * 若边界处有显著内容跨越（非空白分隔带），标记为同组。
     * 保守策略：仅合并阅读序相邻且空间相邻的分格。
     */
    private fun bubbleMergePass(
        boxes: List<PanelBox>,
        mask: BooleanArray,
        w: Int,
        h: Int,
    ): List<PanelBox> {
        val groups = IntArray(boxes.size)
        var nextGroup = 1
        for (i in 0 until boxes.size - 1) {
            val a = boxes[i]
            val b = boxes[i + 1]
            if (contentSpansBoundary(a, b, mask, w, h)) {
                val ga = groups[i]
                val gb = groups[i + 1]
                if (ga == 0 && gb == 0) {
                    groups[i] = nextGroup
                    groups[i + 1] = nextGroup
                    nextGroup++
                } else if (ga != 0) groups[i + 1] = ga
                else groups[i] = gb
            }
        }
        return boxes.mapIndexed { idx, box ->
            if (groups[idx] != 0) box.copy(group = groups[idx]) else box
        }
    }

    /**
     * 检查两个分格边界处是否有内容跨越（墨水密度高）。
     * 仅检查水平相邻（左右）和垂直相邻（上下）的情况。
     */
    private fun contentSpansBoundary(a: PanelBox, b: PanelBox, mask: BooleanArray, w: Int, h: Int): Boolean {
        val ax0 = (a.x * w).toInt().coerceIn(0, w - 1)
        val ay0 = (a.y * h).toInt().coerceIn(0, h - 1)
        val ax1 = ((a.x + a.w) * w).toInt().coerceIn(0, w - 1)
        val ay1 = ((a.y + a.h) * h).toInt().coerceIn(0, h - 1)
        val bx0 = (b.x * w).toInt().coerceIn(0, w - 1)
        val by0 = (b.y * h).toInt().coerceIn(0, h - 1)
        val bx1 = ((b.x + b.w) * w).toInt().coerceIn(0, w - 1)
        val by1 = ((b.y + b.h) * h).toInt().coerceIn(0, h - 1)

        val hGap = (bx0 - ax1).let { if (b.x < a.x) (ax0 - bx1) else it }
        val vGap = (by0 - ay1).let { if (b.y < a.y) (ay0 - by1) else it }
        val maxGap = (maxOf(w, h) * 0.02f).toInt()

        if (hGap in 0..maxGap && vOverlap(ay0, ay1, by0, by1) > 0) {
            val xStart = minOf(ax1, bx1)
            val xEnd = maxOf(ax0, bx0)
            val yMin = maxOf(ay0, by0)
            val yMax = minOf(ay1, by1)
            if (xEnd > xStart && yMax > yMin) {
                var ink = 0
                var total = 0
                for (y in yMin..yMax) {
                    val row = y * w
                    for (x in xStart..xEnd) {
                        total++
                        if (mask[row + x]) ink++
                    }
                }
                if (total > 0 && ink.toFloat() / total > 0.3f) return true
            }
        }
        if (vGap in 0..maxGap && hOverlap(ax0, ax1, bx0, bx1) > 0) {
            val yStart = minOf(ay1, by1)
            val yEnd = maxOf(ay0, by0)
            val xMin = maxOf(ax0, bx0)
            val xMax = minOf(ax1, bx1)
            if (yEnd > yStart && xMax > xMin) {
                var ink = 0
                var total = 0
                for (y in yStart..yEnd) {
                    val row = y * w
                    for (x in xMin..xMax) {
                        total++
                        if (mask[row + x]) ink++
                    }
                }
                if (total > 0 && ink.toFloat() / total > 0.3f) return true
            }
        }
        return false
    }

    private fun vOverlap(ay0: Int, ay1: Int, by0: Int, by1: Int): Int =
        maxOf(0, minOf(ay1, by1) - maxOf(ay0, by0))

    private fun hOverlap(ax0: Int, ax1: Int, bx0: Int, bx1: Int): Int =
        maxOf(0, minOf(ax1, bx1) - maxOf(ax0, bx0))
}
