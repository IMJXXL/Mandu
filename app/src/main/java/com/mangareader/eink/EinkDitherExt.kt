package com.mangareader.eink

import android.graphics.Bitmap

fun Bitmap.applyEinkDither(algorithm: Int, levels: Int): Bitmap {
    if (isRecycled || width <= 0 || height <= 0) return this
    val soft = if (config == Bitmap.Config.HARDWARE) {
        copy(Bitmap.Config.ARGB_8888, false) ?: return this
    } else this
    if (EinkDitherNative.nativeDither(soft, algorithm, levels)) return soft
    val pixels = IntArray(soft.width * soft.height)
    soft.getPixels(pixels, 0, soft.width, 0, 0, soft.width, soft.height)
    EinkDither.apply(pixels, soft.width, soft.height, algorithm, levels)
    soft.setPixels(pixels, 0, soft.width, 0, 0, soft.width, soft.height)
    return soft
}
