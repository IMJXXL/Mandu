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
            // ★ mihon 对齐：maxScale=初始 scale×5（MAX_ZOOM_SCALE），小图档不至于 5 绝对值≈16×基准
            if (value > 0f) {
                runCatching { setMinScale(value * 0.5f) }
                runCatching { setMaxScale(value * 5f) }
            }
        }

    /** ★ 手势分流主轴：true=横向滑动放行翻页（横向 pager）、纵向拖动看图（铺满屏幕划着看）；
     *  false=反向（竖向翻页 pager）。由 PagerReaderView 按 verticalPager 接线。 */
    var axisPassthroughHorizontal = true

    /** ★ 双击放大开关（2026-10-05）：false=双击不触发缩放（快速连点翻页与双击冲突的解法），
     *  双击事件直接放行不消费。由 PagerReaderView 按 config.isDoubleTapZoom 接线。 */
    var doubleTapZoom = true

    /** ★ 未放大态平移接管标记（2026-10-06 双翻页源对齐）：hold-pan/hold-zoomedPan 期间置位，
     *  DOWN/UP/CANCEL 复位。根检测器（PagerReaderView.dispatchTouchEvent 层）凭此让路——
     *  两判定窗（根 dx≥dy 严格 vs 本视图沿轴判定）对斜滑结论不一致，适应宽度页竖拖平移中
     *  轨迹稍斜会被根检测器抢翻（工单"高度越界不能下滑一点就翻页"实锤）。 */
    var panActive = false

    /** ★ 滑动兜底（2026-10-05，"zip 单页滑动不翻页"普通模式防线）：未放大态沿主轴的滑动
     *  靠"放行→ViewPager2 内部 RecyclerView 拦截接管"翻页——个别 ROM/系统版本拦截行为
     *  异常时滑动就死（点击仍能翻）。两个时机显式兜底：onFling（沿主轴快速滑动）与
     *  UP（位移超 30% 视口的慢拖），交 pager 翻一页。正常设备 VP2 拦截后本视图收到
     *  CANCEL，这两处根本不触发（零双重翻页）；短码 fling-fb / up-fb 证明走到了兜底。 */
    var pagerSwipeFallback: ((dir: Int) -> Unit)? = null
    private var fallbackFired = false
    // ★ pass-flip 自接管标记：消费的手势流（DOWN 未放大态本视图就吞了，super 全程不知情）
    //   在 UP/CANCEL 时直接收尾归还，不走 super（基类收到没有 DOWN 前缀的孤立流行为未定义）
    private var passFlipOwns = false
    // ★ 2026-10-06 123307/131541 日志实锤（hold-pan 标记了 4 次图纹丝不动）：fork 的平移状态
    //   （maxTouchCount/vTranslateStart/vCenterStart）全部在 ACTION_DOWN 里初始化，未放大态
    //   DOWN 被本视图吞掉后 super 收到的孤立 MOVE 直接被无视——"适应宽度上下拖不动"根因。
    //   pan 分支代理 super 前补一条合成 DOWN（位置=真实落点），fork 平移数学完全成立；
    //   合成 DOWN 只在用户已实际拖动（pan 判定需超 touchSlop）时发出，内部 GestureDetector
    //   的 tap/双击计时随首个 MOVE 超界自动作废，不影响双击配置。每手势最多补一条。
    private var superArmed = false

    private fun armSuper(event: MotionEvent) {
        if (superArmed) return
        superArmed = true
        val down = android.view.MotionEvent.obtain(
            event.downTime, event.downTime, MotionEvent.ACTION_DOWN, downX, downY, 0
        )
        try { super.onTouchEvent(down) } finally { down.recycle() }
    }

    private fun gestureBaseScale(): Float = if (viewportBaseScale > 0f) viewportBaseScale else minScale

    // ★ 2026-10-05 二次审查改自算：minScale 下放到 0.5×基准后，捏小的图小于视口，
    //   SSIV 默认 PAN_LIMIT_INSIDE 仍允许自由拖动 → getPanRemaining 返回正值 →
    //   沿轴滑动被误判 hold-pan，捏小态翻页失效（捏小恰是铺满/贴宽用户看全貌后的下一步）。
    //   改比"图当前显示尺寸是否真溢出视口"：溢出才有拖图意义；捏小/贴边一律放行翻页。
    private fun hasVerticalPanRoom(): Boolean = try {
        sHeight * scale > height + 1f
    } catch (_: Exception) { false }

    private fun hasHorizontalPanRoom(): Boolean = try {
        sWidth * scale > width + 1f
    } catch (_: Exception) { false }

    private val touchSlopPx by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop }

    /** ★ 拖图接力判定（mihon SSIV fork 同款体验）：滑动方向图已贴边（该向 pan 余量耗尽）
     *  时返回 true——此时继续拖动应交还翻页容器接力，而不是把图顶在边上不动。
     *  getPanRemaining 是"当前位置的真实余量"，与自算的 hasXXPanRoom（图是否溢出）互补。 */
    private fun panExhaustedToward(dx: Float, dy: Float): Boolean = try {
        val r = RectF()
        getPanRemaining(r)
        if (axisPassthroughHorizontal) {
            (dx > touchSlopPx && r.right <= 1f) || (dx < -touchSlopPx && r.left <= 1f)
        } else {
            (dy > touchSlopPx && r.bottom <= 1f) || (dy < -touchSlopPx && r.top <= 1f)
        }
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
                passFlipOwns = false
                panActive = false
                superArmed = false
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
                    // ★ 拖图接力（mihon 同款）：放大态沿滑动方向图已贴边→交还翻页容器继续
                    //   接力翻页，不再把图顶在边上；未贴边仍是拖图（惯性平移不受影响）
                    // ★ 2026-10-06 交还方式对齐 mihon fork：mihon 是 requestDisallow(false)+
                    //   继续消费本帧，parent 下一帧走正常 onInterceptTouchEvent 接管；旧写法
                    //   return false 把本帧甩回 parent 时 intercept 阶段已过，parent 只能进
                    //   onTouchEvent（RV 未拦截态，状态错乱）→ 交还后断流翻不了页（123307 日志
                    //   zoom-pan-edge 之后 0 次 pagerScroll=dragging 实锤）。改为消费本帧后，
                    //   后续帧重复本判定保持 disallow=false，parent 在 touchSlop 处正常拦截。
                    //   ★ panActive 门（2026-10-06 复盘补）：mihon fork 的 edgeXSwipe 带 !isPanning——
                    //   已进入拖图的手势贴边不再交还（用户在拖图看右半页，松手后新手势才翻页）；
                    //   无此门"拖图到边+累计位移超8%"会误翻页（新引入回归，复盘揪出）。
                    if (!panActive && panExhaustedToward(event.x - downX, event.y - downY)) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        if (!fallbackFired) {
                            val d = if (axisPassthroughHorizontal) event.x - downX else event.y - downY
                            val extent = if (axisPassthroughHorizontal) width else height
                            if (extent > 0 && kotlin.math.abs(d) > extent * 0.08f) {
                                fallbackFired = true
                                markSwipe(context, "pass-flip-self")
                                pagerSwipeFallback?.invoke(if (d < 0) 1 else -1)
                                return true
                            }
                        }
                        markSwipe(context, "zoom-pan-edge")
                        return true
                    }
                    // 已在拖图（panActive）贴边 → fork 继续平移，fitToBounds 原地钳住（mihon 同款）；
                    // 松手后下一手势 panActive 复位，首帧即命中边缘交还 → 翻页
                    markSwipe(context, "hold-zoomedPan")
                    panActive = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return super.onTouchEvent(event)
                }
                // ★ 未放大方向分流：沿放行主轴的滑动交还翻页（ViewPager2），
                //   垂直轴且图上还有可拖空间 → 归 SSIV 拖动看图（铺满屏幕"划着看"）；
                //   无可拖空间（完整显示模式纵向贴边）→ 一并放行翻页，与旧行为一致
                // ★ 2026-10-05 横屏滑动翻页+贴高拖图双修：主轴拖动先看"该轴有无可拖余量"——
                //   贴高/缩放超出(有余量)→归拖图；铺满贴边(无余量)→放行翻页。
                // ★ 2026-10-06 判定窗收紧 ×1.25→×1.0（对齐 mihon fork dx>dy 判据）：
                //   旧窗把 dx∈(0.8dy, dy) 的斜竖滑判成沿轴——适应宽度页横向贴满无余量、
                //   dOrtho>dAlong 又差一线 → 落 8% 自翻=斜竖滑"一点就翻页"（工单实锤，
                //   mihon 同轨迹 dx<dy 全归平移）；dx≥dy 的横主导斜滑行为不变。
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                val alongAxis = if (axisPassthroughHorizontal) dy < dx else dx < dy
                if (alongAxis) {
                    val alongRoom = if (axisPassthroughHorizontal) hasHorizontalPanRoom() else hasVerticalPanRoom()
                    if (alongRoom) {
                        // ★ 拖图接力（mihon 同款）：基准态图溢出但当前位置沿滑动方向已贴边
                        //   （原始尺寸/贴满高度档拖到边）→交还翻页容器接力，治"个别不能翻"
                        // ★ 2026-10-06 交还方式对齐 mihon（同 zoom-pan-edge）：disallow(false)+
                        //   消费本帧，parent 下帧正常 intercept；旧 return false 断流。拦截失效
                        //   设备沿用 pass-flip-self 自翻兜底（位移超 8% 视口直接翻一页）。
                        //   ★ panActive 门（同 zoom-pan-edge）：拖图中贴边不交还，mihon isPanning 语义
                        if (!panActive && panExhaustedToward(event.x - downX, event.y - downY)) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                            consumedDown = true
                            passFlipOwns = true
                            if (!fallbackFired) {
                                val d = if (axisPassthroughHorizontal) event.x - downX else event.y - downY
                                val extent = if (axisPassthroughHorizontal) width else height
                                if (extent > 0 && kotlin.math.abs(d) > extent * 0.08f) {
                                    fallbackFired = true
                                    markSwipe(context, "pass-flip-self")
                                    pagerSwipeFallback?.invoke(if (d < 0) 1 else -1)
                                    return true
                                }
                            }
                            markSwipe(context, "hold-pan-edge")
                            return true
                        }
                        markSwipe(context, "hold-pan")
                        panActive = true
                        armSuper(event)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        consumedDown = true
                        return super.onTouchEvent(event)
                    }
                    // ★ mihon 适应宽度同款（2026-10-05 工单）：沿轴（适应宽度横向）贴边但正交轴
                    //   （纵向）有内容可看且本次手势纵向占优→按平移处理，不翻页。此前竖拖/斜竖拖
                    //   全落进翻页判定，一拖就翻，无法像 mihon 那样上下拖画面
                    val orthoRoom = if (axisPassthroughHorizontal) hasVerticalPanRoom() else hasHorizontalPanRoom()
                    val dAlong = if (axisPassthroughHorizontal) kotlin.math.abs(event.x - downX) else kotlin.math.abs(event.y - downY)
                    val dOrtho = if (axisPassthroughHorizontal) kotlin.math.abs(event.y - downY) else kotlin.math.abs(event.x - downX)
                    if (orthoRoom && dOrtho > dAlong) {
                        markSwipe(context, "hold-pan")
                        panActive = true
                        armSuper(event)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        consumedDown = true
                        return super.onTouchEvent(event)
                    }
                    // ★ 2026-10-05 深度定位（1005-205951 日志实锤）：126 次放行翻页 0 次
                    //   pagerScroll=DRAGGED、0 次 fling-fb/up-fb——放行后事件流在转发层丢失，
                    //   本视图每手势只收到首条 MOVE（旧 return false 假设"父会持续看到流并
                    //   在 touchSlop 处接管"在部分设备/转发链上不成立），UP 兜底也因 UP 从未
                    //   回到本视图而死。改为消费事件流并自累计沿轴位移：
                    //   - 正常设备 touchSlop(≈24px) 处 RV 先拦截接管（远小于 25% 视口阈值），
                    //     本视图收 CANCEL 不翻页——零双重翻页，与旧链路完全兼容；
                    //   - 拦截失效设备由本视图自己累计到 25% 视口直接翻一页（fallback 防重），
                    //     不再依赖 RV/UP/fling 任何一环。
                    if (!fallbackFired) {
                        val d = if (axisPassthroughHorizontal) event.x - downX else event.y - downY
                        val extent = if (axisPassthroughHorizontal) width else height
                        // 阈值 8% 视口（≥touchSlop 数倍防误点）：轻扫/慢拖必达——25% 时快速轻扫
                        // 够不着造成"有的能翻有的不能"；翻页意图在沿轴无拖图余量时明确
                        if (extent > 0 && kotlin.math.abs(d) > extent * 0.08f) {
                            fallbackFired = true
                            markSwipe(context, "pass-flip-self")
                            pagerSwipeFallback?.invoke(if (d < 0) 1 else -1)
                            consumedDown = true
                            passFlipOwns = true
                            return true
                        }
                    }
                    if (!swipeDiagMarked) markSwipe(context, "pass-flip")
                    consumedDown = true
                    passFlipOwns = true
                    return true
                }
                val hasRoom = if (axisPassthroughHorizontal) hasVerticalPanRoom() else hasHorizontalPanRoom()
                if (!hasRoom) {
                    // ★ 斜滑死路收口：图上无任何可拖空间时，任何方向滑动唯一有意义的就是翻页。
                    //   沿轴分量超 8% 视口按其方向自翻（斜度略大的滑动曾被归到这里放行后
                    //   断流死，是"个别不能翻"的另一来源）；纯正交滑动吞流放空（无意义动作）
                    if (!fallbackFired) {
                        val d = if (axisPassthroughHorizontal) event.x - downX else event.y - downY
                        val extent = if (axisPassthroughHorizontal) width else height
                        if (extent > 0 && kotlin.math.abs(d) > extent * 0.08f) {
                            fallbackFired = true
                            markSwipe(context, "pass-flip-self")
                            pagerSwipeFallback?.invoke(if (d < 0) 1 else -1)
                            consumedDown = true
                            passFlipOwns = true
                            return true
                        }
                    }
                    if (!swipeDiagMarked) markSwipe(context, "v-noPan-pass")
                    consumedDown = true
                    passFlipOwns = true
                    return true
                }
                markSwipe(context, "hold-pan")
                panActive = true
                armSuper(event)
                parent?.requestDisallowInterceptTouchEvent(true)
                consumedDown = true
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                panActive = false
                superArmed = false
                // ★ pass-flip 自接管的手势流在此收尾：翻页判定已在 MOVE 内完成（自翻或
                //   正常设备 RV 拦截时收 CANCEL），super 从未见过这段流的 DOWN，不透传
                if (passFlipOwns) {
                    passFlipOwns = false
                    consumedDown = false
                    return true
                }
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
