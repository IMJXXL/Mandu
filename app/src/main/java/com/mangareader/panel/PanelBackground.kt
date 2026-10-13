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
}