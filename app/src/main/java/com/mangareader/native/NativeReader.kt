package com.mangareader.native

import android.graphics.Bitmap
import android.graphics.Canvas

object NativeReader {
    init {
        System.loadLibrary("native_renderer")
        System.loadLibrary("native_engine")
        System.loadLibrary("native_reader")
    }

    private external fun nativeInit(viewWidth: Int, viewHeight: Int, isVertical: Boolean)
    private external fun nativeDestroy()
    private external fun nativeSetPagePool(startIdx: Int, bitmaps: Array<Bitmap>)
    private external fun nativeSetCurrentPage(page: Int)
    private external fun nativeGetCurrentPage(): Int
    private external fun nativeGetTotalPages(): Int
    private external fun nativeSetTotalPages(total: Int)
    private external fun nativeOnTouchEvent(action: Int, x1: Float, y1: Float, x2: Float, y2: Float, pointerCount: Int): Int
    private external fun nativeRender(canvas: Canvas)
    private external fun nativeScrollBy(dy: Float)
    private external fun nativeSetClickFlipType(type: Int)
    private external fun nativeSetVertical(vertical: Boolean)
    private external fun nativeSetViewSize(w: Int, h: Int)
    private external fun nativeGetScale(): Float
    private external fun nativeGetScrollY(): Float

    fun init(vw: Int, vh: Int, vert: Boolean) = nativeInit(vw, vh, vert)
    fun destroy() = nativeDestroy()
    fun setPagePool(startIdx: Int, bitmaps: Array<Bitmap>) = nativeSetPagePool(startIdx, bitmaps)
    fun setCurrentPage(page: Int) = nativeSetCurrentPage(page)
    fun getCurrentPage(): Int = nativeGetCurrentPage()
    fun getTotalPages(): Int = nativeGetTotalPages()
    fun setTotalPages(total: Int) = nativeSetTotalPages(total)
    fun onTouchEvent(action: Int, x1: Float, y1: Float, x2: Float = 0f, y2: Float = 0f, pc: Int = 1): Int = nativeOnTouchEvent(action, x1, y1, x2, y2, pc)
    fun render(canvas: Canvas) = nativeRender(canvas)
    fun scrollBy(dy: Float) = nativeScrollBy(dy)
    fun setClickFlipType(type: Int) = nativeSetClickFlipType(type)
    fun setVertical(vert: Boolean) = nativeSetVertical(vert)
    fun setViewSize(w: Int, h: Int) = nativeSetViewSize(w, h)
    fun getScale(): Float = nativeGetScale()
    fun getScrollY(): Float = nativeGetScrollY()
}
