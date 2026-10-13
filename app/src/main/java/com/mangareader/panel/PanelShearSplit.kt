package com.mangareader.panel

import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 倾斜分隔线切分（管线阶段 6，评估报告 §五差距补齐二期）。
 * 标准切分失败时尝试 ±2/±4/±6/±8 度倾斜剪切，在剪切变换后坐标系找空白带切分。
 * 纯 JVM 无 Android 依赖。思路出处：PanelsPlus `_segmenter.lua` projectColumnsSheared/projectRowsSheared/trySlope，全新 Kotlin 实现。
 */
internal object PanelShearSplit {

    /**
     * 对 SHEAR_ANGLES 每个候选角度做剪切变换 x'=x-slope×y（列投影）或 y'=y-slope×x（行投影），
     * 在变换后坐标系找空白带（投影值≤阈值），找到后按倾斜线切分为子框，
     * 校验子框面积（≥候选框面积×SHEAR_CHILD_AREA_MIN）。任一子框无效则回退尝试下一角度，
     * 全部角度均未找到则返回原框单元素列表。
     */
    internal fun tryShear(mask: BooleanArray, w: Int, h: Int, box: PanelComponents.Box): List<PanelComponents.Box> {
        val boxArea = box.area.toFloat()
        if (boxArea <= 0) return listOf(box)

        for (angle in PanelTunables.SHEAR_ANGLES) {
            val slope = tan(Math.toRadians(angle.toDouble())).toFloat()
            if (!slope.isFinite() || slope == 0f) continue

            val vResult = tryShearVertical(mask, w, h, box, slope, boxArea)
            if (vResult != null) return vResult

            val hResult = tryShearHorizontal(mask, w, h, box, slope, boxArea)
            if (hResult != null) return hResult
        }
        return listOf(box)
    }

    /** 列投影剪切：x'=x-slope×y，找垂直空白带按倾斜线切分左右。 */
    private fun tryShearVertical(
        mask: BooleanArray,
        w: Int,
        h: Int,
        box: PanelComponents.Box,
        slope: Float,
        boxArea: Float,
    ): List<PanelComponents.Box>? {
        val boxW = box.w
        val boxH = box.h
        if (boxW < 8 || boxH < 8) return null

        val xMin = box.x0
        val xMax = box.x1
        val yMin = box.y0
        val yMax = box.y1

        val shearXMin = xMin - slope * yMax
        val shearXMax = xMax - slope * yMin
        val projMin = minOf(shearXMin, xMin - slope * yMin)
        val projMax = maxOf(shearXMax, xMax - slope * yMax)
        val projRange = (projMax - projMin).roundToInt()
        if (projRange <= 0 || projRange > 4 * boxW) return null

        val proj = IntArray(projRange + 1)
        for (y in yMin..yMax) {
            val row = y * w
            val offset = -slope * y - projMin
            for (x in xMin..xMax) {
                if (mask[row + x]) {
                    val sx = (x + offset).roundToInt()
                    if (sx in proj.indices) proj[sx]++
                }
            }
        }

        val band = findShearedBand(proj, boxH, projRange + 1) ?: return null
        val (midProj, _) = band

        val midXatY0 = (midProj + slope * yMin).roundToInt()
        val midXatY1 = (midProj + slope * yMax).roundToInt()

        val left = clampBox(box.x0, box.y0, minOf(midXatY0, midXatY1) - 1, box.y1, box)
        val right = clampBox(maxOf(midXatY0, midXatY1) + 1, box.y0, box.x1, box.y1, box)
        if (left.area <= 0 || right.area <= 0) return null
        if (left.area < boxArea * PanelTunables.SHEAR_CHILD_AREA_MIN) return null
        if (right.area < boxArea * PanelTunables.SHEAR_CHILD_AREA_MIN) return null
        return listOf(left, right)
    }

    /** 行投影剪切：y'=y-slope×x，找水平空白带按倾斜线切分上下。 */
    private fun tryShearHorizontal(
        mask: BooleanArray,
        w: Int,
        h: Int,
        box: PanelComponents.Box,
        slope: Float,
        boxArea: Float,
    ): List<PanelComponents.Box>? {
        val boxW = box.w
        val boxH = box.h
        if (boxW < 8 || boxH < 8) return null

        val xMin = box.x0
        val xMax = box.x1
        val yMin = box.y0
        val yMax = box.y1

        val shearYMin = yMin - slope * xMax
        val shearYMax = yMax - slope * xMin
        val projMin = minOf(shearYMin, yMin - slope * xMin)
        val projMax = maxOf(shearYMax, yMax - slope * xMax)
        val projRange = (projMax - projMin).roundToInt()
        if (projRange <= 0 || projRange > 4 * boxH) return null

        val proj = IntArray(projRange + 1)
        for (x in xMin..xMax) {
            val offset = -slope * x - projMin
            for (y in yMin..yMax) {
                if (mask[y * w + x]) {
                    val sy = (y + offset).roundToInt()
                    if (sy in proj.indices) proj[sy]++
                }
            }
        }

        val band = findShearedBand(proj, boxW, projRange + 1) ?: return null
        val (midProj, _) = band

        val midYatX0 = (midProj + slope * xMin).roundToInt()
        val midYatX1 = (midProj + slope * xMax).roundToInt()

        val top = clampBox(box.x0, box.y0, box.x1, minOf(midYatX0, midYatX1) - 1, box)
        val bottom = clampBox(box.x0, maxOf(midYatX0, midYatX1) + 1, box.x1, box.y1, box)
        if (top.area <= 0 || bottom.area <= 0) return null
        if (top.area < boxArea * PanelTunables.SHEAR_CHILD_AREA_MIN) return null
        if (bottom.area < boxArea * PanelTunables.SHEAR_CHILD_AREA_MIN) return null
        return listOf(top, bottom)
    }

    /** 在剪切投影中找空白带：投影值≤span×SHEAR_BAND_RATIO，两侧有内容。返回 (mid, bandLen) 或 null。 */
    private fun findShearedBand(proj: IntArray, span: Int, len: Int): Pair<Int, Int>? {
        if (len < 8) return null
        val whiteThr = (span * PanelTunables.SHEAR_BAND_RATIO).toInt()
        val contentMin = (span * PanelTunables.SPLIT_CONTENT_RATIO).toInt()

        var i = 2
        while (i < len - 2) {
            if (proj[i] <= whiteThr) {
                var j = i
                while (j < len - 2 && proj[j] <= whiteThr) j++
                val bandLen = j - i
                if (bandLen in 1..(len / 3)) {
                    val mid = (i + j) / 2
                    if (mid > 1 && mid < len - 1) {
                        var before = 0
                        for (k in 0 until i) before += proj[k]
                        var after = 0
                        for (k in j until len) after += proj[k]
                        if (before >= contentMin && after >= contentMin) {
                            return Pair(mid, bandLen)
                        }
                    }
                }
                i = j + 1
            } else {
                i++
            }
        }
        return null
    }

    private fun clampBox(x0: Int, y0: Int, x1: Int, y1: Int, parent: PanelComponents.Box): PanelComponents.Box {
        return PanelComponents.Box(
            x0.coerceIn(parent.x0, parent.x1),
            y0.coerceIn(parent.y0, parent.y1),
            x1.coerceIn(parent.x0, parent.x1),
            y1.coerceIn(parent.y0, parent.y1),
        )
    }
}