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

    // ★ curl 双页合并产物缓存（对齐 pager mergedCache，64MB 字节预算）：
    //   PageCurl 的 key(updatedCurrent) 每次落地整树重建，remember(leftBmp,rightBmp) 随之重跑
    //   → 主线程每次落地 2-3 张 ~19.5MB createBitmap 分配突发掉帧。键=源位图 identity
    //  （页解码/纹理重烘换实例自动 miss），淘汰不 recycle（同 pager 策略，byteCount 恒定无失配）
    private val mergedCache = object : androidx.collection.LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun mergeBitmaps(left: Bitmap, right: Bitmap?, gapPx: Int = 0, bgColor: Int = android.graphics.Color.WHITE): Bitmap {
        if (right == null || right.isRecycled) return left
        val key = "${System.identityHashCode(left)}:${System.identityHashCode(right)}:$gapPx:$bgColor"
        mergedCache.get(key)?.let { if (!it.isRecycled) return it }
        val leftSw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && left.config == Bitmap.Config.HARDWARE) left.copy(Bitmap.Config.ARGB_8888, false) else left
        val rightSw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && right.config == Bitmap.Config.HARDWARE) right.copy(Bitmap.Config.ARGB_8888, false) else right
        val w = leftSw.width + rightSw.width + gapPx
        val h = maxOf(leftSw.height, rightSw.height)
        val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        // ★ 整屏幕：两页高度差留边随纸纹（页面各自已烘焙，只填空档不重复烘），关=纯平涂原样
        com.mangareader.filter.PaperPageTexture.fillMergeBackground(canvas, w, h, bgColor)
        canvas.drawBitmap(leftSw, 0f, (h - leftSw.height) / 2f, null)
        canvas.drawBitmap(rightSw, (leftSw.width + gapPx).toFloat(), (h - rightSw.height) / 2f, null)
        mergedCache.put(key, merged)
        return merged
    }

    fun protectSpreadPages(spread: Int, totalPages: Int, shift: Boolean): Set<Int> {
        val pages = mutableSetOf<Int>()
        for (s in (spread - 1)..(spread + 1)) {
            if (s >= 0 && s < spreadCount(totalPages, shift)) {
                pages.add(leftPage(s, totalPages, shift))
                val r = rightPage(s, totalPages, shift)
                if (r >= 0) pages.add(r)
            }
        }
        return pages
    }
}