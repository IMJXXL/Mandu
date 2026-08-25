package com.mangareader.native

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

class PagerSSIV @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SubsamplingScaleImageView(context, attrs) {

    private var downX = 0f
    private var downY = 0f
    private var consumedDown = false

    // 双击检测器
    private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale <= minScale + 0.01f) {
                // 未缩放：双击放大到2倍
                val targetScale = minOf(2f, maxScale)
                val center = PointF(e.x, e.y)
                animateScaleAndCenter(targetScale, center)
                    ?.withDuration(300)?.withInterruptible(true)?.start()
            } else {
                // 已缩放：双击缩小回原始大小
                val currentCenter = center ?: PointF(width / 2f, height / 2f)
                animateScaleAndCenter(minScale, currentCenter)
                    ?.withDuration(300)?.withInterruptible(true)?.start()
            }
            return true
        }
    })

    // ---- navigateToPan: 平移辅助 ----

    fun canPanLeft(): Boolean = canPan { it.left }
    fun canPanRight(): Boolean = canPan { it.right }

    private fun canPan(fn: (RectF) -> Float): Boolean {
        if (visibility != android.view.View.VISIBLE || scale <= minScale + 0.01f) return false
        return try {
            val rect = RectF()
            getPanRemaining(rect)
            fn(rect) > 1f
        } catch (_: Exception) { false }
    }

    fun panLeft() { pan { c, v -> c.also { it.x -= v.width / v.scale } } }
    fun panRight() { pan { c, v -> c.also { it.x += v.width / v.scale } } }

    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        val c = center ?: return
        val target = fn(c, this)
        animateCenter(target)
            ?.withDuration(250)
            ?.withInterruptible(true)?.start()
    }

    // ---- 触摸处理 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 始终让双击检测器处理
        doubleTapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                consumedDown = false
                if (scale <= minScale + 0.01f) {
                    // 未缩放：不调super，让ViewPager2处理翻页手势
                    return true
                }
                // 已缩放：让SSIV处理pan/zoom
                consumedDown = true
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    consumedDown = true
                    return super.onTouchEvent(event)
                }
                if (scale > minScale + 0.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return super.onTouchEvent(event)
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!consumedDown) return false
            }
        }
        return super.onTouchEvent(event)
    }
}
