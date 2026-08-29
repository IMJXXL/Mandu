package com.mangareader.viewer

import android.graphics.Bitmap

object ImageTrimmer {

    data class TrimResult(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun trimWhiteBorders(bitmap: Bitmap, threshold: Int = 220, whiteRatio: Float = 0.90f, factor: Float = 0.15f): TrimResult {
        val w = bitmap.width
        val h = bitmap.height
        // ★ F3.3：判定用 1/4 降采样（4096×8192 整图 IntArray ≈134MB 瞬时 → 1/16 ≈8MB），判定完映射回全图坐标。
        //   白边判定对精度不敏感（margin/factor 兜底），缩放后像素均值不影响"是否大面积白"的判断。
        val dw = (w / 4).coerceAtLeast(1)
        val dh = (h / 4).coerceAtLeast(1)
        val probe = if (dw == w && dh == h) bitmap else Bitmap.createScaledBitmap(bitmap, dw, dh, true)
        val pixels = IntArray(dw * dh)
        probe.getPixels(pixels, 0, dw, 0, 0, dw, dh)
        if (probe !== bitmap) probe.recycle()

        var left = 0
        var right = dw - 1
        var top = 0
        var bottom = dh - 1

        for (x in 0 until dw / 2) {
            if (!isMostlyWhiteCol(pixels, x, dw, dh, threshold, whiteRatio)) { left = x; break }
        }
        for (x in dw - 1 downTo dw / 2) {
            if (!isMostlyWhiteCol(pixels, x, dw, dh, threshold, whiteRatio)) { right = x; break }
        }
        for (y in 0 until dh / 2) {
            if (!isMostlyWhiteRow(pixels, y, dw, dh, threshold, whiteRatio)) { top = y; break }
        }
        for (y in dh - 1 downTo dh / 2) {
            if (!isMostlyWhiteRow(pixels, y, dw, dh, threshold, whiteRatio)) { bottom = y; break }
        }

        // 映射回全图坐标（缩放放大近似，边缘偏差 ≤4px，下面 margin 足以覆盖）
        left = (left * w / dw).coerceIn(0, w - 1)
        right = (right * w / dw).coerceIn(0, w - 1)
        top = (top * h / dh).coerceIn(0, h - 1)
        bottom = (bottom * h / dh).coerceIn(0, h - 1)

        val margin = (w * 0.01f).toInt().coerceAtLeast(2)
        left = maxOf(0, left - margin)
        right = minOf(w - 1, right + margin)
        top = maxOf(0, top - margin)
        bottom = minOf(h - 1, bottom + margin)

        val maxTrimW = (w * factor).toInt()
        if (left > maxTrimW) left = maxTrimW
        if (w - 1 - right > maxTrimW) right = w - 1 - maxTrimW
        val maxTrimH = (h * factor).toInt()
        if (top > maxTrimH) top = maxTrimH
        if (h - 1 - bottom > maxTrimH) bottom = h - 1 - maxTrimH

        return TrimResult(left, top, right, bottom)
    }

    fun applyTrim(bitmap: Bitmap, trim: TrimResult): Bitmap {
        val x = trim.left.coerceIn(0, bitmap.width - 1)
        val y = trim.top.coerceIn(0, bitmap.height - 1)
        val w = (trim.right - trim.left + 1).coerceIn(1, bitmap.width - x)
        val h = (trim.bottom - trim.top + 1).coerceIn(1, bitmap.height - y)
        if (w <= 0 || h <= 0) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
        // ★ F3.3：createBitmap 裁剪本就产生独立新位图（仅无裁剪全尺寸时返回原对象，已被 !== 判断挡住），
        //   原来的额外 .copy() 是第三份瞬时内存，删掉（4096×8192 场景省约 128MB 峰值）
        return cropped
    }

    private fun isMostlyWhiteCol(pixels: IntArray, x: Int, w: Int, h: Int, threshold: Int, whiteRatio: Float): Boolean {
        var whiteCount = 0
        for (y in 0 until h) { if (isLight(pixels[y * w + x], threshold)) whiteCount++ }
        return whiteCount >= h * whiteRatio
    }

    private fun isMostlyWhiteRow(pixels: IntArray, y: Int, w: Int, h: Int, threshold: Int, whiteRatio: Float): Boolean {
        var whiteCount = 0
        for (x in 0 until w) { if (isLight(pixels[y * w + x], threshold)) whiteCount++ }
        return whiteCount >= w * whiteRatio
    }

    private fun isLight(pixel: Int, threshold: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r + g + b) / 3 > threshold
    }
}
