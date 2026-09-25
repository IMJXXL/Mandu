package com.mangareader.native.curl

import android.graphics.Bitmap
import android.graphics.Canvas

object SpreadMapping {
    fun spreadCount(totalPages: Int, shift: Boolean): Int =
        if (shift && totalPages > 1) 1 + totalPages / 2 else (totalPages + 1) / 2

    fun leftPage(spread: Int, totalPages: Int, shift: Boolean): Int =
        if (shift && totalPages > 1) {
            if (spread == 0) 0 else 2 * spread - 1
        } else {
            2 * spread
        }

    fun rightPage(spread: Int, totalPages: Int, shift: Boolean): Int {
        val r = if (shift && totalPages > 1) {
            if (spread == 0) -1 else 2 * spread
        } else {
            2 * spread + 1
        }
        return if (r >= totalPages) -1 else r
    }

    fun pageToSpread(page: Int, totalPages: Int, shift: Boolean): Int =
        if (shift && totalPages > 1) {
            if (page == 0) 0 else 1 + (page - 1) / 2
        } else {
            page / 2
        }

    fun mergeBitmaps(left: Bitmap, right: Bitmap?, gapPx: Int = 0, bgColor: Int = android.graphics.Color.WHITE): Bitmap {
        if (right == null || right.isRecycled) return left
        val leftSw = if (left.config == Bitmap.Config.HARDWARE) left.copy(Bitmap.Config.ARGB_8888, false) else left
        val rightSw = if (right.config == Bitmap.Config.HARDWARE) right.copy(Bitmap.Config.ARGB_8888, false) else right
        val w = leftSw.width + rightSw.width + gapPx
        val h = maxOf(leftSw.height, rightSw.height)
        val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(bgColor)
        canvas.drawBitmap(leftSw, 0f, (h - leftSw.height) / 2f, null)
        canvas.drawBitmap(rightSw, (leftSw.width + gapPx).toFloat(), (h - rightSw.height) / 2f, null)
        return merged
    }

    /** 当前 spread±2 的全部页索引：双页预加载+trim保护集共用。
     *  ±1 时空闲缓冲只有前 1 个 spread，连续快翻第二个 turn 的背面会与解码赛跑；
     *  ±2 最多 10 页，CurlBitmapCache(12) 装得下。 */
    fun protectSpreadPages(spread: Int, totalPages: Int, shift: Boolean): Set<Int> {
        val pages = mutableSetOf<Int>()
        for (s in (spread - 2)..(spread + 2)) {
            if (s >= 0 && s < spreadCount(totalPages, shift)) {
                pages.add(leftPage(s, totalPages, shift))
                val r = rightPage(s, totalPages, shift)
                if (r >= 0) pages.add(r)
            }
        }
        return pages
    }
}