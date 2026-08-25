package com.mangareader.viewer

import android.graphics.Bitmap

object ImageTrimmer {

    data class TrimResult(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun trimWhiteBorders(bitmap: Bitmap, threshold: Int = 220, whiteRatio: Float = 0.90f, factor: Float = 0.15f): TrimResult {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var left = 0
        var right = w - 1
        var top = 0
        var bottom = h - 1

        for (x in 0 until w / 2) {
            if (!isMostlyWhiteCol(pixels, x, w, h, threshold, whiteRatio)) { left = x; break }
        }
        for (x in w - 1 downTo w / 2) {
            if (!isMostlyWhiteCol(pixels, x, w, h, threshold, whiteRatio)) { right = x; break }
        }
        for (y in 0 until h / 2) {
            if (!isMostlyWhiteRow(pixels, y, w, h, threshold, whiteRatio)) { top = y; break }
        }
        for (y in h - 1 downTo h / 2) {
            if (!isMostlyWhiteRow(pixels, y, w, h, threshold, whiteRatio)) { bottom = y; break }
        }

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
        // .copy() ensures the result is an independent bitmap not tied to the source's lifecycle
        return if (cropped !== bitmap) cropped.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) else bitmap
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
