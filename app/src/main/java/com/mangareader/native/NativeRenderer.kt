package com.mangareader.native

object NativeRenderer {
    init {
        System.loadLibrary("native_renderer")
    }

    external fun nativeInit(viewWidth: Int, viewHeight: Int)
    external fun nativeDestroy()
    external fun nativeSetViewSize(w: Int, h: Int)
    external fun nativeLoadImage(data: ByteArray, len: Int): Int
    external fun nativeSetPage(page: Int)
    external fun nativeSetTotalPages(total: Int)
    external fun nativeZoom(factor: Float, cx: Float, cy: Float)
    external fun nativePan(dx: Float, dy: Float)
    external fun nativeResetZoom()
    external fun nativeStartFlip(direction: Int)
    external fun nativeUpdateFlip(progress: Float)
    external fun nativeClampOffset()
    external fun nativeGetScale(): Float
    external fun nativeGetOffsetX(): Float
    external fun nativeGetOffsetY(): Float
    external fun nativeGetCurrentPage(): Int
    external fun nativeGetTotalPages(): Int
    external fun nativeGetWidth(): Int
    external fun nativeGetHeight(): Int
    external fun nativeIsAnimating(): Boolean
    external fun nativeGetFlipProgress(): Float
    external fun nativeGetFlipDirection(): Int
    external fun nativeHitTest(x: Float, y: Float, viewW: Int, viewH: Int): Boolean
    external fun nativeGetTapAction(x: Float, y: Float, viewW: Int, viewH: Int, rtl: Boolean, clickFlipType: Int): Int
}
