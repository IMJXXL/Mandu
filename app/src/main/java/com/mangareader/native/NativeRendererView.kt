package com.mangareader.native

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class NativeRendererView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var bitmap: Bitmap? = null
    private var onPageChanged: ((Int) -> Unit)? = null
    private var onToggleUi: (() -> Unit)? = null
    private var isRtl = false
    private var clickFlipType = 1

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pageNumBgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val pagePaint = Paint().apply {
        color = Color.WHITE; textSize = 32f; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTime = 0L
    private var isDragging = false

    private lateinit var scaleDetector: ScaleGestureDetector

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                NativeRenderer.nativeZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                NativeRenderer.nativeClampOffset()
                invalidate()
                return true
            }
            override fun onScaleBegin(detector: ScaleGestureDetector) = true
            override fun onScaleEnd(detector: ScaleGestureDetector) {}
        })
    }

    fun setOnPageChangedListener(listener: (Int) -> Unit) { onPageChanged = listener }
    fun setOnToggleUiListener(listener: () -> Unit) { onToggleUi = listener }
    fun setRtl(rtl: Boolean) { isRtl = rtl }
    fun setClickFlipType(type: Int) { clickFlipType = type }

    fun initRenderer() {
        if (width > 0 && height > 0) {
            NativeRenderer.nativeInit(width, height)
        }
    }

    fun loadImage(data: ByteArray) {
        if (width <= 0 || height <= 0) { post { loadImage(data) }; return }
        NativeRenderer.nativeInit(width, height)
        val result = NativeRenderer.nativeLoadImage(data, data.size)
        if (result == 0) {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            var s = 1
            while (opts.outWidth / s > 4096 || opts.outHeight / s > 4096) s *= 2
            bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
            NativeRenderer.nativeClampOffset()
            invalidate()
        }
    }

    fun setPage(page: Int) { NativeRenderer.nativeSetPage(page); invalidate() }
    fun setTotalPages(total: Int) { NativeRenderer.nativeSetTotalPages(total) }
    fun getCurrentPage() = NativeRenderer.nativeGetCurrentPage()
    fun getTotalPages() = NativeRenderer.nativeGetTotalPages()

    fun resetZoom() { NativeRenderer.nativeResetZoom(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            NativeRenderer.nativeSetViewSize(w, h)
            NativeRenderer.nativeResetZoom()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return

        val scale = NativeRenderer.nativeGetScale()
        val offsetX = NativeRenderer.nativeGetOffsetX()
        val offsetY = NativeRenderer.nativeGetOffsetY()

        val scaledW = bmp.width * scale
        val scaledH = bmp.height * scale
        val dstX = (width - scaledW) / 2f + offsetX
        val dstY = (height - scaledH) / 2f + offsetY

        canvas.drawBitmap(bmp, null, RectF(dstX, dstY, dstX + scaledW, dstY + scaledH), paint)

        val page = NativeRenderer.nativeGetCurrentPage()
        val total = NativeRenderer.nativeGetTotalPages()
        if (total > 0) {
            val text = "${page + 1}/$total"
            val textW = pagePaint.measureText(text)
            canvas.drawRoundRect(width / 2f - textW / 2f - 12f, height - 80f, width / 2f + textW / 2f + 12f, height - 44f, 20f, 20f, pageNumBgPaint)
            canvas.drawText(text, width / 2f, height - 52f, pagePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
                lastTouchTime = System.currentTimeMillis(); isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX; val dy = event.y - lastTouchY
                    if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) isDragging = true
                    NativeRenderer.nativePan(dx, dy)
                    NativeRenderer.nativeClampOffset()
                    lastTouchX = event.x; lastTouchY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dt = System.currentTimeMillis() - lastTouchTime
                if (!isDragging && dt < 300) {
                    val action = NativeRenderer.nativeGetTapAction(event.x, event.y, width, height, isRtl, clickFlipType)
                    when (action) { 0 -> onToggleUi?.invoke(); 1 -> onPageChanged?.invoke(-1); 2 -> onPageChanged?.invoke(1) }
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NativeRenderer.nativeDestroy()
        bitmap?.recycle(); bitmap = null
    }
}
