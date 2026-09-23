package com.mangareader.engine.pdf

import android.graphics.RectF
import android.util.Log
import kotlin.math.ceil

object PdfTallSplitCalculator {
    private const val TAG = "PdfTallSplit"
    private const val TALL_RATIO_THRESHOLD = 3f
    private const val MAX_SEGMENTS = 64

    fun optimalSplitHeightPx(screenMax: Int): Float =
        if (screenMax <= 0) 4096f else screenMax * 2f

    fun splitHeightPdf(screenMax: Int, screenWidth: Int, pageWidth: Float): Float {
        if (screenWidth <= 0 || pageWidth <= 0f) return optimalSplitHeightPx(screenMax)
        return optimalSplitHeightPx(screenMax) * pageWidth / screenWidth.toFloat()
    }

    fun isTallPage(pageWidth: Float, pageHeight: Float, splitHeightPdf: Float): Boolean {
        if (pageWidth <= 0f) return false
        return pageHeight / pageWidth > TALL_RATIO_THRESHOLD &&
            pageHeight > splitHeightPdf
    }

    fun segmentCount(pageHeight: Float, splitHeightPdf: Float): Int {
        if (splitHeightPdf <= 0f || pageHeight <= 0f) return 1
        var n = ceil(pageHeight / splitHeightPdf).toInt().coerceAtLeast(1)
        if (n > MAX_SEGMENTS) {
            Log.w(TAG, "segmentCount capped $n -> $MAX_SEGMENTS pageHeight=$pageHeight")
            n = MAX_SEGMENTS
        }
        return n
    }

    fun segmentRect(pageWidth: Float, pageHeight: Float, splitHeightPdf: Float, segmentIndex: Int): RectF {
        val y0 = (segmentIndex - 1) * splitHeightPdf
        val y1 = minOf(segmentIndex * splitHeightPdf, pageHeight)
        return RectF(0f, y0, pageWidth, y1.coerceAtMost(pageHeight))
    }
}
