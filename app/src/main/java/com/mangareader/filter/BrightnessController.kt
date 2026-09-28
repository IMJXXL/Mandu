package com.mangareader.filter

import android.view.Window
import android.view.WindowManager

object BrightnessController {

    fun applyBrightness(value: Int, window: Window?, onOverlayAlpha: (Float) -> Unit) {
        val v = value.coerceIn(-100, 100)
        if (window != null) {
            val screenBrightness = when {
                v > 0 -> v / 100f
                v < 0 -> 0.01f
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { this.screenBrightness = screenBrightness }
        }
        val alpha = if (v < 0) (-v / 100f) else 0f
        onOverlayAlpha(alpha)
    }
}