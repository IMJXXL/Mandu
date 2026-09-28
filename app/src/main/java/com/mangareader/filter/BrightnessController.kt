package com.mangareader.filter

import android.content.Context
import android.provider.Settings
import android.view.Window
import android.view.WindowManager

object BrightnessController {

    // 正档区间内的插值基线。NaN=未取。一次正档会话只取一次，防止拖动中环境光变化导致 v 越调越暗
    private var cachedBaseline = Float.NaN

    fun applyBrightness(value: Int, window: Window?, context: Context?, onOverlayAlpha: (Float) -> Unit) {
        val v = value.coerceIn(-100, 100)
        if (window != null) {
            when {
                v > 0 -> {
                    if (cachedBaseline.isNaN()) {
                        cachedBaseline = currentBrightness(context).coerceIn(0.02f, 1f)
                    }
                    val baseline = cachedBaseline
                    window.attributes = window.attributes.apply {
                        this.screenBrightness = baseline + (1.0f - baseline) * (v / 100f)
                    }
                }
                else -> {
                    cachedBaseline = Float.NaN
                    window.attributes = window.attributes.apply {
                        this.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
        }
        val alpha = if (v < 0) (-v / 100f) else 0f
        onOverlayAlpha(alpha)
    }

    /** 进入阅读器或回到负/零档前调用，丢弃旧基线 */
    fun reset() {
        cachedBaseline = Float.NaN
    }

    /**
     * 当前实际亮度。优先读隐藏键 screen_brightness_float（Android 9+，自适应亮度开启时
     * 它由系统背光曲线实时更新，反映真实亮度；SCREEN_BRIGHTNESS int 档只存手动滑条位置，
     * 自动亮度下会偏离实际值导致 v=1 跳变），键不存在再退回 int 档。
     */
    private fun currentBrightness(context: Context?): Float {
        if (context == null) return 0.5f
        return try {
            Settings.System.getFloat(context.contentResolver, "screen_brightness_float")
        } catch (_: Exception) {
            try {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                ).coerceIn(0, 255) / 255f
            } catch (_: Exception) {
                0.5f
            }
        }
    }
}
