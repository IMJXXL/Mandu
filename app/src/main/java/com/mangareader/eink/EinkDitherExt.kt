package com.mangareader.eink

import android.graphics.Bitmap

/** 墨水屏抖动的 Bitmap 薄封装：批量 getPixels/setPixels（绝不逐像素 API），就地处理。 */
fun Bitmap.applyEinkDither(algorithm: Int, levels: Int): Boolean {
    if (isRecycled || width <= 0 || height <= 0) return false
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    EinkDither.apply(pixels, width, height, algorithm, levels)
    setPixels(pixels, 0, width, 0, 0, width, height)
    return true
}
