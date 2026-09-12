package com.mangareader.ui

object VolumeKeyHandler {
    @Volatile var enabled: Boolean = false
    @Volatile var inverted: Boolean = false
    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrev: (() -> Unit)? = null
    @Volatile var menuVisible: Boolean = false
}