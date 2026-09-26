package com.mangareader.engine.img

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.mangareader.engine.pdf.PdfTallSplitCalculator
import com.mangareader.viewer.ArchiveEngine

/**
 * 长图分段渲染器（§4）：BitmapRegionDecoder 按段解码——不整图解码、不降采样，
 * 长条漫在 fit-width 显示下原生清晰。几何与 PDF 分段同源（PdfTallSplitCalculator
 * A1 均分语义），实现各异（PDF 走 PdfRenderer 渲染、图片走区域解码）。
 *
 * 解码宽钳制：inSampleSize 取「段宽仍 ≥ 屏宽的最大 2 的幂」，解码后若仍超屏宽则精确
 * 缩到屏宽——段位图内存钳制在 ≈屏宽×2×屏高（与 PDF 段同量级 ~20MB），不随原图宽度爆炸。
 *
 * 安全矩阵：BRI 实例化失败/区域解码失败/与展开几何不符（图被替换、缓存过期）→ 返回
 * null 由调用方整图兜底；OOM → sample×2 重试一次，再败回退整图。永不抛出。
 */
@Suppress("DEPRECATION") // BitmapRegionDecoder 自 API 31 deprecated，但仍是唯一的区域解码方案（minSdk 26）
object ImageSegmentRenderer {

    /** 段缓存键：携带屏幕尺寸（同 PdfSegmentRenderer）——分屏/旋转后旧尺寸缓存不可达，重走新几何 */
    fun segmentCacheKey(pageKey: String, segmentIndex: Int, screenWidth: Int, screenMax: Int): String =
        "${pageKey}|seg${segmentIndex}|w${screenWidth}m${screenMax}"

    /**
     * 采样规则（纯函数，JVM 测试覆盖）：取最大 2 的幂 s 使 imageWidth/s ≥ cap，
     * cap = max(目标宽, 720)。宁可保住原生宽度再精确缩，也不落在屏宽一半以下。
     */
    fun sampleSizeFor(imageWidth: Int, targetWidth: Int): Int {
        var s = 1
        val cap = maxOf(targetWidth, 720)
        while (imageWidth / (s * 2) >= cap && s < 1 shl 20) s *= 2
        return s
    }

    fun decode(
        engine: ArchiveEngine,
        meta: ArchiveEngine.PageMeta,
        treePath: String,
        segmentIndex: Int,
        screenWidth: Int,
        screenMax: Int,
    ): Bitmap? {
        // ★ 原始字节读取失败重试一次：首次冷缓存/并发抖动下 zip 条目流偶发失败 → 段渲染 null
        //   走整图兜底（用户实测=第一页清晰后面全是降采样糊图），重试通常即成
        var raw = try { engine.loadPageRaw(meta, treePath) } catch (_: Exception) { null }
        if (raw == null || raw.isEmpty()) {
            try { Thread.sleep(120) } catch (_: InterruptedException) { return null }
            raw = try { engine.loadPageRaw(meta, treePath) } catch (_: Exception) { null }
        }
        if (raw == null || raw.isEmpty()) return null
        val dec = try { BitmapRegionDecoder.newInstance(raw, 0, raw.size, false) } catch (_: Exception) { null } ?: return null
        try {
            val imgW = dec.width
            val imgH = dec.height
            if (imgW <= 0 || imgH <= 0) return null
            val splitH = PdfTallSplitCalculator.splitHeightPdf(screenMax, screenWidth, imgW.toFloat())
            // ★ 展开几何复核：图被替换/尺寸表过期导致已不是长图 → null 走整图兜底，绝不错切错位
            if (!PdfTallSplitCalculator.isTallPage(imgW.toFloat(), imgH.toFloat(), splitH)) return null
            val rect = PdfTallSplitCalculator.segmentRect(imgW.toFloat(), imgH.toFloat(), splitH, segmentIndex)
            val r0 = rect.top.toInt().coerceIn(0, imgH - 1)
            val r1 = rect.bottom.toInt().coerceIn(r0 + 1, imgH)
            val region = Rect(0, r0, imgW, r1)
            val s = sampleSizeFor(imgW, screenWidth)
            var bmp = decodeRegion(dec, region, s)
            if (bmp == null) {
                // OOM/失败重试一次：sample×2 减内存
                System.gc()
                bmp = decodeRegion(dec, region, s * 2)
            }
            if (bmp == null) return null
            // 2 的幂采样给出离散宽度（如 1250），超屏宽的段精确缩到屏宽（fit-width 显示无损）
            val cap = maxOf(screenWidth, 720)
            if (bmp.width > cap + 2) {
                val scaledH = (bmp.height.toLong() * cap / bmp.width).toInt().coerceAtLeast(1)
                val scaled = try { Bitmap.createScaledBitmap(bmp, cap, scaledH, true) } catch (oom: OutOfMemoryError) { null }
                if (scaled != null) {
                    if (scaled !== bmp) bmp.recycle()
                    bmp = scaled
                }
            }
            return bmp
        } finally {
            try { dec.recycle() } catch (_: Exception) {}
        }
    }

    private fun decodeRegion(dec: BitmapRegionDecoder, region: Rect, sampleSize: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            dec.decodeRegion(region, opts)
        } catch (oom: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
