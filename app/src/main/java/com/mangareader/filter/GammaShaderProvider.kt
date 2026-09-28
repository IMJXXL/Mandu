package com.mangareader.filter

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.os.Build

object GammaShaderProvider {

    private const val AGSL_GAMMA_SHADER = """
        uniform shader src;
        uniform half3 gamma;
        half4 main(float2 coord) {
            half4 color = src.eval(coord);
            color.rgb = pow(color.rgb, gamma);
            return color;
        }
    """

    fun createGammaRenderEffect(gamma: Int): RenderEffect? {
        if (Build.VERSION.SDK_INT < 33) return null
        if (gamma == 100) return null
        return try {
            val shader = android.graphics.RuntimeShader(AGSL_GAMMA_SHADER)
            val g = (gamma / 100f).coerceIn(0.1f, 4.0f)
            shader.setFloatUniform("gamma", g, g, g)
            RenderEffect.createRuntimeShaderEffect(shader, "src")
        } catch (_: Exception) {
            null
        }
    }

    fun getEffectivePaint(
        grayscale: Boolean,
        invert: Boolean,
        brightness: Int,
        contrast: Int,
        gamma: Int
    ): Paint? {
        val gammaShaderAvailable = Build.VERSION.SDK_INT >= 33 && gamma != 100
        val gammaApprox = if (!gammaShaderAvailable && gamma != 100) gamma else null
        val colorMatrix = ColorMatrixBuilder.buildCombinedColorMatrix(
            grayscale, invert, brightness, contrast, gammaApprox
        )
        return if (colorMatrix != null) {
            Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
        } else null
    }
}
