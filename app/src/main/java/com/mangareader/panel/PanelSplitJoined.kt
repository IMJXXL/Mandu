package com.mangareader.panel

/**
 * 相连框拆分（管线阶段 5，评估报告 §五差距补齐二期）。
 * 检测共享边框的相连面板，沿白色分隔带切分为多个子框，递归拆分深度限制。
 * 纯 JVM 无 Android 依赖。思路出处：PanelsPlus `_smartcomponentdetector.lua` splitJoinedFrames，全新 Kotlin 实现。
 */
internal object PanelSplitJoined {

    /**
     * 对候选框做行/列投影统计墨水量，搜索白色分隔带（投影值≤span×SPLIT_BAND_RATIO）
     * 或黑色共享边框（投影值≥span×SPLIT_BLACK_LINE_RATIO）；找到带后校验两侧内容面积
     * （before/after≥span×SPLIT_CONTENT_RATIO），沿带切分为两个子框，递归拆分子框（深度+1）。
     * 深度≥SPLIT_MAX_DEPTH 或未找到带时返回原框单元素列表。
     * 若提供 gray，先尝试边框线分割（亮度阈值检测窄而密的黑色边框线）。
     */
    internal fun splitJoined(
        mask: BooleanArray,
        w: Int,
        h: Int,
        box: PanelComponents.Box,
        depth: Int = 0,
        gray: IntArray? = null,
        maxDepth: Int = PanelTunables.SPLIT_MAX_DEPTH,
    ): List<PanelComponents.Box> {
        if (depth >= maxDepth) return listOf(box)
        if (box.w < 4 || box.h < 4) return listOf(box)

        if (gray != null) {
            val hBorder = findBorderLineH(gray, w, box)
            if (hBorder != null) {
                val midY = hBorder
                val top = clampBox(box.x0, box.y0, box.x1, midY - 1, box)
                val bottom = clampBox(box.x0, midY + 1, box.x1, box.y1, box)
                if (top.area > 0 && bottom.area > 0) {
                    val a = splitJoined(mask, w, h, top, depth + 1, gray, maxDepth)
                    val b = splitJoined(mask, w, h, bottom, depth + 1, gray, maxDepth)
                    return a + b
                }
            }
            val vBorder = findBorderLineV(gray, w, box)
            if (vBorder != null) {
                val midX = vBorder
                val left = clampBox(box.x0, box.y0, midX - 1, box.y1, box)
                val right = clampBox(midX + 1, box.y0, box.x1, box.y1, box)
                if (left.area > 0 && right.area > 0) {
                    val a = splitJoined(mask, w, h, left, depth + 1, gray, maxDepth)
                    val b = splitJoined(mask, w, h, right, depth + 1, gray, maxDepth)
                    return a + b
                }
            }
        }

        val hSplit = findHorizontalBand(mask, w, h, box)
        if (hSplit != null) {
            val (midY, bandH) = hSplit
            val top = clampBox(box.x0, box.y0, box.x1, midY - 1, box)
            val bottom = clampBox(box.x0, midY + bandH, box.x1, box.y1, box)
            if (top.area > 0 && bottom.area > 0) {
                val a = splitJoined(mask, w, h, top, depth + 1, gray, maxDepth)
                val b = splitJoined(mask, w, h, bottom, depth + 1, gray, maxDepth)
                return a + b
            }
        }

        val vSplit = findVerticalBand(mask, w, h, box)
        if (vSplit != null) {
            val (midX, bandW) = vSplit
            val left = clampBox(box.x0, box.y0, midX - 1, box.y1, box)
            val right = clampBox(midX + bandW, box.y0, box.x1, box.y1, box)
            if (left.area > 0 && right.area > 0) {
                val a = splitJoined(mask, w, h, left, depth + 1, gray, maxDepth)
                val b = splitJoined(mask, w, h, right, depth + 1, gray, maxDepth)
                return a + b
            }
        }

        return listOf(box)
    }

    /** 搜索水平白色分隔带（按行投影）。返回 (midY, bandH) 或 null。 */
    private fun findHorizontalBand(mask: BooleanArray, w: Int, h: Int, box: PanelComponents.Box): Pair<Int, Int>? {
        val span = box.w
        val proj = IntArray(box.h)
        for (y in 0 until box.h) {
            var cnt = 0
            val row = (box.y0 + y) * w
            for (x in box.x0..box.x1) if (mask[row + x]) cnt++
            proj[y] = cnt
        }
        return findBandInProjection(proj, span, box.h)
    }

    /** 搜索垂直白色分隔带（按列投影）。返回 (midX, bandW) 或 null。 */
    private fun findVerticalBand(mask: BooleanArray, w: Int, h: Int, box: PanelComponents.Box): Pair<Int, Int>? {
        val span = box.h
        val proj = IntArray(box.w)
        for (x in 0 until box.w) {
            var cnt = 0
            val col = box.x0 + x
            for (y in box.y0..box.y1) if (mask[y * w + col]) cnt++
            proj[x] = cnt
        }
        return findBandInProjection(proj, span, box.w)
    }

    /**
     * 在投影数组中找白色分隔带或黑色共享边框。
     * @param proj 投影值数组（每行/列的墨水量）
     * @param span 投影跨度（用于计算阈值，即另一维的长度）
     * @param len 投影数组长度（即当前维的长度）
     * @return (mid, bandLen) 相对于 box 内坐标，或 null
     */
    private fun findBandInProjection(proj: IntArray, span: Int, len: Int): Pair<Int, Int>? {
        if (len < 8) return null
        val whiteThr = (span * PanelTunables.SPLIT_BAND_RATIO).toInt()
        val blackThr = (span * PanelTunables.SPLIT_BLACK_LINE_RATIO).toInt()
        val contentMin = (span * PanelTunables.SPLIT_CONTENT_RATIO).toInt()
        val edgeMargin = (len * PanelTunables.SPLIT_EDGE_MARGIN).toInt()

        var bestMid = -1
        var bestBandLen = 0
        var i = 2
        while (i < len - 2) {
            val isWhite = proj[i] <= whiteThr
            val isBlack = proj[i] >= blackThr
            if (isWhite || isBlack) {
                var j = i
                while (j < len - 2 && ((proj[j] <= whiteThr && isWhite) || (proj[j] >= blackThr && isBlack))) j++
                val bandLen = j - i
                if (bandLen in 1..(len / 3)) {
                    val mid = (i + j) / 2
                    if (mid >= edgeMargin && mid < len - edgeMargin) {
                        var before = 0
                        for (k in 0 until i) before += proj[k]
                        var after = 0
                        for (k in j until len) after += proj[k]
                        if (before >= contentMin && after >= contentMin) {
                            if (bandLen > bestBandLen) {
                                bestMid = mid
                                bestBandLen = bandLen
                            }
                        }
                    }
                }
                i = j + 1
            } else {
                i++
            }
        }
        return if (bestMid >= 0) Pair(bestMid, bestBandLen) else null
    }

    /** 钳制子框到原框边界内，避免浮点误差越界。 */
    private fun clampBox(x0: Int, y0: Int, x1: Int, y1: Int, parent: PanelComponents.Box): PanelComponents.Box {
        return PanelComponents.Box(
            x0.coerceIn(parent.x0, parent.x1),
            y0.coerceIn(parent.y0, parent.y1),
            x1.coerceIn(parent.x0, parent.x1),
            y1.coerceIn(parent.y0, parent.y1),
        )
    }

    /**
     * 水平边框线检测：逐行统计亮度 < SEGMENT_BORDER_LUMINANCE_MAX 的像素数，
     * 找窄而密的黑色边框线（投影≥span×SEGMENT_BORDER_LINE_RATIO，线宽≤h×5%）。
     * @return 边框线中心 y 坐标（绝对），或 null
     */
    private fun findBorderLineH(gray: IntArray, w: Int, box: PanelComponents.Box): Int? {
        val span = box.w
        val threshold = (span * PanelTunables.SEGMENT_BORDER_LINE_RATIO).toInt()
        val maxLineWidth = maxOf(2, (box.h * 0.05f).toInt())
        val edgeMargin = (box.h * PanelTunables.SPLIT_EDGE_MARGIN).toInt()
        val lumMax = PanelTunables.SEGMENT_BORDER_LUMINANCE_MAX

        var bestCenter = -1
        var i = box.y0 + edgeMargin
        while (i <= box.y1 - edgeMargin) {
            var cnt = 0
            val row = i * w
            for (x in box.x0..box.x1) if (gray[row + x] < lumMax) cnt++
            if (cnt >= threshold) {
                var j = i
                while (j <= box.y1 - edgeMargin && j <= i + maxLineWidth) {
                    var c = 0
                    val r = j * w
                    for (x in box.x0..box.x1) if (gray[r + x] < lumMax) c++
                    if (c >= threshold) j++ else break
                }
                val center = (i + j - 1) / 2
                if (center > bestCenter) bestCenter = center
                i = j + 1
            } else {
                i++
            }
        }
        return if (bestCenter >= 0) bestCenter else null
    }

    /**
     * 垂直边框线检测：逐列统计亮度 < SEGMENT_BORDER_LUMINANCE_MAX 的像素数，
     * 找窄而密的黑色边框线。
     * @return 边框线中心 x 坐标（绝对），或 null
     */
    private fun findBorderLineV(gray: IntArray, w: Int, box: PanelComponents.Box): Int? {
        val span = box.h
        val threshold = (span * PanelTunables.SEGMENT_BORDER_LINE_RATIO).toInt()
        val maxLineWidth = maxOf(2, (box.w * 0.05f).toInt())
        val edgeMargin = (box.w * PanelTunables.SPLIT_EDGE_MARGIN).toInt()
        val lumMax = PanelTunables.SEGMENT_BORDER_LUMINANCE_MAX

        var bestCenter = -1
        var i = box.x0 + edgeMargin
        while (i <= box.x1 - edgeMargin) {
            var cnt = 0
            for (y in box.y0..box.y1) if (gray[y * w + i] < lumMax) cnt++
            if (cnt >= threshold) {
                var j = i
                while (j <= box.x1 - edgeMargin && j <= i + maxLineWidth) {
                    var c = 0
                    for (y in box.y0..box.y1) if (gray[y * w + j] < lumMax) c++
                    if (c >= threshold) j++ else break
                }
                val center = (i + j - 1) / 2
                if (center > bestCenter) bestCenter = center
                i = j + 1
            } else {
                i++
            }
        }
        return if (bestCenter >= 0) bestCenter else null
    }
}