package com.mangareader.engine.pdf

object PdfSegmentRenderer {
    // ★ key 携带渲染尺寸（2026-09-25 实查）：splitH/segW/segH 由当时屏幕尺寸推导，
    //   分屏/折叠屏/免重启旋转改尺寸后，旧 key 的段位图与新切割几何不符（相邻段重叠/漏内容），
    //   尺寸入 key 使旧尺寸缓存不可达，重渲染走新几何。
    fun segmentCacheKey(sharedKey: String, pageIndex: Int, segmentIndex: Int, screenWidth: Int, screenMax: Int): String =
        "${sharedKey}|seg${segmentIndex}|w${screenWidth}m${screenMax}"
}