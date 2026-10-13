package com.mangareader.panel

import kotlin.math.abs

internal object PanelBackground {

    private const val BG_WHITE_THRESHOLD = 200
    private const val BG_DELTA = 30

    internal fun estimate(gray: IntArray, w: Int, h: Int, sampleRatio: Float = 0.05f): Int {
        if (w <= 0 || h <= 0 || gray.size != w * h) return 255
        val bw = maxOf(1, (w * sampleRatio).toInt())
        val bh = maxOf(1, (h * sampleRatio).toInt())
        val hist = IntArray(256)
        var count = 0
        for (y in 0 until bh) {
            val row = y * w
            for (x in 0 until w) {
                hist[gray[row + x]]++
                count++
            }
        }
        for (y in h - bh until h) {
            val row = y * w
            for (x in 0 until w) {
                hist[gray[row + x]]++
                count++
            }
        }
        for (x in 0 until bw) {
            for (y in bh until h - bh) {
                hist[gray[y * w + x]]++
                count++
            }
        }
        for (x in w - bw until w) {
            for (y in bh until h - bh) {
                hist[gray[y * w + x]]++
                count++
            }
        }
        if (count == 0) return 255
        val median = count / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc > median) return v
        }
        return 255
    }

    internal fun maskRelativeTo(gray: IntArray, w: Int, h: Int, bg: Int, delta: Int = BG_DELTA): BooleanArray {
        val n = w * h
        val out = BooleanArray(n)
        for (i in 0 until n) out[i] = abs(gray[i] - bg) > delta
        return out
    }

    internal fun isWhiteBackground(bg: Int): Boolean = bg >= BG_WHITE_THRESHOLD

    /** RGB 三通道中位数背景估计。外环采样（页缘 sampleRatio 边带）分别统计 R/G/B 三通道直方图取中位数。 */
    internal fun estimateRgb(rgb: IntArray, w: Int, h: Int, sampleRatio: Float = PanelTunables.RGB_SAMPLE_RATIO): Triple<Int, Int, Int> {
        if (w <= 0 || h <= 0 || rgb.size != w * h) return Triple(255, 255, 255)
        val bw = maxOf(1, (w * sampleRatio).toInt())
        val bh = maxOf(1, (h * sampleRatio).toInt())
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var count = 0
        fun tally(idx: Int) {
            val p = rgb[idx]
            histR[(p shr 16) and 0xFF]++
            histG[(p shr 8) and 0xFF]++
            histB[p and 0xFF]++
            count++
        }
        for (y in 0 until bh) {
            val row = y * w
            for (x in 0 until w) tally(row + x)
        }
        for (y in h - bh until h) {
            val row = y * w
            for (x in 0 until w) tally(row + x)
        }
        for (x in 0 until bw) {
            for (y in bh until h - bh) tally(y * w + x)
        }
        for (x in w - bw until w) {
            for (y in bh until h - bh) tally(y * w + x)
        }
        if (count == 0) return Triple(255, 255, 255)
        return Triple(medianOf(histR, count), medianOf(histG, count), medianOf(histB, count))
    }

    /** 最大通道差判定前景：max(|r-br|,|g-bg|,|b-bb|)>delta → true（墨水）。 */
    internal fun maskRelativeToRgb(rgb: IntArray, w: Int, h: Int, bg: Triple<Int, Int, Int>, delta: Int = PanelTunables.RGB_DELTA): BooleanArray {
        val n = w * h
        val out = BooleanArray(n)
        val br = bg.first
        val bgc = bg.second
        val bb = bg.third
        for (i in 0 until n) {
            val p = rgb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val dr = abs(r - br)
            val dg = abs(g - bgc)
            val db = abs(b - bb)
            out[i] = maxOf(dr, dg, db) > delta
        }
        return out
    }

    /** 判定背景是否为彩色（三通道差异显著）。灰度页三通道接近返回 false。 */
    internal fun isColourBackground(bg: Triple<Int, Int, Int>): Boolean {
        val r = bg.first
        val g = bg.second
        val b = bg.third
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        return spread >= PanelTunables.RGB_COLOUR_SPREAD
    }

    private fun medianOf(hist: IntArray, count: Int): Int {
        val median = count / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc > median) return v
        }
        return 255
    }
}