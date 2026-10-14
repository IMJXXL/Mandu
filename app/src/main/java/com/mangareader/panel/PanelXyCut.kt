package com.mangareader.panel

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object PanelXyCut {

    private class Ctx(
        val w: Int,
        val h: Int,
        val inkRatio: Float,
        val minGutter: Int,
        val minSide: Int,
        val minArea: Int,
        val sliverAspect: Int,
        val sliverInk: Int,
        val maxDepth: Int,
        val maxPanels: Int,
        val mode: String,
    ) {
        val rows = IntArray(h)
        val cols = IntArray(w)
    }

    private class Cell(val x: Int, val y: Int, val w: Int, val h: Int)

    internal fun segment(mask: BooleanArray, w: Int, h: Int, mode: String = "manga"): List<PanelComponents.Box> {
        if (w <= 0 || h <= 0 || mask.size != w * h) return emptyList()
        var totalInk = 0
        for (i in mask.indices) if (mask[i]) totalInk++

        val minDim = min(w, h)
        val inkRatio = if (mode == "manga") 0.04f else PanelTunables.SEGMENT_GUTTER_INK_RATIO
        val minGutter = if (mode == "comic") 1 else max(2, (minDim * PanelTunables.SEGMENT_GUTTER_RATIO).toInt())
        val minSide = max(4, (minDim * PanelTunables.SEGMENT_MIN_PANEL_SIDE).toInt())
        val minArea = (w.toFloat() * h * PanelTunables.SEGMENT_MIN_PANEL_AREA).toInt()
        val sliverInk = (totalInk.toFloat() * PanelTunables.SEGMENT_SLIVER_INK).toInt()

        val ctx = Ctx(
            w, h, inkRatio, minGutter, minSide, minArea,
            PanelTunables.SEGMENT_SLIVER_ASPECT, sliverInk,
            PanelTunables.SPLIT_MAX_DEPTH, PanelTunables.SEGMENT_MAX_PANELS, mode,
        )

        val cells = mutableListOf<Cell>()
        cut(mask, 0, 0, w - 1, h - 1, 0, ctx, cells)

        return cells.map { c ->
            val x = max(0, c.x - 1)
            val y = max(0, c.y - 1)
            val r = min(w - 1, c.x + c.w)
            val b = min(h - 1, c.y + c.h)
            PanelComponents.Box(x, y, r, b)
        }
    }

    private fun cut(mask: BooleanArray, x0: Int, y0: Int, x1: Int, y1: Int, depth: Int, ctx: Ctx, out: MutableList<Cell>) {
        if (x1 < x0 || y1 < y0 || out.size >= ctx.maxPanels) return

        project(mask, x0, y0, x1, y1, ctx.rows, ctx.cols, ctx.w)
        val top = trimRange(ctx.rows, y0, y1, true)
        val bottom = trimRange(ctx.rows, y0, y1, false)
        val left = trimRange(ctx.cols, x0, x1, true)
        val right = trimRange(ctx.cols, x0, x1, false)
        if (bottom < top || right < left) return

        var regionInk = 0
        for (y in top..bottom) regionInk += ctx.rows[y]

        if (depth < ctx.maxDepth) {
            val width = right - left + 1
            val height = bottom - top + 1
            val avgRow = if (height > 0) regionInk.toFloat() / height else 0f
            val avgCol = if (width > 0) regionInk.toFloat() / width else 0f

            val (rowStart, rowStop, rowLen) = findWidestGutter(ctx.rows, top, bottom, width, ctx.inkRatio, ctx.minGutter, avgRow, false)
            val (colStart, colStop, colLen) = findWidestGutter(ctx.cols, left, right, height, ctx.inkRatio, ctx.minGutter, avgCol, true)

            if (rowLen > 0 && rowLen >= colLen) {
                cut(mask, left, top, right, rowStart!! - 1, depth + 1, ctx, out)
                cut(mask, left, rowStop!! + 1, right, bottom, depth + 1, ctx, out)
                return
            } else if (colLen > 0) {
                cut(mask, left, top, colStart!! - 1, bottom, depth + 1, ctx, out)
                cut(mask, colStop!! + 1, top, right, bottom, depth + 1, ctx, out)
                return
            }

            if (ctx.mode == "manga") {
                val kSplit = find4KomaCenterlineSplit(mask, left, top, right, bottom, ctx.cols, ctx.w, ctx.h)
                if (kSplit != null) {
                    cut(mask, left, top, kSplit - 1, bottom, depth + 1, ctx, out)
                    cut(mask, kSplit + 1, top, right, bottom, depth + 1, ctx, out)
                    return
                }
            }
        }

        emitLeaf(left, top, right, bottom, regionInk, ctx, out)
    }

    private fun project(mask: BooleanArray, x0: Int, y0: Int, x1: Int, y1: Int, rows: IntArray, cols: IntArray, w: Int) {
        for (x in x0..x1) cols[x] = 0
        for (y in y0..y1) {
            val base = y * w
            var count = 0
            for (x in x0..x1) {
                if (mask[base + x]) {
                    count++
                    cols[x]++
                }
            }
            rows[y] = count
        }
    }

    private fun trimRange(projection: IntArray, from: Int, to: Int, findStart: Boolean): Int {
        if (findStart) {
            var f = from
            while (f <= to && projection[f] == 0) f++
            return f
        } else {
            var t = to
            while (t >= from && projection[t] == 0) t--
            return t
        }
    }

    private fun findWidestGutter(
        projection: IntArray, from: Int, to: Int, span: Int,
        inkRatio: Float, minLength: Int, avgInk: Float, isColumn: Boolean,
    ): Triple<Int?, Int?, Int> {
        var maxInk = span * inkRatio
        val valleyCap = if (isColumn) 0.03f else 0.05f
        val valleyRatio = if (isColumn) 0.10f else 0.15f
        if (avgInk > 0) {
            val valleyMax = min(span * valleyCap, avgInk * valleyRatio)
            if (valleyMax > maxInk) maxInk = valleyMax
        }

        var bestStart: Int? = null
        var bestStop: Int? = null
        var bestLength = 0
        var bestScore = 0
        var runStart: Int? = null

        for (index in from..to) {
            if (projection[index] <= maxInk) {
                if (runStart == null) runStart = index
            } else {
                val rs = runStart
                if (rs != null && rs > from) {
                    val length = index - rs
                    val isClean = projection[rs] <= span * 0.015f
                    val maxAllowedLen = if (isClean) Int.MAX_VALUE else 30
                    val score = length + if (isClean) 100 else 0
                    if (length >= minLength && length <= maxAllowedLen &&
                        (length > 1 || projection[rs] == 0) && score > bestScore
                    ) {
                        bestStart = rs
                        bestStop = index - 1
                        bestLength = length
                        bestScore = score
                    }
                }
                runStart = null
            }
        }

        return Triple(bestStart, bestStop, bestLength)
    }

    private fun emitLeaf(x0: Int, y0: Int, x1: Int, y1: Int, ink: Int, ctx: Ctx, out: MutableList<Cell>) {
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w < ctx.minSide || h < ctx.minSide || w * h < ctx.minArea) return

        val longSide: Int
        val shortSide: Int
        if (h > w) { longSide = h; shortSide = w } else { longSide = w; shortSide = h }
        if (longSide >= shortSide * ctx.sliverAspect && ink < ctx.sliverInk) return

        out.add(Cell(x0, y0, w, h))
    }

    private fun minInRange(projection: IntArray, from: Int, to: Int): Int {
        var smallest = Int.MAX_VALUE
        for (i in from..to) if (projection[i] < smallest) smallest = projection[i]
        return smallest
    }

    private fun maxContinuousRun(mask: BooleanArray, x: Int, y0: Int, y1: Int, w: Int): Int {
        var maxRun = 0
        var curRun = 0
        for (y in y0..y1) {
            if (mask[y * w + x]) {
                curRun++
                if (curRun > maxRun) maxRun = curRun
            } else {
                curRun = 0
            }
        }
        return maxRun
    }

    private fun find4KomaCenterlineSplit(
        mask: BooleanArray, left: Int, top: Int, right: Int, bottom: Int,
        cols: IntArray, mapW: Int, mapH: Int,
    ): Int? {
        val width = right - left + 1
        val height = bottom - top + 1

        if (width < mapW * 0.65f || width.toFloat() / height < 1.25f) return null
        if (left > mapW * 0.18f || right < mapW * 0.82f) return null

        val minX = max(left + 15, (mapW * 0.35f).toInt())
        val maxX = min(right - 15, kotlin.math.ceil(mapW * 0.65f).toInt())
        if (minX > maxX) return null

        var bestSplit: Int? = null
        var bestScore = -1f

        for (x in minX..maxX) {
            val valR = cols[x].toFloat() / height
            if (valR <= 0.35f) {
                val leftW = x - left + 1
                val rightW = right - x + 1
                val leftAspect = leftW.toFloat() / height
                val rightAspect = rightW.toFloat() / height

                if (leftAspect <= 1.55f && rightAspect <= 1.55f) {
                    var leftRun = 0f
                    for (dx in 1..6) {
                        val bx = x - dx
                        if (bx >= left && cols[bx].toFloat() / height >= 0.45f) {
                            val run = maxContinuousRun(mask, bx, top, bottom, mapW).toFloat() / height
                            if (run > leftRun) leftRun = run
                        }
                    }

                    var rightRun = 0f
                    for (dx in 1..6) {
                        val bx = x + dx
                        if (bx <= right && cols[bx].toFloat() / height >= 0.45f) {
                            val run = maxContinuousRun(mask, bx, top, bottom, mapW).toFloat() / height
                            if (run > rightRun) rightRun = run
                        }
                    }

                    if ((leftRun >= 0.50f && rightRun >= 0.50f) ||
                        (leftRun >= 0.65f && rightRun >= 0.35f) ||
                        (rightRun >= 0.65f && leftRun >= 0.35f)
                    ) {
                        val score = (leftRun + rightRun) - valR
                        if (score > bestScore) {
                            bestScore = score
                            bestSplit = x
                        }
                    }
                }
            }
        }

        return bestSplit
    }
}