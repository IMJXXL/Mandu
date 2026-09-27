package com.mangareader.eink

import android.graphics.Bitmap

object EinkDitherNative {
    private val loaded = try {
        System.loadLibrary("eink_dither"); true
    } catch (_: Exception) { false }

    fun nativeDither(bitmap: Bitmap, algorithm: Int, levels: Int, endpointPassthrough: Int = 0): Boolean {
        if (!loaded) return false
        return try { nativeDitherImpl(bitmap, algorithm, levels, endpointPassthrough) } catch (_: Exception) { false }
    }

    private external fun nativeDitherImpl(bitmap: Bitmap, algorithm: Int, levels: Int, endpointPassthrough: Int): Boolean
}