package com.mangareader.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.LruCache
import kotlin.math.abs

internal object PanelFoldJoin {

    private const val DARK_MAX = 40
    private const val DARK_SHARE = 0.97f
    private const val MAX_BAND_SHARE = 0.04f
    private const val CENTRE_TOLERANCE = 0.03f

    internal data class FoldInfo(val u0: Float, val u1: Float, val pad: Float)

    internal fun findBand(width: Int, height: Int, sample: (x: Int, y: Int) -> Int): IntArray? {
        if (width < 50 || height < 50) return null
        val cx = width / 2
        val step = maxOf(1, height / 200)
        fun isDarkColumn(x: Int): Boolean {
            var dark = 0
            var total = 0
            var y = 0
            while (y < height) {
                val c = sample(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum < DARK_MAX) dark++
                total++
                y += step
            }
            return dark.toFloat() / total >= DARK_SHARE
        }
        if (!isDarkColumn(cx)) return null
        var x0 = cx
        while (x0 > 0 && isDarkColumn(x0 - 1)) x0--
        var x1 = cx
        while (x1 < width - 1 && isDarkColumn(x1 + 1)) x1++
        val bandW = x1 - x0 + 1
        val maxBand = (MAX_BAND_SHARE * width).toInt()
        val centreTol = (CENTRE_TOLERANCE * width).toInt()
        val centre = (x0 + x1) / 2
        if (bandW > maxBand) return null
        if (abs(centre - cx) > centreTol) return null
        if (x0 <= 0 || x1 >= width - 1) return null
        return intArrayOf(x0, x1)
    }

    internal fun joinBitmap(src: Bitmap): Pair<Bitmap, FoldInfo?>? {
        if (src.isRecycled) return null
        val w = src.width
        val h = src.height
        val band = findBand(w, h) { x, y -> src.getPixel(x, y) } ?: return null
        val x0 = band[0]
        val x1 = band[1]
        val bandW = x1 - x0 + 1
        val pad = bandW / 2
        val leftW = x0
        val rightW = w - x1 - 1
        val newW = leftW + pad * 2 + rightW
        if (newW <= 0 || h <= 0) return null
        trimEdges(src, x0, x1, PanelTunables.FOLD_TRIM_COLUMNS)
        return try {
            val result = Bitmap.createBitmap(newW, h, Bitmap.Config.ARGB_8888)
            result.eraseColor(Color.WHITE)
            val canvas = Canvas(result)
            if (leftW > 0) {
                canvas.drawBitmap(src, Rect(0, 0, x0, h), Rect(0, 0, leftW, h), null)
            }
            if (rightW > 0) {
                canvas.drawBitmap(src, Rect(x1 + 1, 0, w, h), Rect(leftW + pad * 2, 0, newW, h), null)
            }
            val u0 = leftW.toFloat() / newW
            val u1 = (leftW + pad * 2).toFloat() / newW
            Pair(result, FoldInfo(u0, u1, pad.toFloat() / newW))
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 折痕条带边缘修剪：对左边界左侧 trimColumns 列用更左侧邻近画面列复制覆盖，
     * 右边界右侧 trimColumns 列同理。越界时跳过越界列仅修剪存在邻近列的一侧。
     * 直接修改 src 像素。返回 true=修剪成功，false=越界无法修剪（调用方回退未修剪）。
     */
    private fun trimEdges(src: Bitmap, bandX0: Int, bandX1: Int, trimColumns: Int = PanelTunables.FOLD_TRIM_COLUMNS): Boolean {
        if (src.isRecycled) return false
        val w = src.width
        val h = src.height
        if (trimColumns <= 0 || h <= 0 || w <= 0) return false
        var trimmed = false
        try {
            val step = trimColumns + 1
            for (i in 1..trimColumns) {
                val leftEdge = bandX0 - i
                val leftSrc = leftEdge - step
                if (leftEdge >= 0 && leftSrc >= 0) {
                    for (y in 0 until h) {
                        src.setPixel(leftEdge, y, src.getPixel(leftSrc, y))
                    }
                    trimmed = true
                }
                val rightEdge = bandX1 + i
                val rightSrc = rightEdge + step
                if (rightEdge < w && rightSrc < w) {
                    for (y in 0 until h) {
                        src.setPixel(rightEdge, y, src.getPixel(rightSrc, y))
                    }
                    trimmed = true
                }
            }
        } catch (_: Exception) {
            return false
        }
        return trimmed
    }

    internal object Cache {
        private const val MAX_BOOKS = 4
        private val books = object : LruCache<String, BookCache>(MAX_BOOKS) {
            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: BookCache,
                newValue: BookCache?,
            ) {
                oldValue.evictAll()
            }
        }

        fun get(treePath: String, page: Int): Bitmap? = books[treePath]?.get(page)

        fun put(treePath: String, page: Int, bmp: Bitmap) {
            val bc = books[treePath] ?: BookCache().also { books.put(treePath, it) }
            bc.put(page, bmp)
        }

        fun clearBook(treePath: String) {
            books.remove(treePath)
        }
    }

    private class BookCache : LruCache<Int, Bitmap>(32) {
        override fun entryRemoved(
            evicted: Boolean,
            key: Int,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (!oldValue.isRecycled) oldValue.recycle()
        }
    }
}