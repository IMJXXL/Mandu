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
    // ★ 滑动诊断（2026-10-05）：每次手势只在分流判定点落一条短码——翻页滑动失效时
    //   区分"放行给了 ViewPager2"还是"被 SSIV 留下平移"以及当时缩放态
    private var swipeDiagMarked = false

    private fun markSwipe(context: Context, tag: String) {
        if (swipeDiagMarked) return
        swipeDiagMarked = true
        try {
            com.mangareader.DiagTracer.mark(context, "swipe $tag scale=${"%.2f".format(scale)} base=${"%.2f".format(gestureBaseScale())}")
        } catch (_: Throwable) {}
    }

    /** ★ 视口基准缩放（2026-10-02）：铺满屏幕/拆分视口把"初始显示比例"当未放大态——
     *  minScale 是整图 contain 比例，贴宽/视口定位的初始 scale 恒大于它，若按 minScale 判定
     *  会把初始态误判成"用户已放大"而吞掉翻页手势（拆分后滑动翻页失效的根因之一）。
     *  0 = 未设（回落 minScale，完整显示模式行为不变）。 */
    var viewportBaseScale = 0f
        set(value) {
            field = value
            // ★ 2026-10-05 工单"应该能缩放到更小"：SSIV 默认 min=contain 贴边，铺满/贴宽基准下
            //   pinch-out 到贴边就停。基准已知时把下限放到 0.5×基准——能看全貌，双击/手势仍回基准。
            //   0 = 未设（完整显示模式行为不变，min 仍为 contain）。
            if (value > 0f) {
                runCatching { setMinScale(value * 0.5f) }
            }
        }

    /** ★ 手势分流主轴：true=横向滑动放行翻页（横向 pager）、纵向拖动看图（铺满屏幕划着看）；
     *  false=反向（竖向翻页 pager）。由 PagerReaderView 按 verticalPager 接线。 */
    var axisPassthroughHorizontal = true

    /** ★ 双击放大开关（2026-10-05）：false=双击不触发缩放（快速连点翻页与双击冲突的解法），
     *  双击事件直接放行不消费。由 PagerReaderView 按 config.isDoubleTapZoom 接线。 */
    var doubleTapZoom = true

    /** ★ 滑动兜底（2026-10-05，"zip 单页滑动不翻页"普通模式防线）：未放大态沿主轴的滑动
     *  靠"放行→ViewPager2 内部 RecyclerView 拦截接管"翻页——个别 ROM/系统版本拦截行为
     *  异常时滑动就死（点击仍能翻）。两个时机显式兜底：onFling（沿主轴快速滑动）与
     *  UP（位移超 30% 视口的慢拖），交 pager 翻一页。正常设备 VP2 拦截后本视图收到
     *  CANCEL，这两处根本不触发（零双重翻页）；短码 fling-fb / up-fb 证明走到了兜底。 */
    var pagerSwipeFallback: ((dir: Int) -> Unit)? = null
    private var fallbackFired = false

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
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (fallbackFired || scale > gestureBaseScale() + 0.01f) return false
            val alongAxis = if (axisPassthroughHorizontal)
                kotlin.math.abs(vx) >= kotlin.math.abs(vy) else kotlin.math.abs(vy) >= kotlin.math.abs(vx)
            if (!alongAxis) return false
            val v = if (axisPassthroughHorizontal) vx else vy
            fallbackFired = true
            markSwipe(context, "fling-fb")
            pagerSwipeFallback?.invoke(if (v < 0) 1 else -1)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // ★ 双击放大关闭：不消费不动作——连点快速翻页不再被双击判定打断
            if (!doubleTapZoom) return false
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
                swipeDiagMarked = false
                fallbackFired = false
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
                    markSwipe(context, "hold-zoomedPan")
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return super.onTouchEvent(event)
                }
                // ★ 未放大方向分流：沿放行主轴的滑动交还翻页（ViewPager2），
                //   垂直轴且图上还有可拖空间 → 归 SSIV 拖动看图（铺满屏幕"划着看"）；
                //   无可拖空间（完整显示模式纵向贴边）→ 一并放行翻页，与旧行为一致
                // ★ 2026-10-05 横屏滑动翻页+贴高拖图双修：主轴拖动先看"该轴有无可拖余量"——
                //   贴高/缩放超出(有余量)→归拖图；铺满贴边(无余量)→放行翻页。
                //   判拖图还需正交分量明显占优(×1.25)：横屏拇指弧线 dx≈dy 旧判据全归拖图，
                //   铺满下永不翻页（工单实锤 hold-pan 连发）。
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                val alongAxis = if (axisPassthroughHorizontal) dy < dx * 1.25f else dx < dy * 1.25f
                if (alongAxis) {
                    val alongRoom = if (axisPassthroughHorizontal) hasHorizontalPanRoom() else hasVerticalPanRoom()
                    if (alongRoom) {
                        markSwipe(context, "hold-pan")
                        parent?.requestDisallowInterceptTouchEvent(true)
                        consumedDown = true
                        return super.onTouchEvent(event)
                    }
                    markSwipe(context, "pass-flip"); return false
                }
                val hasRoom = if (axisPassthroughHorizontal) hasVerticalPanRoom() else hasHorizontalPanRoom()
                if (!hasRoom) { markSwipe(context, "v-noPan-pass"); return false }
                markSwipe(context, "hold-pan")
                parent?.requestDisallowInterceptTouchEvent(true)
                consumedDown = true
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // ★ UP 兜底（仅 UP；VP2 拦截后会发 CANCEL，正常设备走不到这里）：
                // 未放大态沿主轴慢拖超 30% 视口且拦截没接管 → 显式翻一页
                if (event.actionMasked == MotionEvent.ACTION_UP && !consumedDown && !fallbackFired &&
                    scale <= base + 0.01f && pagerSwipeFallback != null
                ) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val alongAxis = if (axisPassthroughHorizontal)
                        kotlin.math.abs(dx) >= kotlin.math.abs(dy) else kotlin.math.abs(dy) >= kotlin.math.abs(dx)
                    val d = if (axisPassthroughHorizontal) dx else dy
                    val extent = if (axisPassthroughHorizontal) width else height
                    if (alongAxis && extent > 0 && kotlin.math.abs(d) > extent * 0.3f) {
                        fallbackFired = true
                        markSwipe(context, "up-fb")
                        pagerSwipeFallback?.invoke(if (d < 0) 1 else -1)
                    }
                }
                if (!consumedDown) return false
            }
        }
        return super.onTouchEvent(event)
    }
}
