package com.mangareader.sr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log

enum class DeviceTier { FAST, MID, SLOW, UNKNOWN }

data class BenchmarkResult(
    val inferenceMs: Long = -1,
    val deviceTier: DeviceTier = DeviceTier.UNKNOWN,
    val scale: Int = 2,
    val modelKey: String? = null,
)

/** 基准测试：生成一张合成图跑一次推理，按耗时给设备分档（快/中/慢），决定预加载页数。 */
class SrBenchmark(private val manager: SuperResolutionManager) {

    suspend fun run(context: Context): BenchmarkResult {
        val synthetic = createSyntheticImage()
        try {
            val scale = manager.activeScale
            val version = manager.currentModelVersion()
            val start = System.nanoTime()
            val result = manager.process(synthetic, version)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val didRun = result.width == synthetic.width * scale
            if (result !== synthetic) result.recycle()

            if (!didRun) {
                Log.w(TAG, "benchmark: SR did not run, tier UNKNOWN")
                return BenchmarkResult(deviceTier = DeviceTier.UNKNOWN)
            }

            return BenchmarkResult(
                inferenceMs = elapsedMs,
                deviceTier = classifyTier(elapsedMs),
                scale = scale,
                modelKey = manager.activeModel?.key,
            )
        } finally {
            synthetic.recycle()
        }
    }

    private fun classifyTier(ms: Long): DeviceTier = when {
        ms < 3000 -> DeviceTier.FAST
        ms < 8000 -> DeviceTier.MID
        else -> DeviceTier.SLOW
    }

    private fun createSyntheticImage(width: Int = 800, height: Int = 1100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            Color.LTGRAY, Color.DKGRAY, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        for (i in 1 until 10) {
            val x = i * width / 10f
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }
        for (i in 1 until 10) {
            val y = i * height / 10f
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        }
        return bitmap
    }

    companion object {
        private const val TAG = "SrBenchmark"
    }
}

class DeviceProfileManager(context: Context) {

    private val prefs = context.getSharedPreferences("sr_benchmark", Context.MODE_PRIVATE)

    fun saveResult(result: BenchmarkResult) {
        prefs.edit()
            .putString("tier", result.deviceTier.name)
            .putLong("inference_ms", result.inferenceMs)
            .putInt("scale", result.scale)
            .putString("model", result.modelKey)
            .putLong("ts", System.currentTimeMillis())
            .apply()
    }

    /** 快机预载 5 页 / 中机 2 页 / 慢机不预载；未跑过基准测试给保守默认 2 页 */
    fun getPreloadWindow(): Int {
        val tierName = prefs.getString("tier", null) ?: return 2
        return when (tierName) {
            "FAST" -> 5
            "MID" -> 2
            "SLOW" -> 0
            else -> 2
        }
    }

    fun hasResult(): Boolean = prefs.contains("tier")

    fun describe(): String {
        val tier = prefs.getString("tier", null) ?: return "未测试"
        val ms = prefs.getLong("inference_ms", -1)
        val label = when (tier) {
            "FAST" -> "快"
            "MID" -> "中"
            "SLOW" -> "慢"
            else -> "未知"
        }
        return "$label（单页约 ${ms}ms）"
    }

    fun clear() = prefs.edit().clear().apply()
}
