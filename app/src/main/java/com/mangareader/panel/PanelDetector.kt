package com.mangareader.panel

import android.graphics.Bitmap
import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bitmap 包装层（管线最外层，评估报告 §六 API）。
 * 所有格式（本地/远程、zip/cbz/rar/azw3/pdf/文件夹）最终都经 ArchiveEngine 出 Bitmap，
 * 这里只认 Bitmap → 天然全格式兼容，零格式适配。
 */
object PanelDetector {

    /**
     * 检测单页。缩样至长边≤[PanelTunables.MAX_SIDE]（性能预算 30~50ms/页），即用即弃。
     * HARDWARE 位图先拷软件位图（getPixels 不支持硬件位图）。
     */
    suspend fun detect(
        src: Bitmap,
        rtl: Boolean,
        confFail: Float = PanelTunables.CONF_FAIL,
    ): PanelPageResult = withContext(Dispatchers.Default) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val sw = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        try {
            val longSide = maxOf(sw.width, sw.height)
            val scale = if (longSide > PanelTunables.MAX_SIDE) PanelTunables.MAX_SIDE / longSide.toFloat() else 1f
            val dw = kotlin.math.round(sw.width * scale).toInt().coerceAtLeast(1)
            val dh = kotlin.math.round(sw.height * scale).toInt().coerceAtLeast(1)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(sw, dw, dh, true) else sw
            val px = IntArray(dw * dh)
            small.getPixels(px, 0, dw, 0, 0, dw, dh)
            if (small !== sw) small.recycle()
            val gray = IntArray(dw * dh)
            for (i in gray.indices) {
                val p = px[i]
                gray[i] = (0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)).toInt()
            }
            val r = PanelDetectorCore.analyze(gray, dw, dh, rtl, confFail)
            Log.d(
                "PanelDetector",
                "detect ${dw}x${dh} boxes=${r.boxes.size} conf=${r.pageConf} fallback=${r.fallback} ${android.os.SystemClock.elapsedRealtime() - t0}ms",
            )
            r
        } finally {
            if (sw !== src) sw.recycle()
        }
    }

    /**
     * 页级结果缓存：按书隔离（每书 LRU 128 页，最多留 4 本，LRU 逐出最旧的书）。
     * 覆盖层每次换页先查这里，命中即零成本直显；换书 [clearBook]。
     */
    object Cache {
        private const val MAX_BOOKS = 4
        private const val PAGES_PER_BOOK = 128
        private val books = object : LinkedHashMap<String, android.util.LruCache<Int, PanelPageResult>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.util.LruCache<Int, PanelPageResult>>): Boolean =
                size > MAX_BOOKS
        }

        private fun book(treePath: String): android.util.LruCache<Int, PanelPageResult> = synchronized(books) {
            books.getOrPut(treePath) { android.util.LruCache(PAGES_PER_BOOK) }
        }

        fun get(treePath: String, page: Int): PanelPageResult? = synchronized(books) { book(treePath).get(page) }

        fun put(treePath: String, page: Int, result: PanelPageResult) {
            synchronized(books) { book(treePath).put(page, result) }
        }

        fun clearBook(treePath: String) {
            synchronized(books) { books.remove(treePath) }
        }
    }
}
