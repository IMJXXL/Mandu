package com.mangareader.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

object HapticIntensityController {

    fun amplitudeForLevel(level: Int): Int {
        if (level <= 0) return -1
        val clamped = level.coerceIn(1, 10)
        return 26 + (clamped - 1) * (255 - 26) / 9
    }

    fun supportsAmplitudeControl(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return false
        return vibrator.hasAmplitudeControl()
    }

    fun performHaptic(
        context: Context,
        haptic: HapticFeedback,
        intensity: Int
    ) {
        val level = intensity.coerceIn(0, 10)
        if (level == 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val vibrator = context.getSystemService(Vibrator::class.java)
                if (vibrator != null && vibrator.hasAmplitudeControl()) {
                    val amplitude = amplitudeForLevel(level)
                    vibrator.vibrate(VibrationEffect.createOneShot(30, amplitude))
                    return
                }
            } catch (_: SecurityException) {
            }
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}