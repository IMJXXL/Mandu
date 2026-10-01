package com.mangareader.filter

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint


object ColorMatrixBuilder {

    fun buildCombinedColorMatrix(
        grayscale: Boolean,
        invert: Boolean,
        brightness: Int,
        contrast: Int,
        gammaApprox: Int? = null
    ): ColorMatrix? {
        val hasBrightness = brightness != 0
        val hasContrast = contrast != 100
        val hasGammaApprox = gammaApprox != null && gammaApprox != 100
        if (!grayscale && !invert && !hasBrightness && !hasContrast && !hasGammaApprox) return null

        val matrix = ColorMatrix()

        // 伽马近似放最前（与安卓13+的 RenderEffect 作用位置一致：内容先过伽马，再进对比度/亮度）
        if (hasGammaApprox && gammaApprox != null) {
            val g = gammaApprox / 100f
            val samples = floatArrayOf(0f, 64f, 128f, 192f, 255f)
            // 端点重权（黑4/中2/白4）：单条直线拟合 x^g 必然污染端点，均匀权重下 γ130 白点被压到
            // 244（漫画白底发灰）、γ50 黑端抬到 36。端点加权后白点误差减半（-7/-11）、黑端抬升减半，
            // 代价是中段误差略增——中段误差用户拖滑条可补偿，白底发灰不可补偿
            val weights = floatArrayOf(4f, 1f, 2f, 1f, 4f)
            val mapped = FloatArray(5) { i ->
                val normalized = samples[i] / 255f
                Math.pow(normalized.toDouble(), g.toDouble()).toFloat() * 255f
            }
            val (a, b) = leastSquaresFit(samples, mapped, weights)
            matrix.postConcat(ColorMatrix(floatArrayOf(
                a, 0f, 0f, 0f, b,
                0f, a, 0f, 0f, b,
                0f, 0f, a, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        // 后_concat 而非 set：setSaturation 内部是全量覆写，会冲掉上面的伽马近似（灰度+伽马同开时伽马失效）
        if (grayscale) matrix.postConcat(ColorMatrix().apply { setSaturation(0f) })

        if (invert) {
            matrix.postConcat(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }

        if (hasContrast) {
            val c = contrast / 100f
            val offset = 128f * (1f - c)
            matrix.postConcat(ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, offset,
                0f, c, 0f, 0f, offset,
                0f, 0f, c, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )))
        }

        if (hasBrightness) {
            val b = brightness * 2.550f
            matrix.postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f,
            )))
        }

        return matrix
    }

    private fun leastSquaresFit(x: FloatArray, y: FloatArray, w: FloatArray): Pair<Float, Float> {
        var sw = 0f
        var sx = 0f
        var sy = 0f
        var sxy = 0f
        var sxx = 0f
        for (i in x.indices) {
            sw += w[i]
            sx += w[i] * x[i]
            sy += w[i] * y[i]
            sxy += w[i] * x[i] * y[i]
            sxx += w[i] * x[i] * x[i]
        }
        val denom = sw * sxx - sx * sx
        if (denom == 0f) return Pair(1f, 0f)
        val a = (sw * sxy - sx * sy) / denom
        val b = (sy - a * sx) / sw
        return Pair(a, b)
    }

    fun buildPaint(colorMatrix: ColorMatrix?): Paint? {
        if (colorMatrix == null) return null
        return Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
    }
}