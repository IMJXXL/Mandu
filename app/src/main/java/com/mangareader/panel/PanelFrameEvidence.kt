package com.mangareader.panel

import kotlin.math.abs

internal object PanelFrameEvidence {

    private const val LINE_SUPPORT_THRESHOLD = 0.80f
    private const val SLOPE_LIMIT = 0.35f

    internal fun frameSides(
        pixels: IntArray,
        count: Int,
        width: Int,
        box: PanelComponents.Box,
        tolerance: Int,
    ): Int {
        val top = box.y0
        val bottom = box.y1
        val left = box.x0
        val right = box.x1
        val h = bottom - top + 1
        val w = right - left + 1
        if (h <= 0 || w <= 0) return 0
        val leftArr = IntArray(h) { right + 1 }
        val rightArr = IntArray(h) { left - 1 }
        val topArr = IntArray(w) { bottom + 1 }
        val bottomArr = IntArray(w) { top - 1 }
        for (i in 0 until count) {
            val idx = pixels[i]
            val y = idx / width
            val x = idx - y * width
            val yi = y - top
            val xi = x - left
            if (yi in 0 until h) {
                if (x < leftArr[yi]) leftArr[yi] = x
                if (x > rightArr[yi]) rightArr[yi] = x
            }
            if (xi in 0 until w) {
                if (y < topArr[xi]) topArr[xi] = y
                if (y > bottomArr[xi]) bottomArr[xi] = y
            }
        }
        var sides = 0
        if (lineSupport(leftArr, 0, h - 1, tolerance) >= LINE_SUPPORT_THRESHOLD) sides++
        if (lineSupport(rightArr, 0, h - 1, tolerance) >= LINE_SUPPORT_THRESHOLD) sides++
        if (lineSupport(topArr, 0, w - 1, tolerance) >= LINE_SUPPORT_THRESHOLD) sides++
        if (lineSupport(bottomArr, 0, w - 1, tolerance) >= LINE_SUPPORT_THRESHOLD) sides++
        return sides
    }

    private fun lineSupport(values: IntArray, first: Int, last: Int, tolerance: Int): Float {
        val span = last - first
        if (span <= 0) return 0f
        var best = 0f
        for (a in 0..4) {
            for (b in a + 3..8) {
                val i = first + span * a / 8
                val j = first + span * b / 8
                if (j == i) continue
                val slope = (values[j].toFloat() - values[i]) / (j - i)
                if (abs(slope) <= SLOPE_LIMIT) {
                    var cnt = 0
                    for (k in first..last) {
                        if (abs(values[k] - values[i] - (k - i) * slope) <= tolerance) cnt++
                    }
                    val ratio = cnt.toFloat() / (span + 1)
                    if (ratio > best) {
                        best = ratio
                        if (best >= LINE_SUPPORT_THRESHOLD) return best
                    }
                }
            }
        }
        return best
    }
}