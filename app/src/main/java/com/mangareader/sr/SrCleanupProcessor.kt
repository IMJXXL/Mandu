package com.mangareader.sr

import android.graphics.Bitmap
import android.util.Log

/** 快速优化档（1x 算法清理）：双边滤波去噪 + 锐化掩模，纯 CPU 原图尺寸毫秒级。
 *  无神经网络权重，不依赖 ncnn/Vulkan；任何失败返回 null，调用方回退原图。 */
object SrCleanupProcessor {
    private const val TAG = "SrCleanup"

    private val libLoaded: Boolean by lazy {
        try {
            System.loadLibrary("sr_engine")
            true
        } catch (_: Throwable) {
            Log.e(TAG, "sr_engine load failed, 快速优化档不可用")
            false
        }
    }

    private external fun nativeCleanup(src: Bitmap, strength: Int): Bitmap?

    /** strength: 0=中 1=轻 2=强（对应降噪三档 OFF/LIGHT/STRONG） */
    fun process(src: Bitmap, strength: Int): Bitmap? {
        if (!libLoaded || src.isRecycled) return null
        // 硬件位图无法 lockPixels 读 RGBA，交给调用方回退
        if (src.config != Bitmap.Config.ARGB_8888) return null
        return try {
            nativeCleanup(src, strength)
        } catch (t: Throwable) {
            Log.w(TAG, "cleanup failed: ${t.message}")
            null
        }
    }
}
