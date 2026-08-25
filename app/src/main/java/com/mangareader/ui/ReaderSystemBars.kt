package com.mangareader.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager

fun hideSystemBars(activity: Activity?, isImmersiveMode: Boolean, isKeepScreenOn: Boolean) {
    activity?.let { a ->
        android.os.Handler(a.mainLooper).post {
            val w = a.window
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (!isImmersiveMode) {
                w.statusBarColor = android.graphics.Color.BLACK
                w.navigationBarColor = android.graphics.Color.BLACK
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                return@post
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val ctrl = w.insetsController ?: return@post
                    try { ctrl.hide(android.view.WindowInsets.Type.systemBars()) } catch (_: IllegalStateException) {}
                    ctrl.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    @Suppress("DEPRECATION")
                    var flags = w.decorView.systemUiVisibility
                    flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
                    flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    w.decorView.systemUiVisibility = flags
                }
                if (isKeepScreenOn) {
                    w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            } catch (_: Exception) {}
        }
    }
}

fun showSystemBars(activity: Activity?, isKeepScreenOn: Boolean) {
    activity?.let { a ->
        android.os.Handler(a.mainLooper).post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { a.window.insetsController?.show(android.view.WindowInsets.Type.systemBars()) } catch (_: IllegalStateException) {}
                } else {
                    @Suppress("DEPRECATION")
                    val decorView = a.window.decorView
                    var flags = decorView.systemUiVisibility
                    flags = flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
                    flags = flags and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
                    flags = flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
                    decorView.systemUiVisibility = flags
                }
                if (isKeepScreenOn) {
                    a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            } catch (_: Exception) {}
        }
    }
}

fun restoreSystemBarsSync(activity: Activity?) {
    activity?.let { a ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                a.window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                val decorView = a.window.decorView
                var flags = decorView.systemUiVisibility
                flags = flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
                flags = flags and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
                flags = flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
                decorView.systemUiVisibility = flags
            }
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }
}
