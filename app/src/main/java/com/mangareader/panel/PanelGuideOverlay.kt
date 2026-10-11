package com.mangareader.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 逐格引导覆盖层（评估报告 §7.2 交互契约）。
 *
 * 自我闭环：页位图经 engine.loadPage 自载（与 pager 视图绑定状态解耦，规避"聚焦槽还没换到位"
 * 的时序竞态；ArchiveEngine 位图缓存与 pager 共享，不重复解码，位图归引擎缓存所有——禁止 recycle）。
 * 绘制：整页 fit-center 铺底 → 当前格以外四矩形压暗(55%) → 当前格裁切亮显居中放大 + 描边。
 * 墨水屏（session.eink）：不压暗、不裁切放大、零动画，仅 3px 描边（评估报告 §7.3 墨水屏纪律）。
 * 手势：点按分区走 nav（RTL 语义与 CurlReaderView 同款：PREV 区=前进）；水平滑 ≥64dp 切格
 * （左滑=下一格，与 pager 手势惯例一致，不做 RTL 镜像）；末页末格再前进 = onExit 交回章末流程。
 * 识别中沿用上一帧画面 + 右下角"识别中"小字（KOReader 防闪纪律：原位换图不重建）。
 */
class PanelGuideOverlay(context: Context) : View(context) {

    data class Session(
        val treePath: String,
        val engine: com.mangareader.viewer.ArchiveEngine,
        val pages: List<com.mangareader.viewer.ArchiveEngine.PageMeta>,
        val rtl: Boolean,
        val eink: Boolean,
        val nav: com.mangareader.native.ViewerNavigation,
        /** 描边/高亮用强调色（NES 侧经 readerAccentColor 推导后传 int） */
        val accentColor: Int,
        /** 非当前格压暗 alpha 0~255（设置面板"压暗程度"，0=不压暗纯放大） */
        val dimAlpha: Int = DIM_ALPHA,
        /** 翻格动画（设置面板可关；墨水屏恒关） */
        val animEnabled: Boolean = true,
        /** 页置信度降级门槛（设置面板灵敏度档位 → PanelTunables.confFailFor） */
        val minConf: Float = PanelTunables.CONF_FAIL,
        /** 纵向阅读序倒转：true=行序 下→上（设置面板"先下"）；false=常规 上→下 */
        val vertBottomUp: Boolean = false,
        val goToPage: (Int) -> Unit,
        val currentPage: () -> Int,
        val onExit: () -> Unit,
    )

    private companion object {
        /** pendingLanding 哨兵：跨页回退落"上一页阅读序最后一格" */
        const val LANDING_LAST = Int.MIN_VALUE
        const val DIM_ALPHA = 140            // 55% 压暗
        const val ANIM_MS = 180L
        const val SWIPE_DP = 64f
        const val CROP_MARGIN_DP = 8f
    }

    private var session: Session? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadJob: Job? = null

    private var shownPage = -1
    private var pageBitmap: Bitmap? = null
    private var result: PanelPageResult? = null
    private var panelIdx = 0
    private var pendingLanding: Int? = null
    /** 连续降级页计数（C8）：达 [PanelTunables.CONSECUTIVE_FAIL_EXIT] 自动退出逐格模式 */
    private var consecutiveFails = 0

    // 绘制缓存（onDraw 内不得分配）
    private val scrimPaint = Paint().apply { color = Color.BLACK; alpha = DIM_ALPHA }
    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val einkBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = 12f * resources.displayMetrics.scaledDensity
    }
    private val srcRect = Rect()
    private val fullRect = RectF()
    private val panelRect = RectF()
    private var cropRect: RectF? = null
    private var animFrom: RectF? = null
    private var animTo: RectF? = null
    private var animStartAt = 0L

    private var downX = 0f
    private var downY = 0f

    // ———— 生命周期 ————

    fun begin(s: Session) {
        session = s
        shownPage = -1
        result = null
        pageBitmap = null
        panelIdx = 0
        pendingLanding = null
        consecutiveFails = 0
        cropRect = null
        scrimPaint.alpha = s.dimAlpha
        visibility = VISIBLE
        showPage(s.currentPage())
    }

    fun end() {
        session = null
        loadJob?.cancel()
        loadJob = null
        animTo = null
        animFrom = null
        result = null
        pageBitmap = null
        visibility = GONE
        invalidate()
    }

    /** host 的 delegate.onPageChanged 桥入口 */
    fun onHostPageChanged(page: Int) {
        if (session == null || page == shownPage) return
        showPage(page)
    }

    // ———— 运行时热更新（设置面板即改即生效，不重开 begin）————

    /** 显示类：压暗程度/翻格动画——纯绘制参数，滑条拖动即时生效，不触发重识别 */
    fun onRenderSettingsChanged(dimAlpha: Int, animEnabled: Boolean) {
        val s = session ?: return
        if (s.dimAlpha == dimAlpha && s.animEnabled == animEnabled) return
        session = s.copy(dimAlpha = dimAlpha, animEnabled = animEnabled)
        scrimPaint.alpha = dimAlpha
        invalidate()
    }

    /** 识别类：置信门槛/阅读方向（横+纵）——清本书结果缓存并重检当前页（方向变了阅读序也要重排） */
    fun onDetectSettingsChanged(minConf: Float, rtl: Boolean, bottomUp: Boolean) {
        val s = session ?: return
        if (s.minConf == minConf && s.rtl == rtl && s.vertBottomUp == bottomUp) return
        session = s.copy(minConf = minConf, rtl = rtl, vertBottomUp = bottomUp)
        PanelDetector.Cache.clearBook(s.treePath)
        showPage(shownPage)
    }

    override fun onDetachedFromWindow() {
        session = null
        loadJob?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    // ———— 页装载 ————

    private fun showPage(page: Int) {
        val s = session ?: return
        if (page !in s.pages.indices) return
        shownPage = page
        loadJob?.cancel()
        val sCaptured = s
        loadJob = scope.launch {
            // 位图必载（显示用）；检测结果命中缓存则只跳检测。
            // ★ 此前缓存命中提前 return 导致位图不装载——旧位图配新格框会切错画面
            val bmp = withContext(Dispatchers.Default) {
                runCatching { sCaptured.engine.loadPage(sCaptured.pages[page], sCaptured.treePath) }.getOrNull()
            }
            val res = PanelDetector.Cache.get(sCaptured.treePath, page)
                ?: (if (bmp == null) PanelDetectorCore.fallbackResult()
                    else runCatching { PanelDetector.detect(bmp, sCaptured.rtl, sCaptured.minConf, sCaptured.vertBottomUp) }
                        .getOrDefault(PanelDetectorCore.fallbackResult()))
            post {
                if (session !== sCaptured || shownPage != page) return@post
                pageBitmap = bmp
                applyResult(page, res)
            }
        }
    }

    private fun applyResult(page: Int, r: PanelPageResult) {
        val s = session ?: return
        PanelDetector.Cache.put(s.treePath, page, r)
        result = r
        val landing = pendingLanding
        pendingLanding = null
        panelIdx = when (landing) {
            LANDING_LAST -> r.boxes.size - 1
            null -> panelIdx.coerceIn(0, r.boxes.size - 1)
            else -> landing.coerceIn(0, r.boxes.size - 1)
        }
        cropRect = null
        invalidate()
        if (!r.fallback) {
            consecutiveFails = 0
            prefetchNextPage()
        } else {
            consecutiveFails++
            if (consecutiveFails >= PanelTunables.CONSECUTIVE_FAIL_EXIT) {
                android.widget.Toast.makeText(
                    context, "连续多页识别不到分格，已恢复普通阅读", android.widget.Toast.LENGTH_SHORT,
                ).show()
                session?.onExit?.invoke()
            }
        }
    }

    // ———— 格导航 ————

    private fun nextPanel() {
        val r = result ?: return
        if (panelIdx < r.boxes.size - 1) {
            panelIdx++
            animateCropTo(currentCropTarget())
        } else {
            requestPage((session?.currentPage?.invoke() ?: 0) + 1, 0)
        }
    }

    private fun prevPanel() {
        val r = result ?: return
        if (panelIdx > 0) {
            panelIdx--
            animateCropTo(currentCropTarget())
        } else {
            requestPage((session?.currentPage?.invoke() ?: 0) - 1, LANDING_LAST)
        }
    }

    private fun requestPage(target: Int, landing: Int) {
        val s = session ?: return
        if (target !in s.pages.indices) {
            // 末页末格再前进 → 退出逐格，交回普通阅读的章末流程；首页首格回退不动作
            if (target >= s.pages.size) s.onExit()
            return
        }
        pendingLanding = landing
        s.goToPage(target)
    }

    /** C10 预取：显示本页后台预检测下页（缓存命中则零成本） */
    private fun prefetchNextPage() {
        val s = session ?: return
        val next = (session?.currentPage?.invoke() ?: shownPage) + 1
        if (next !in s.pages.indices || PanelDetector.Cache.get(s.treePath, next) != null) return
        scope.launch {
            val bmp = runCatching { s.engine.loadPage(s.pages[next], s.treePath) }.getOrNull() ?: return@launch
            val r = runCatching { PanelDetector.detect(bmp, s.rtl, s.minConf, s.vertBottomUp) }.getOrNull() ?: return@launch
            post { if (session === s) PanelDetector.Cache.put(s.treePath, next, r) }
        }
    }

    // ———— 触摸 ————

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val thr = SWIPE_DP * resources.displayMetrics.density
                when {
                    abs(dx) > thr && abs(dx) > abs(dy) -> if (dx < 0) nextPanel() else prevPanel()
                    abs(dx) < thr / 2 && abs(dy) < thr / 2 -> handleTap(event.x, event.y)
                }
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val s = session ?: return
        if (width <= 0 || height <= 0) return
        when (s.nav.getAction(x / width, y / height)) {
            com.mangareader.native.NavigationAction.PREV -> if (s.rtl) nextPanel() else prevPanel()
            com.mangareader.native.NavigationAction.NEXT -> if (s.rtl) prevPanel() else nextPanel()
            com.mangareader.native.NavigationAction.MENU -> s.onExit()
        }
    }

    // ———— 绘制 ————

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = session ?: return
        val r = result
        val bmp = pageBitmap
        val vw = width.toFloat()
        val vh = height.toFloat()

        // 步骤动画推进（非墨水屏）
        val animTarget = animTo
        if (animTarget != null) {
            val t = ((System.currentTimeMillis() - animStartAt).toFloat() / ANIM_MS).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)   // ease-out quad
            val f = animFrom ?: animTarget
            cropRect = RectF(
                lerp(f.left, animTarget.left, e), lerp(f.top, animTarget.top, e),
                lerp(f.right, animTarget.right, e), lerp(f.bottom, animTarget.bottom, e),
            )
            if (t >= 1f) {
                animTo = null
                animFrom = null
            } else {
                postInvalidateOnAnimation()
            }
        }

        if (bmp == null || bmp.isRecycled) {
            canvas.drawColor(Color.BLACK)
            // result 已到但位图没载到 = 解码失败；result 未到 = 正常识别中
            val msg = if (result != null) "页面加载失败，点击中键退出" else "分格识别中…"
            canvas.drawText(msg, vw / 2 - hintPaint.textSize * msg.length / 2.2f, vh / 2, hintPaint)
            return
        }

        // 整页 fit-center
        val m = minOf(vw / bmp.width, vh / bmp.height)
        val fw = bmp.width * m
        val fh = bmp.height * m
        fullRect.set((vw - fw) / 2, (vh - fh) / 2, (vw + fw) / 2, (vh + fh) / 2)
        srcRect.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, srcRect, fullRect, cropPaint)

        if (r == null || r.fallback || r.boxes.size <= 1 || s.eink) {
            // 降级页/单格页/墨水屏：整页干净显示
            if (r != null && !r.fallback && r.boxes.isNotEmpty() && s.eink) {
                // 墨水屏：仅描边当前格，不压暗不放大
                val box = r.boxes[panelIdx.coerceIn(0, r.boxes.size - 1)]
                panelRect.set(
                    fullRect.left + box.x * fullRect.width(),
                    fullRect.top + box.y * fullRect.height(),
                    fullRect.left + box.right * fullRect.width(),
                    fullRect.top + box.bottom * fullRect.height(),
                )
                borderPaint.color = s.accentColor
                canvas.drawRect(panelRect, einkBorderPaint.apply { color = s.accentColor })
            }
            drawHint(canvas, r)
            return
        }

        // 当前格压暗（当前格以外四矩形）
        val box = r.boxes[panelIdx.coerceIn(0, r.boxes.size - 1)]
        panelRect.set(
            fullRect.left + box.x * fullRect.width(),
            fullRect.top + box.y * fullRect.height(),
            fullRect.left + box.right * fullRect.width(),
            fullRect.top + box.bottom * fullRect.height(),
        )
        canvas.drawRect(0f, 0f, vw, panelRect.top, scrimPaint)
        canvas.drawRect(0f, panelRect.bottom, vw, vh, scrimPaint)
        canvas.drawRect(0f, panelRect.top, panelRect.left, panelRect.bottom, scrimPaint)
        canvas.drawRect(panelRect.right, panelRect.top, vw, panelRect.bottom, scrimPaint)

        // 当前格裁切放大居中
        val target = currentCropTarget()
        if (cropRect == null) cropRect = target
        cropRect?.let { cr ->
            srcRect.set(
                (box.x * bmp.width).toInt().coerceIn(0, bmp.width - 1),
                (box.y * bmp.height).toInt().coerceIn(0, bmp.height - 1),
                (box.right * bmp.width).toInt().coerceIn(1, bmp.width),
                (box.bottom * bmp.height).toInt().coerceIn(1, bmp.height),
            )
            canvas.drawBitmap(bmp, srcRect, cr, cropPaint)
            canvas.drawRect(cr, borderPaint.apply { color = s.accentColor })
        }
        drawHint(canvas, r)
    }

    /** 当前格的裁切目标矩形（视图坐标，适配居中 + 8dp 边距） */
    private fun currentCropTarget(): RectF {
        val r = result
        val bmp = pageBitmap
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (r == null || bmp == null || vw <= 0 || vh <= 0) return RectF()
        val box = r.boxes[panelIdx.coerceIn(0, r.boxes.size - 1)]
        val sw = box.w * bmp.width
        val sh = box.h * bmp.height
        val margin = CROP_MARGIN_DP * resources.displayMetrics.density
        val m = minOf((vw - margin * 2) / sw, (vh - margin * 2) / sh)
        val cw = sw * m
        val ch = sh * m
        return RectF((vw - cw) / 2, (vh - ch) / 2, (vw + cw) / 2, (vh + ch) / 2)
    }

    private fun animateCropTo(target: RectF) {
        val s = session ?: return
        if (s.eink || !s.animEnabled) {
            cropRect = target
            invalidate()
            return
        }
        animFrom = cropRect?.let { RectF(it) }
        animTo = RectF(target)
        animStartAt = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    private fun drawHint(canvas: Canvas, r: PanelPageResult?) {
        when {
            r == null ->
                // 识别中（本页结果未到但旧位图还在显示）：右下角小字
                canvas.drawText("识别中…", width - hintPaint.textSize * 3.6f, height - hintPaint.textSize * 1.6f, hintPaint)
            // ★ 降级页角标（2026-10-11 用户"经常不行"诊断：让识别失败可见——
            //   这类页面按整页显示，与"功能没生效"观感相同，角标把两者区分开）
            r.fallback ->
                canvas.drawText(
                    "本页未识别到分格",
                    width - hintPaint.textSize * 8.4f, height - hintPaint.textSize * 1.6f, hintPaint,
                )
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
