package com.mangareader.native.curl

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ★ 翻页特效一期：仿真卷页视图（移植自悠长假期 Q2/C0562s，GL 骨架 + 参数照搬，形变在 [CurlRenderer]）。
 *
 * 参数锚点全部来自逆向实锤：
 * - progress ∈ [25.0, -3.125]，行程系数 26.25（每页宽拖拽量）
 * - 松手回弹 250ms AccelerateDecelerate；完成翻转 300ms Decelerate(1.6)
 * - 翻页判定：位移 ≥50% 或速度越阈（1200px/s）
 *
 * 排他（路由层已拦，此处再守一层）：动图卷 / 条漫 / VERT_PAGER / 双页布局不会路由到本视图。
 * 本视图内双指捏合与长按裁剪不启用（缩放请关闭特效）。
 *
 * 位图供给：engine.loadPage 走现有 byteCount LruCache（位图归引擎管，本视图绝不 recycle）。
 */
class CurlPagerView(context: Context) : GLSurfaceView(context), com.mangareader.native.ReaderViewDelegate {

    private val renderer = CurlRenderer()
    private var engine: ArchiveEngine? = null
    private var treePath: String = ""
    private var pages: List<ArchiveEngine.PageMeta> = emptyList()
    private var currentPage = 0
    private var rtl = false

    var pageBgColor: Int = Color.parseColor("#111111")
        set(v) {
            field = v
            renderer.bgColor = floatArrayOf(Color.red(v) / 255f, Color.green(v) / 255f, Color.blue(v) / 255f, 1f)
        }
    var imageMarginH: Int = 0   // GL 路径不消费边距（保持视图族属性约定兼容）

    private var loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = java.util.Collections.synchronizedSet(HashSet<Int>())

    private var settleAnimator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null
    /** 拖拽方向：true=向前翻（下一页） */
    private var draggingForward = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null

    /** 视图是否完成首帧内容（供 C-2 就绪轮询） */
    override fun isCurrentPageReady(): Boolean =
        renderer.hasFrontTexture() && settleAnimator == null &&
                renderer.progress >= CurlRenderer.PROGRESS_FLAT - 0.01f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = true; isFocusableInTouchMode = true
    }

    // ══════════════ ReaderViewDelegate ══════════════

    override fun setup(
        engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>,
        startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean, verticalPager: Boolean
    ) {
        // 同卷重进（模式切换）：delegate 复用但状态全量重置（对齐 PagerReaderView 完全重建语义）
        settleAnimator?.cancel(); settleAnimator = null
        dragging = false
        this.engine = engine; this.treePath = treePath; this.pages = pages; this.rtl = rtl
        this.currentPage = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        renderer.progress = CurlRenderer.PROGRESS_FLAT
        renderer.rtl = rtl
        renderer.curlingSlot = null
        loadScope.cancel()
        loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        inflight.clear()
        ensureLoaded(currentPage - 1, prevSlot())
        ensureLoaded(currentPage, CurlRenderer.Slot.FRONT)
        ensureLoaded(currentPage + 1, nextSlot())
        requestRender()
    }

    override fun goToPage(page: Int) {
        if (page < 0 || page >= pages.size) return
        settleAnimator?.cancel(); settleAnimator = null
        renderer.progress = CurlRenderer.PROGRESS_FLAT
        renderer.curlingSlot = null
        currentPage = page
        ensureLoaded(page - 1, prevSlot())
        ensureLoaded(page, CurlRenderer.Slot.FRONT)
        ensureLoaded(page + 1, nextSlot())
        requestRender()
    }

    override fun release() {
        settleAnimator?.cancel(); settleAnimator = null
        dragging = false
        renderer.progress = CurlRenderer.PROGRESS_FLAT
        renderer.curlingSlot = null
        loadScope.cancel()
        queueEvent { renderer.clearTexturesGl() }
    }

    override fun trimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            queueEvent { renderer.clearTexturesGl() }
            ensureLoaded(currentPage, CurlRenderer.Slot.FRONT)
        }
    }

    // ══════════════ 手势 ══════════════

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleAnimator?.cancel(); settleAnimator = null
                dragging = true
                downX = ev.x; downY = ev.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
                // 起点决定拖拽方向：LTR 右半屏向前 / RTL 左半屏向前（与 pager 习惯一致）
                val startX = ev.x / width
                draggingForward = if (rtl) startX < 0.5f else startX >= 0.5f
                renderer.curlingSlot = if (draggingForward) CurlRenderer.Slot.FRONT else CurlRenderer.Slot.LEFT
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (dragging) applyDrag(ev.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(ev)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle(); velocityTracker = null
                val dx = ev.x - downX
                val dy = abs(ev.y - downY)
                val slop = width * 0.08f
                if (dragging) {
                    dragging = false
                    if (abs(dx) < slop && dy < slop) {
                        // 纯点击：回落 + 分区处理
                        springBack()
                        handleTap(downX, downY)
                    } else {
                        settle(vx)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle(); velocityTracker = null
                if (dragging) { dragging = false; springBack() }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 拖拽 → progress 映射（行程系数 26.25 为逆向实锤值）。无邻页时锁 32% 预览上限。 */
    private fun applyDrag(x: Float) {
        val dx = x - downX
        val dragUnits = when {
            draggingForward && !rtl -> -dx   // LTR 向前：向左拖 → progress 降
            draggingForward && rtl -> dx     // RTL 向前：向右拖 → progress 降
            !draggingForward && !rtl -> dx   // LTR 向后：向右拖 → progress 降
            else -> -dx                      // RTL 向后：向左拖 → progress 降
        }
        var progress = CurlRenderer.PROGRESS_FLAT - (dragUnits / width) * CurlRenderer.PROGRESS_SPAN
        val hasNeighbor = if (draggingForward) currentPage < pages.size - 1 else currentPage > 0
        val floor = if (hasNeighbor) CurlRenderer.PROGRESS_FULL
        else CurlRenderer.PROGRESS_FLAT - CurlRenderer.PROGRESS_SPAN * 0.32f
        renderer.progress = progress.coerceIn(floor, CurlRenderer.PROGRESS_FLAT)
        requestRender()
    }

    /** 松手判定：位移 ≥50% 或速度越阈（1200px/s）→ 完成翻转；否则回弹 */
    private fun settle(vx: Float) {
        val displaced = CurlRenderer.PROGRESS_FLAT - renderer.progress
        val velocityFlip = if (draggingForward) (if (rtl) vx > 1200f else vx < -1200f)
        else (if (rtl) vx < -1200f else vx > 1200f)
        val hasNeighbor = if (draggingForward) currentPage < pages.size - 1 else currentPage > 0
        val complete = (displaced >= CurlRenderer.PROGRESS_SPAN * 0.5f) || velocityFlip
        if (complete && hasNeighbor) animateFlipComplete() else springBack()
    }

    private fun springBack() {
        animateProgressTo(CurlRenderer.PROGRESS_FLAT, 250L, AccelerateDecelerateInterpolator()) { }
    }

    private fun animateFlipComplete() {
        animateProgressTo(CurlRenderer.PROGRESS_FULL, 300L, DecelerateInterpolator(1.6f)) {
            commitFlip()
        }
    }

    private fun animateProgressTo(target: Float, dur: Long, interp: Interpolator, end: () -> Unit) {
        settleAnimator?.cancel()
        val a = ValueAnimator.ofFloat(renderer.progress, target)
        a.duration = dur
        a.interpolator = interp
        a.addUpdateListener { anim -> renderer.progress = anim.animatedValue as Float; requestRender() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                settleAnimator = null
                end()
            }
        })
        settleAnimator = a
        a.start()
    }

    /** 翻页完成：页码提交 + 纹理零拷贝轮转 + 邻页异步补载 + 进度归位（单帧原子） */
    private fun commitFlip() {
        if (draggingForward) {
            if (currentPage < pages.size - 1) { currentPage++; renderer.rotateForward() }
        } else {
            if (currentPage > 0) { currentPage--; renderer.rotateBackward() }
        }
        renderer.progress = CurlRenderer.PROGRESS_FLAT
        renderer.curlingSlot = null
        ensureLoaded(currentPage, CurlRenderer.Slot.FRONT)
        ensureLoaded(currentPage + 1, nextSlot())
        ensureLoaded(currentPage - 1, prevSlot())
        requestRender()
        onPageChanged?.invoke(currentPage)
    }

    /** 点击分区：左33% 上一页 / 中33% 菜单 / 右33% 下一页（y<5% 强制菜单）；RTL 镜像 */
    private fun handleTap(x: Float, y: Float) {
        if (height > 0 && y / height < 0.05f) { onToggleUi?.invoke(); return }
        val fx = if (width > 0) x / width else 0.5f
        val zonePrev = if (rtl) fx > 0.67f else fx < 0.33f
        val zoneNext = if (rtl) fx < 0.33f else fx > 0.67f
        when {
            zonePrev && currentPage > 0 -> { goToPage(currentPage - 1); onPageChanged?.invoke(currentPage) }
            zoneNext && currentPage < pages.size - 1 -> { goToPage(currentPage + 1); onPageChanged?.invoke(currentPage) }
            else -> onToggleUi?.invoke()
        }
    }

    // ══════════════ 位图供给 ══════════════

    private fun nextSlot(): CurlRenderer.Slot = if (rtl) CurlRenderer.Slot.LEFT else CurlRenderer.Slot.RIGHT
    private fun prevSlot(): CurlRenderer.Slot = if (rtl) CurlRenderer.Slot.RIGHT else CurlRenderer.Slot.LEFT

    /** IO 加载页位图 → GL 队列上传；命中引擎 LruCache 时近零成本 */
    private fun ensureLoaded(pageIdx: Int, slot: CurlRenderer.Slot) {
        val e = engine ?: return
        if (pageIdx !in pages.indices) return
        if (!inflight.add(pageIdx)) return
        loadScope.launch {
            try {
                val bmp = e.loadPage(pages[pageIdx], treePath)
                if (bmp != null) {
                    if (slot == CurlRenderer.Slot.FRONT) {
                        renderer.pageAspect = bmp.width.toFloat() / bmp.height.toFloat()
                    }
                    renderer.upload(slot, bmp)
                    post { requestRender() }
                }
            } catch (_: Exception) {
            } finally {
                inflight.remove(pageIdx)
            }
        }
    }
}
