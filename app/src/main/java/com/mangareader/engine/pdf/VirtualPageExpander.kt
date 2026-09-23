package com.mangareader.engine.pdf

import android.util.Log
import com.mangareader.viewer.ArchiveEngine

object VirtualPageExpander {
    private const val TAG = "PdfTallSplit"

    fun expand(
        pages: List<ArchiveEngine.PageMeta>,
        tallSplitEnabled: Boolean,
        isWebtoonMode: Boolean,
        screenMax: Int,
        screenWidth: Int,
        engine: ArchiveEngine,
        treePath: String,
    ): List<ArchiveEngine.PageMeta> {
        if (!tallSplitEnabled || !isWebtoonMode) return pages
        val result = mutableListOf<ArchiveEngine.PageMeta>()
        for ((origIdx, meta) in pages.withIndex()) {
            if (meta.source != "pdf" || meta.segmentIndex > 0) {
                result.add(meta)
                continue
            }
            val size = try { engine.loadPageSize(meta, treePath) } catch (_: Exception) { null }
            val pw = size?.get(0)?.toFloat() ?: 0f
            val ph = size?.get(1)?.toFloat() ?: 0f
            if (pw <= 0f || ph <= 0f) {
                result.add(meta)
                continue
            }
            val splitH = PdfTallSplitCalculator.splitHeightPdf(screenMax, screenWidth, pw)
            if (!PdfTallSplitCalculator.isTallPage(pw, ph, splitH)) {
                result.add(meta)
                continue
            }
            val n = PdfTallSplitCalculator.segmentCount(ph, splitH)
            Log.d(TAG, "expand idx=$origIdx h/w=${ph / pw} N=$n splitH=$splitH")
            for (seg in 1..n) {
                result.add(meta.copy(segmentIndex = seg, segmentTotal = n, originalPageIdx = origIdx))
            }
        }
        return result
    }
}
