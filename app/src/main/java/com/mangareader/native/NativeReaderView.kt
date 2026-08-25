package com.mangareader.native

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class NativeReaderView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val bgPaint = Paint().apply { color = Color.parseColor("#111111") }
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val zoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val zoomBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) }
    private val overscrollGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var zoomIndicatorAlpha = 0f
    private var zoomIndicatorTime = 0L

    private var engine: ArchiveEngine? = null
    private var treePath: String = ""
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var drawJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private var currentPage = 0
    private var isVertical = true
    private var clickFlipType = 1
    private var isRtl = false

    private var scrollY = 0f
    private var flipOffsetX = 0f
    private var flipAnimating = false
    private var flipDirection = 0
    private var firstPageLoaded = false

    private var offsetX = 0f
    private var offsetY = 0f
    private var scale = 1f
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTime = 0L
    private var velocityY = 0f
    private var pointerCount = 0
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f
    private var pinchStartOffsetX = 0f
    private var pinchStartOffsetY = 0f
    private val minScale = 1f
    private val maxScale = 5f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var hasPendingTap = false
    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private val tapTimeoutRunnable = Runnable {
        if (hasPendingTap) {
            hasPendingTap = false
            if (isVertical) onToggleUi?.invoke()
            else {
                val fx = pendingTapX / surfaceWidth
                val leftAction = if (isRtl) ::nextPage else ::prevPage
                val rightAction = if (isRtl) ::prevPage else ::nextPage
                when {
                    clickFlipType == 1 && fx < 0.35f -> leftAction()
                    clickFlipType == 1 && fx > 0.65f -> rightAction()
                    else -> onToggleUi?.invoke()
                }
            }
        }
    }
    private val fadeOutRunnable = Runnable { renderFrame() }

    private val pageCache = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
    private val pageHeightCache = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val scroller = OverScroller(context, DecelerateInterpolator(1.2f))

    private var nativeArchive: com.mangareader.native.NativeEngine.Archive? = null
    private var nativeArchivePath = ""

    private var overscrollY = 0f
    private var isOverscrolling = false
    private var lastFrameTime = 0L

    private val velocitySamples = FloatArray(5)
    private var velocitySampleIdx = 0

    private val renderRunnable = Runnable { renderFrame() }

    private val flingFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = System.nanoTime()
            val dtMs = if (lastFrameTime > 0) (now - lastFrameTime) / 1_000_000L else 16L
            lastFrameTime = now

            if (scroller.isFinished) {
                if (isOverscrolling) {
                    overscrollY *= 0.85f
                    if (abs(overscrollY) < 0.3f) { overscrollY = 0f; isOverscrolling = false }
                    renderFrame()
                    if (isOverscrolling) Choreographer.getInstance().postFrameCallback(this)
                    else updateCurrentPage()
                } else {
                    updateCurrentPage()
                }
                return
            }
            scroller.computeScrollOffset()
            scrollY = scroller.currY.toFloat()
            val maxY = maxScrollY()
            if (scrollY < 0) { overscrollY = scrollY; scrollY = 0f; isOverscrolling = true }
            else if (scrollY > maxY) { overscrollY = scrollY - maxY; scrollY = maxY; isOverscrolling = true }
            else { overscrollY = 0f; isOverscrolling = false }
            renderFrame()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    var onPageChanged: ((Int) -> Unit)? = null
    var onToggleUi: (() -> Unit)? = null

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun setup(
        engine: ArchiveEngine,
        treePath: String,
        pages: List<ArchiveEngine.PageMeta>,
        startPage: Int,
        vertical: Boolean,
        clickFlip: Int,
        rtl: Boolean = false
    ) {
        val modeChanged = this.isVertical != vertical || this.clickFlipType != clickFlip || this.isRtl != rtl
        val pagesChanged = this.pageList.size != pages.size || this.treePath != treePath

        this.engine = engine
        this.treePath = treePath
        this.pageList = pages
        this.isVertical = vertical
        this.clickFlipType = clickFlip
        this.isRtl = rtl

        if (pagesChanged || modeChanged) {
            this.currentPage = startPage
            scrollY = 0f; offsetX = 0f; offsetY = 0f; scale = 1f
            flipOffsetX = 0f; flipAnimating = false
            overscrollY = 0f; isOverscrolling = false
            if (pagesChanged) {
                firstPageLoaded = false
                pageCache.clear(); pageHeightCache.clear()
            } else {
                pageHeightCache.clear()
            }
            scroller.forceFinished(true)
            Choreographer.getInstance().removeFrameCallback(flingFrameCallback)
            removeCallbacks(tapTimeoutRunnable); hasPendingTap = false
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                renderFrame()
                loadPagesAround(startPage)
            }
        } else if (startPage != this.currentPage) {
            goToPage(startPage)
        } else if (surfaceWidth > 0) {
            renderFrame()
        }
    }

    private fun maxScrollY(): Float = (getPageDisplayHeight(pageList.size) - surfaceHeight).coerceAtLeast(0f)

    private fun clampOffset() {
        if (scale <= 1.01f) { offsetX = 0f; offsetY = 0f; return }
        val maxX = (surfaceWidth * (scale - 1)) / 2
        val maxY = (surfaceHeight * (scale - 1)) / 2
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    private fun getPageDisplayHeight(pageIdx: Int): Float {
        if (surfaceWidth <= 0) return 400f
        if (pageIdx >= pageList.size) {
            var total = 0f; for (i in pageList.indices) total += getPageDisplayHeight(i); return total
        }
        pageHeightCache[pageIdx]?.let { return it }
        val bmp = pageCache[pageIdx]
        val h = if (bmp != null && bmp.width > 0) bmp.height.toFloat() / bmp.width * surfaceWidth else surfaceWidth * 1.4f
        pageHeightCache[pageIdx] = h; return h
    }

    private suspend fun ensureNativeArchive() {
        val eng = engine ?: return
        val path = withContext(Dispatchers.IO) { try { eng.getRealFileForPath(treePath) } catch (_: Exception) { "" } }
        if (path.isEmpty()) return
        if (path == nativeArchivePath && nativeArchive != null) return
        nativeArchive?.close()
        nativeArchive = withContext(Dispatchers.IO) { try { com.mangareader.native.NativeEngine.openArchive(path) } catch (_: Exception) { null } }
        nativeArchivePath = path
    }

    private fun loadPagesAround(centerIdx: Int) {
        val eng = engine ?: return
        val hasCenter = pageCache.containsKey(centerIdx)
        if (!hasCenter) drawJob?.cancel()
        drawJob = scope.launch {
            ensureNativeArchive()
            val screenMax = maxOf(surfaceWidth, surfaceHeight).coerceAtLeast(1)
            val preloadForward = if (isVertical) 10 else 5
            val preloadBack = 4
            val toLoad = mutableListOf<Int>()
            for (i in 0..preloadForward) { val idx = centerIdx + i; if (idx < pageList.size && !pageCache.containsKey(idx)) toLoad.add(idx) }
            for (i in 1..preloadBack) { val idx = centerIdx - i; if (idx >= 0 && !pageCache.containsKey(idx)) toLoad.add(idx) }
            for (idx in toLoad) {
                try {
                    val bmp = withContext(Dispatchers.IO) {
                        var result: Bitmap? = null
                        val meta = pageList[idx]
                        // 所有格式统一走 Java BitmapFactory（正确处理 CMYK/ICC 色彩空间）
                        result = eng.loadPage(pageList[idx], treePath)
                        result
                    }
                    if (bmp != null && !bmp.isRecycled) {
                        pageCache[idx] = bmp; pageHeightCache.remove(idx)
                        if (idx == centerIdx && !firstPageLoaded) {
                            firstPageLoaded = true
                            withContext(Dispatchers.Main) { renderFrame() }
                        }
                    }
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) { renderFrame() }
        }
    }

    fun goToPage(page: Int) {
        if (page < 0 || page >= pageList.size) return
        if (page == currentPage && firstPageLoaded) return
        currentPage = page
        if (isVertical) {
            scrollY = 0f; for (i in 0 until page) scrollY += getPageDisplayHeight(i)
        } else { flipOffsetX = 0f; flipAnimating = false }
        scale = 1f; offsetX = 0f; offsetY = 0f
        loadPagesAround(page); onPageChanged?.invoke(page)
    }

    fun nextPage() { if (currentPage < pageList.size - 1) goToPage(currentPage + 1) }
    fun prevPage() { if (currentPage > 0) goToPage(currentPage - 1) }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceWidth = width; surfaceHeight = height
        pageHeightCache.clear()
        if (!firstPageLoaded) firstPageLoaded = pageCache.isNotEmpty()
        renderFrame()
        if (!pageCache.containsKey(currentPage)) loadPagesAround(currentPage)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        surfaceWidth = w; surfaceHeight = h; pageHeightCache.clear()
        renderFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        drawJob?.cancel(); Choreographer.getInstance().removeFrameCallback(flingFrameCallback)
    }

    private fun sampleVelocity(dy: Float, dt: Long) {
        if (dt <= 0) return
        val v = dy / dt * 1000f
        velocitySamples[velocitySampleIdx % velocitySamples.size] = v
        velocitySampleIdx++
        val count = minOf(velocitySampleIdx, velocitySamples.size)
        var sum = 0f; for (i in 0 until count) sum += velocitySamples[i]
        velocityY = sum / count
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x1 = event.getX(0); val y1 = event.getY(0)
        val x2 = if (event.pointerCount > 1) event.getX(1) else 0f
        val y2 = if (event.pointerCount > 1) event.getY(1) else 0f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x1; lastTouchY = y1; lastTouchTime = System.currentTimeMillis()
                velocityY = 0f; isDragging = false; pointerCount = 1
                velocitySampleIdx = 0; velocitySamples.fill(0f)
                lastFrameTime = 0
                scroller.forceFinished(true)
                Choreographer.getInstance().removeFrameCallback(flingFrameCallback)
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging && pointerCount == 1) {
                    val now = System.currentTimeMillis()
                    val isDoubleTap = hasPendingTap && now - lastTapTime < 350 &&
                        abs(x1 - lastTapX) < 60 && abs(y1 - lastTapY) < 60
                    if (isDoubleTap) {
                        removeCallbacks(tapTimeoutRunnable); hasPendingTap = false
                        if (scale > 1.01f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                            zoomIndicatorTime = System.currentTimeMillis(); renderFrame()
                        }
                        lastTapTime = 0L
                    } else {
                        hasPendingTap = true; lastTapTime = now; lastTapX = x1; lastTapY = y1
                        pendingTapX = x1; pendingTapY = y1
                        removeCallbacks(tapTimeoutRunnable); postDelayed(tapTimeoutRunnable, 200)
                    }
                }
                if (!isVertical && abs(flipOffsetX) > surfaceWidth * 0.25f) {
                    if (isRtl) { if (flipOffsetX > 0) nextPage() else prevPage() }
                    else { if (flipOffsetX > 0) prevPage() else nextPage() }
                }
                if (isVertical && scale <= 1.01f && abs(velocityY) > 300f) {
                    val vel = (velocityY * 1.2f).toInt()
                    scroller.fling(0, scrollY.toInt(), 0, -vel, 0, 0, 0, maxScrollY().toInt())
                    lastFrameTime = System.nanoTime()
                    Choreographer.getInstance().postFrameCallback(flingFrameCallback)
                }
                pointerCount = 0; isDragging = false; pinchStartDist = 0f; pinchStartScale = scale
                flipOffsetX = 0f; flipAnimating = false
                updateCurrentPage()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x1 - lastTouchX; val dy = y1 - lastTouchY
                if (pointerCount == 1) {
                    if (abs(dx) > 5 || abs(dy) > 5) {
                        if (hasPendingTap) { removeCallbacks(tapTimeoutRunnable); hasPendingTap = false }
                    }
                    val dt = System.currentTimeMillis() - lastTouchTime
                    if (isVertical && scale <= 1.01f) sampleVelocity(dy, dt.coerceAtLeast(1))
                    if (isVertical) {
                        if (scale > 1.01f) {
                            offsetX += dx; offsetY += dy; clampOffset()
                        } else {
                            var newScrollY = scrollY - dy
                            val maxY = maxScrollY()
                            if (newScrollY < 0) { newScrollY *= 0.4f; overscrollY = newScrollY }
                            else if (newScrollY > maxY) { newScrollY = maxY + (newScrollY - maxY) * 0.4f; overscrollY = newScrollY - maxY }
                            else { overscrollY = 0f }
                            scrollY = newScrollY.coerceIn(0f, maxY)
                            isOverscrolling = overscrollY != 0f
                        }
                    } else {
                        if (scale > 1.01f) { offsetX += dx; offsetY += dy; clampOffset() }
                        else { flipOffsetX += dx; flipAnimating = true; flipDirection = if (dx < 0) 1 else -1 }
                    }
                    lastTouchX = x1; lastTouchY = y1; lastTouchTime = System.currentTimeMillis(); isDragging = true
                    if (isVertical && scale <= 1.01f) updateCurrentPage()
                } else if (pointerCount >= 2) {
                    if (hasPendingTap) { removeCallbacks(tapTimeoutRunnable); hasPendingTap = false }
                    val d = Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
                    if (pointerCount < 2) {
                        pinchStartDist = d; pinchStartScale = scale
                        pinchStartOffsetX = offsetX; pinchStartOffsetY = offsetY
                    }
                    val ns = (pinchStartScale * (d / pinchStartDist)).coerceIn(minScale, maxScale)
                    if (ns <= 1.01f) { offsetX = 0f; offsetY = 0f }
                    else {
                        val cx = (x1 + x2) / 2; val cy = (y1 + y2) / 2; val r = ns / scale
                        offsetX = cx - (cx - offsetX) * r; offsetY = cy - (cy - offsetY) * r; clampOffset()
                    }
                    scale = ns; isDragging = true; zoomIndicatorTime = System.currentTimeMillis()
                }
                pointerCount = event.pointerCount; renderFrame()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                pointerCount = event.pointerCount
                if (pointerCount >= 2) {
                    pinchStartDist = Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
                    pinchStartScale = scale; pinchStartOffsetX = offsetX; pinchStartOffsetY = offsetY
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerCount = 0; isDragging = false
                removeCallbacks(tapTimeoutRunnable); hasPendingTap = false
            }
        }
        return true
    }

    private fun updateCurrentPage() {
        if (!isVertical || pageList.isEmpty()) return
        var y = 0f
        for (i in pageList.indices) {
            val ph = getPageDisplayHeight(i)
            val screenMid = scrollY + surfaceHeight / 2f
            if (screenMid >= y && screenMid < y + ph) {
                if (i != currentPage) { currentPage = i; onPageChanged?.invoke(i); loadPagesAround(i) }
                return
            }
            y += ph
        }
        if (scrollY >= y - surfaceHeight * 0.5f && pageList.isNotEmpty()) {
            val last = pageList.size - 1
            if (currentPage != last) { currentPage = last; onPageChanged?.invoke(last); loadPagesAround(last) }
        }
    }

    private fun renderFrame() {
        val h = holder
        if (!h.surface.isValid || surfaceWidth <= 0) return
        var canvas: Canvas? = null
        try {
            canvas = h.lockCanvas() ?: return
            canvas.drawPaint(bgPaint)
            if (isVertical) renderVertical(canvas) else renderHorizontal(canvas)
            if (scale > 1.01f) {
                val now = System.currentTimeMillis()
                if (now - zoomIndicatorTime < 1200) zoomIndicatorAlpha = 1f
                else if (zoomIndicatorAlpha > 0f) zoomIndicatorAlpha = (zoomIndicatorAlpha - 0.06f).coerceAtLeast(0f)
                if (zoomIndicatorAlpha > 0f) {
                    val text = "${String.format("%.1f", scale)}x"
                    val tw = zoomPaint.measureText(text); val th = zoomPaint.textSize
                    val cx = surfaceWidth / 2f; val cy = surfaceHeight - 120f
                    zoomBgPaint.alpha = (160 * zoomIndicatorAlpha).toInt()
                    canvas.drawRoundRect(cx - tw / 2 - 20f, cy - th / 2 - 10f, cx + tw / 2 + 20f, cy + th / 2 + 10f, 24f, 24f, zoomBgPaint)
                    zoomPaint.alpha = (255 * zoomIndicatorAlpha).toInt()
                    canvas.drawText(text, cx, cy + th / 3, zoomPaint)
                    if (zoomIndicatorAlpha < 1f) post(fadeOutRunnable)
                }
            }
            if (isOverscrolling && abs(overscrollY) > 0.5f) {
                val alpha = (abs(overscrollY) / 100f * 80f).toInt().coerceIn(0, 80)
                overscrollGlowPaint.color = Color.argb(alpha, 100, 100, 255)
                if (overscrollY < 0) canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), 4f, overscrollGlowPaint)
                else canvas.drawRect(0f, surfaceHeight - 4f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), overscrollGlowPaint)
            }
        } catch (_: Exception) {} finally {
            try { canvas?.let { h.unlockCanvasAndPost(it) } } catch (_: Exception) {}
        }
    }

    private fun renderVertical(canvas: Canvas) {
        val screenTop = scrollY - surfaceHeight
        val screenBottom = scrollY + surfaceHeight * 2
        var y = 0f
        for (i in pageList.indices) {
            val ph = getPageDisplayHeight(i)
            if (y + ph < screenTop) { y += ph; continue }
            if (y > screenBottom) break
            val bmp = pageCache[i]
            if (bmp != null && !bmp.isRecycled) {
                val dw = surfaceWidth.toFloat() * scale; val dh = ph * scale
                val dx = offsetX; val dy = (y - scrollY) * scale + offsetY
                srcRect.set(0, 0, bmp.width, bmp.height); dstRect.set(dx, dy, dx + dw, dy + dh)
                try { canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint) } catch (_: Exception) {}
            }
            y += ph
        }
    }

    private fun renderHorizontal(canvas: Canvas) {
        val bmp = pageCache[currentPage]
        if (bmp != null && !bmp.isRecycled) {
            val fs = minOf(surfaceWidth.toFloat() / bmp.width, surfaceHeight.toFloat() / bmp.height) * scale
            val dw = bmp.width * fs; val dh = bmp.height * fs
            val dx = (surfaceWidth - dw) / 2 + offsetX + flipOffsetX
            val dy = (surfaceHeight - dh) / 2 + offsetY
            srcRect.set(0, 0, bmp.width, bmp.height); dstRect.set(dx, dy, dx + dw, dy + dh)
            try { canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint) } catch (_: Exception) {}
        }
        if (flipAnimating && flipOffsetX != 0f) {
            val prevIdx = if (flipDirection > 0) currentPage - 1 else currentPage + 1
            if (prevIdx in 0 until pageList.size) {
                val prevBmp = pageCache[prevIdx]
                if (prevBmp != null && !prevBmp.isRecycled) {
                    val fs = minOf(surfaceWidth.toFloat() / prevBmp.width, surfaceHeight.toFloat() / prevBmp.height) * scale
                    val dw = prevBmp.width * fs; val dh = prevBmp.height * fs
                    val baseX = if (flipDirection > 0) surfaceWidth.toFloat() + flipOffsetX else flipOffsetX - surfaceWidth
                    val dx = (surfaceWidth - dw) / 2 + baseX + offsetX
                    val dy = (surfaceHeight - dh) / 2 + offsetY
                    srcRect.set(0, 0, prevBmp.width, prevBmp.height); dstRect.set(dx, dy, dx + dw, dy + dh)
                    try { canvas.drawBitmap(prevBmp, srcRect, dstRect, drawPaint) } catch (_: Exception) {}
                }
            }
        }
    }

    fun release() {
        drawJob?.cancel()
        Choreographer.getInstance().removeFrameCallback(flingFrameCallback)
        removeCallbacks(tapTimeoutRunnable); removeCallbacks(renderRunnable)
        nativeArchive?.close(); nativeArchive = null; nativeArchivePath = ""
        pageCache.clear(); pageHeightCache.clear()
    }
}
