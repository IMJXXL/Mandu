package com.mangareader.sr

import android.graphics.Bitmap

/** 引擎不可用时的兜底处理器：原样返回，绝不崩溃。 */
class NoOpProcessor : SuperResolutionProcessor {
    override val model: SRModel = SRModel.REALCUGAN_2X_CONSERVATIVE
    override val isReady: Boolean = true
    override suspend fun initialize(modelPath: String, gpuid: Int) {}
    override fun process(
        input: Bitmap,
        scale: Int,
        denoiseLevel: DenoiseLevel,
        denoiseStrength: Float,
        bwConfig: MangaBWPostProcessConfig?,
    ): Bitmap = input
    override fun release() {}
}
