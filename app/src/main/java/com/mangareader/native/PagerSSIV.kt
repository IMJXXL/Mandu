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

    /** ★ 视口基准缩放（2026-10-02）：铺满屏幕/拆分视口把"初始显示比例"当未放大态——
     *  minScale 是整图 contain 比例，贴宽/视口定位的初始 scale 恒大于它，若按 minScale 判定
     *  会把初始态误判成"用户已放大"而吞掉翻页手势（拆分后滑动翻页失效的根因之一）。
     *  0 = 未设（回落 minScale，完整显示模式行为不变）。 */
    var viewportBaseScale = 0f

    /** ★ 手势分流主轴：true=横向滑动放行翻页（横向 pager）、纵向拖动看图（铺满屏幕划着看）；
     *  false=反向（竖向翻页 pager）。由 PagerReaderView 按 verticalPager 接线。 */
    var axisPassthroughHorizontal = true

    private fun gestureBaseScale(): Float = if (viewportBaseScale > 0f) viewportBaseScale else minScale

    private fun hasVerticalPanRoom(): Boolean = try {
        val rect = RectF()
        getPanRemaining(rect)
        rect.top > 1f || rect.bottom > 1f
    } catch (_: Exception) { false }

    private fun hasHorizontalPanRoom(): Boolean = try {
        val rect = RectF()
        getPanRemaining(rect)
        rect.left > 1f || rect.right > 1f
    } catch (_: Exception) { false }

    // 双击检测器
    private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val base = gestureBaseScale()
            if (scale <= base + 0.01f) {
                // 未放大：双击放大到2倍
                val targetScale = minOf(2f * base, maxScale)
                val center = PointF(e.x, e.y)
                animateScaleAndCenter(targetScale, center)
                    ?.withDuration(300)?.withInterruptible(true)?.start()
            } else {
                // 已放大：双击缩回视口基准
                val currentCenter = center ?: PointF(width / 2f, height / 2f)
                animateScaleAndCenter(base, currentCenter)
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

        val base = gestureBaseScale()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                consumedDown = false
                if (scale <= base + 0.01f) {
                    // 未放大：消费 DOWN 保留事件流，MOVE 按方向分流（翻页/拖图）
                    return true
                }
                // 已放大：让SSIV处理pan/zoom
                consumedDown = true
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    consumedDown = true
                    return super.onTouchEvent(event)
                }
                if (scale > base + 0.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return super.onTouchEvent(event)
                }
                // ★ 未放大方向分流：沿放行主轴的滑动交还翻页（ViewPager2），
                //   垂直轴且图上还有可拖空间 → 归 SSIV 拖动看图（铺满屏幕"划着看"）；
                //   无可拖空间（完整显示模式纵向贴边）→ 一并放行翻页，与旧行为一致
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                val alongAxis = if (axisPassthroughHorizontal) dx >= dy else dy >= dx
                if (alongAxis) return false
                val hasRoom = if (axisPassthroughHorizontal) hasVerticalPanRoom() else hasHorizontalPanRoom()
                if (!hasRoom) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                consumedDown = true
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!consumedDown) return false
            }
        }
        return super.onTouchEvent(event)
    }
}
