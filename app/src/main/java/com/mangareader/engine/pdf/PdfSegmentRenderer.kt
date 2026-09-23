package com.mangareader.engine.pdf

object PdfSegmentRenderer {
    fun segmentCacheKey(sharedKey: String, pageIndex: Int, segmentIndex: Int): String =
        "${sharedKey}|seg${segmentIndex}"
}