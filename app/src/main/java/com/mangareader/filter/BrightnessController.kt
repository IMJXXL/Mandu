package com.mangareader.filter

import android.content.Context
import android.provider.Settings
import android.view.Window
import android.view.WindowManager

object BrightnessController {

    fun applyBrightness(value: Int, window: Window?, context: Context?, onOverlayAlpha: (Float) -> Unit) {
        val v = value.coerceIn(-100, 100)
        if (window != null) {
            when {
                v > 0 -> {
                    val baseline = getSystemBrightness(context)
                    window.attributes = window.attributes.apply {
                        this.screenBrightness = baseline + (1.0f - baseline) * (v / 100f)
                    }
                }
                v < 0 -> {
                    window.attributes = window.attributes.apply {
                        this.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
                else -> {
                    window.attributes = window.attributes.apply {
                        this.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
        }
        val alpha = if (v < 0) (-v / 100f) else 0f
        onOverlayAlpha(alpha)
    }

    private fun getSystemBrightness(context: Context?): Float {
        if (context == null) return 0.5f
        return try {
            val brightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            brightness.coerceIn(0, 255) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }
}
