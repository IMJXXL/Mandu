package com.mangareader.sr

import android.graphics.Bitmap
import android.util.Log

class LanczosProcessor(
    override val model: SRModel,
) : SuperResolutionProcessor {

    override val isReady: Boolean
        get() = nativeLibraryLoaded

    override suspend fun initialize(modelPath: String, gpuid: Int) {
    }

    override fun process(
        input: Bitmap,
        scale: Int,
        denoiseLevel: DenoiseLevel,
        denoiseStrength: Float,
        bwConfig: MangaBWPostProcessConfig?,
    ): Bitmap {
        if (!isReady) return input
        if (input.isRecycled) return input
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && input.config == Bitmap.Config.HARDWARE) return input

        val argb = ensureArgb(input) ?: return input
        val out = nativeLanczosProcess(argb, scale.toFloat())
        if (out === argb || out === input) return input

        var result = out
        if (bwConfig != null && bwConfig.grayLevels >= 2) {
            SrBwProcessor.process(result, bwConfig.grayLevels, bwConfig.densityCorrection)
                ?.let { result = it }
        }
        return result
    }

    override fun release() {
    }

    private fun ensureArgb(input: Bitmap): Bitmap? {
        if (input.config == Bitmap.Config.ARGB_8888 && input.isMutable) return input
        val copied = input.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        if (input.config != Bitmap.Config.ARGB_8888) copied.setHasAlpha(false)
        return copied
    }

    private external fun nativeLanczosProcess(bitmap: Bitmap, scale: Float): Bitmap

    companion object {
        private const val TAG = "LanczosProcessor"
        val nativeLibraryLoaded: Boolean by lazy {
            try {
                System.loadLibrary("lanczos_engine")
                Log.i(TAG, "liblanczos_engine.so loaded successfully")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load liblanczos_engine.so: ${e.message}")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error loading liblanczos_engine.so: ${e.message}")
                false
            }
        }
    }
}