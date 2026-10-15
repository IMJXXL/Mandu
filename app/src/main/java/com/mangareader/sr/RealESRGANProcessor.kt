package com.mangareader.sr

import java.io.File
import android.graphics.Bitmap
import android.util.Log

class RealESRGANProcessor(
    override val model: SRModel,
) : SuperResolutionProcessor {

    private var handle: Long = 0L
    // ★ F4.5②：abort/release 共用生命周期锁——原先 abort 读 handle 与 release 置空之间有
    //   use-after-free 窗口（nativeAbort 可打在已 delete 的 wrapper 上；release 的 waitForIdle 不覆盖 abort 路径）
    private val lifecycleLock = Any()

    override val isReady: Boolean
        get() = handle != 0L && nativeLibraryLoaded

    override suspend fun initialize(modelPath: String, gpuid: Int) {
        if (!nativeLibraryLoaded) {
            Log.e(TAG, "SR native library not available for ${model.key}")
            return
        }
        if (handle != 0L) release()


        val dirName = File(modelPath).name
        val paramPath = "$modelPath/$dirName.param"
        val binPath = "$modelPath/$dirName.bin"
        try {
            handle = nativeInit(paramPath, binPath, gpuid, model.modelType, true, model.scale)
            Log.i(TAG, "RealESRGAN initialized: model=${model.key}, handle=$handle")
        } catch (e: Throwable) {
            Log.e(TAG, "RealESRGAN native init failed: ${e.message}")
            handle = 0L
        }
    }

    override fun process(
        input: Bitmap,
        scale: Int,
        denoiseLevel: DenoiseLevel,
        denoiseStrength: Float,
        bwConfig: MangaBWPostProcessConfig?,
    ): Bitmap {
        if (!isReady) {
            Log.w(TAG, "RealESRGAN not ready, returning original")
            return input
        }
        val output = nativeProcess(
            handle, input, scale,
            denoiseStrength,
            bwConfig?.grayLevels ?: 0,
            bwConfig?.densityCorrection ?: false,
        )
        return output ?: input
    }

    override fun abort() {
        synchronized(lifecycleLock) {
            val h = handle
            if (h != 0L && nativeLibraryLoaded) {
                try { nativeAbort(h) } catch (_: Throwable) {}
            }
        }
    }

    override fun release() {
        synchronized(lifecycleLock) {
            val h = handle
            handle = 0L
            if (h != 0L && nativeLibraryLoaded) {
                try { nativeRelease(h) } catch (_: Throwable) {}
            }
        }
    }

    private external fun nativeInit(paramPath: String, binPath: String, gpuid: Int, modelType: String, useFp16: Boolean, scale: Int): Long
    private external fun nativeProcess(
        handle: Long, input: Bitmap, scale: Int,
        denoiseStrength: Float, grayLevels: Int, densityCorrection: Boolean,
    ): Bitmap?
    private external fun nativeRelease(handle: Long)
    private external fun nativeAbort(handle: Long)

    companion object {
        private const val TAG = "SrProcessor"
        val nativeLibraryLoaded: Boolean by lazy {
            try {
                android.system.Os.setenv("KMP_AFFINITY", "disabled", true)
                android.system.Os.setenv("OMP_NUM_THREADS", "1", true)
                System.loadLibrary("sr_engine")
                Log.i(TAG, "libsr_engine.so loaded successfully")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libsr_engine.so: ${e.message}")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error loading libsr_engine.so: ${e.message}")
                false
            }
        }
    }
}
