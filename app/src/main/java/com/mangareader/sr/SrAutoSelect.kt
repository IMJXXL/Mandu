package com.mangareader.sr

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.roundToInt

/**
 * 自动选最快模型：拿用户当前正在看的真实页面，逐个模型实测耗时，选最快的。
 * 结果写入全局设置（SrSettings.model）持久化，直到用户再次点击本功能或手动换模型。
 */
object SrAutoSelect {
    private const val TAG = "SrAutoSelect"

    data class Result(
        val best: SRModel,
        val bestMs: Long,
        val all: List<Pair<SRModel, Long>>,
    )

    /**
     * @param sample 当前页原图（管线留档里取；取不到返回 null）
     * @param onProgress 进度文案回调（"正在实测 2/5 …"）
     */
    suspend fun run(sample: Bitmap?, onProgress: (String) -> Unit): Result? {
        if (sample == null || sample.isRecycled) return null
        val models = NativeLibraryStatus.getAvailableModels()
        if (models.isEmpty()) return null

        // 测速样本统一缩到 800 最长边：各模型同素材、同尺寸，毫秒数才可比
        val longest = maxOf(sample.width, sample.height)
        val testSample = if (longest > 800) {
            val r = 800f / longest
            Bitmap.createScaledBitmap(
                sample,
                (sample.width * r).roundToInt().coerceAtLeast(1),
                (sample.height * r).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            sample
        }

        val results = mutableListOf<Pair<SRModel, Long>>()
        models.forEachIndexed { index, model ->
            onProgress("正在实测 ${index + 1}/${models.size}：${model.displayName}")
            try {
                SrCore.manager.switchModel(
                    model = model,
                    scale = model.scale,
                    denoiseLevel = SrCore.settings.denoise,
                    bwConfig = SrCore.settings.bwConfigOrNull(),
                )
                // 第一遍暖机（含模型加载，不计时）
                val v1 = SrCore.manager.currentModelVersion()
                val warm = SrCore.manager.process(testSample, v1)
                if (warm !== testSample && !warm.isRecycled) warm.recycle()
                // 第二遍正式计时
                val v2 = SrCore.manager.currentModelVersion()
                val t0 = System.nanoTime()
                val out = SrCore.manager.process(testSample, v2)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (out !== testSample && !out.isRecycled) out.recycle()
                results.add(model to ms)
                Log.i(TAG, "${model.key}: ${ms}ms")
            } catch (e: Exception) {
                Log.e(TAG, "bench ${model.key} failed", e)
                results.add(model to Long.MAX_VALUE)
            }
        }

        if (testSample !== sample && !testSample.isRecycled) testSample.recycle()

        val best = results.filter { it.second != Long.MAX_VALUE }.minByOrNull { it.second } ?: return null
        // 写全局并立即应用
        SrCore.settings.model = best.first
        SrCore.applySettings(null)
        onProgress("完成")
        return Result(best.first, best.second, results)
    }
}
