package com.hippo.ehviewer.util

import android.app.Activity
import android.content.pm.ActivityInfo
import com.hippo.ehviewer.Settings

/**
 * Reader window color mode: HDR / WCG / SDR.
 * Call [setReaderColorMode] on enter reader, [restoreColorMode] on leave.
 */
fun setReaderColorMode(activity: Activity) {
    val hdr = Settings.readerHdrDisplay.value
    val wcg = Settings.readerAdvancedColor.value
    val mode = when {
        hdr && wcg -> ActivityInfo.COLOR_MODE_HDR
        wcg -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        else -> ActivityInfo.COLOR_MODE_DEFAULT
    }
    runCatching { activity.window.setColorMode(mode) }
}

fun restoreColorMode(activity: Activity) {
    runCatching { activity.window.setColorMode(ActivityInfo.COLOR_MODE_DEFAULT) }
}