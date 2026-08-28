package com.mangareader.sr

import android.graphics.Bitmap

/**
 * 黑白优化独立处理器：不放大、不加载 AI 模型，只对原图做灰阶量化 + 网点密度校正。
 * 毫秒级纯 CPU，适合"只开黑白优化、不开超分放大"的场景（老机器/省电）。
 */
object SrBwProcessor {

    fun isAvailable(): Boolean = RealESRGANProcessor.nativeLibraryLoaded

    /**
     * 只对黑白漫画页调用（彩色页请跳过）。
     * @return 处理后的新 Bitmap；失败或 grayLevels<2 返回 null（调用方用原图）
     */
    fun process(src: Bitmap, grayLevels: Int, densityCorrection: Boolean): Bitmap? {
        if (!isAvailable() || src.isRecycled) return null
        if (grayLevels < 2) return null
        return try {
            nativeProcessBW(src, grayLevels, densityCorrection)
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    private external fun nativeProcessBW(src: Bitmap, grayLevels: Int, densityCorrection: Boolean): Bitmap?
}
