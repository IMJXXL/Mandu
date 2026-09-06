package com.ehviewer.core.mainthread

import android.os.Looper

internal val isMainThread: Boolean
    get() = Looper.getMainLooper().isCurrentThread

fun checkMainThread() {
    check(isMainThread) { "Must be called from the main thread" }
}

fun checkNotMainThread() {
    check(!isMainThread) { "Must not be called from the main thread" }
}
