package com.mangareader.panel

internal object PanelSpreadRotate {
    private const val MIN_SPREAD_ASPECT = 1.3f

    internal fun shouldRotate(bmpW: Int, bmpH: Int, screenW: Int, screenH: Int): Boolean {
        if (screenH <= screenW) return false
        if (bmpH <= 0) return false
        return bmpW.toFloat() / bmpH >= MIN_SPREAD_ASPECT
    }

    internal fun imageAngle(direction: Int, rtl: Boolean): Int = when (direction) {
        0 -> 270
        1 -> 90
        else -> if (rtl) 270 else 90
    }
}