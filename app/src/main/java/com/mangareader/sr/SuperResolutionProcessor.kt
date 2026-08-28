package com.mangareader.sr

import android.graphics.Bitmap

interface SuperResolutionProcessor {
    val model: SRModel
    val isReady: Boolean

    suspend fun initialize(modelPath: String, gpuid: Int = 0)

    fun process(
        input: Bitmap,
        scale: Int = 2,
        denoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT,
        denoiseStrength: Float = 0f,
        bwConfig: MangaBWPostProcessConfig? = null,
    ): Bitmap

    fun release()
}
