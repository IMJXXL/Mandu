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

        if (grayscale) matrix.setSaturation(0f)

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

        if (hasGammaApprox && gammaApprox != null) {
            val g = gammaApprox / 100f
            val samples = floatArrayOf(0f, 64f, 128f, 192f, 255f)
            val mapped = FloatArray(5) { i ->
                val normalized = samples[i] / 255f
                Math.pow(normalized.toDouble(), g.toDouble()).toFloat() * 255f
            }
            val (a, b) = leastSquaresFit(samples, mapped)
            matrix.postConcat(ColorMatrix(floatArrayOf(
                a, 0f, 0f, 0f, b,
                0f, a, 0f, 0f, b,
                0f, 0f, a, 0f, b,
                0f, 0f, 0f, 1f, 0f,
            )))
        }

        return matrix
    }

    private fun leastSquaresFit(x: FloatArray, y: FloatArray): Pair<Float, Float> {
        val n = x.size.toFloat()
        val sumX = x.sum()
        val sumY = y.sum()
        val sumXY = x.indices.sumOf { i -> (x[i] * y[i]).toDouble() }.toFloat()
        val sumXX = x.indices.sumOf { i -> (x[i] * x[i]).toDouble() }.toFloat()
        val denom = n * sumXX - sumX * sumX
        if (denom == 0f) return Pair(1f, 0f)
        val a = (n * sumXY - sumX * sumY) / denom
        val b = (sumY - a * sumX) / n
        return Pair(a, b)
    }

    fun buildPaint(colorMatrix: ColorMatrix?): Paint? {
        if (colorMatrix == null) return null
        return Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
    }
}