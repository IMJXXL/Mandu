package com.mangareader.eink

import android.graphics.Bitmap

object EinkDitherNative {
    private val loaded = try {
        System.loadLibrary("eink_dither"); true
    } catch (_: Exception) { false }

    fun nativeDither(bitmap: Bitmap, algorithm: Int, levels: Int): Boolean {
        if (!loaded) return false
        return try { nativeDitherImpl(bitmap, algorithm, levels) } catch (_: Exception) { false }
    }

    private external fun nativeDitherImpl(bitmap: Bitmap, algorithm: Int, levels: Int): Boolean
}