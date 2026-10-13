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
 * （左滑=下一格，与 pager 手势惯例一致，不做 RTL 镜像）；末页末格再前进 = onChapterEnd 交回章末流程。
 * 识别中沿用上一帧画面 + 右下角"识别中"小字（KOReader 防闪纪律：原位换图不重建）。
 */
class PanelGuideOverlay(context: Context) : View(context) {

    /**
     * 超分联动（NES 注入，2026-10-11 用户定则"分格用超分后的图，超分没出就等它"）：
     * srSource(page) → Ready=用超分位图显示+检测；Pending=超分在算，先显原图、
     * 挂 [Session.srPoll] 等完成后重载本页；null=本页与超分无关（直接用原图）。
     * 拆分双页（doublePageSplit）：SR 产物与 engine.loadPage 同页索引空间（都是拆分后的半页）。
     */
    sealed interface SrPage {
        data class Ready(val bitmap: Bitmap) : SrPage
        data object Pending : SrPage
    }

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
        val dimMode: Int = 0,
        val splitMode: Int = 0,

        val transitionMode: Int = 0,
        val displayMode: Int = 0,
        val cropMode: Int = 2,
        val slideMs: Long = 180L,
        val precision: Int = 1,
        val previewEnabled: Boolean = false,
        val srSource: ((Int) -> SrPage?)? = null,
        val srPoll: ((Int, () -> Unit) -> Unit)? = null,
        val goToPage: (Int) -> Unit,
        val currentPage: () -> Int,

        val onChapterEnd: () -> Unit = {},
        val onCloseActive: () -> Unit = {},
        val progressEnabled: Boolean = false,
        val swipeInvert: Boolean = false,
        val tapInvert: Boolean = false,
        val foldJoinEnabled: Boolean = false,
        val autoRotateEnabled: Boolean = false,
        val autoRotateDir: Int = 2,
        val crossPageEnabled: Boolean = false,
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
    /** 该页超分仍在计算中（显示原图 + "等待画质增强…"，产物就绪后自动重载） */
    @Volatile private var srWaiting = false

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
    private var inPreview = false
    private var prevBox: PanelBox? = null
    private var prevCropRect: RectF? = null
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // ———— 生命周期 ————

    fun begin(s: Session) {
        session = s
        shownPage = -1
        result = null
        pageBitmap = null
        panelIdx = 0
        pendingLanding = null
        srWaiting = false
        cropRect = null
        inPreview = false
        prevBox = null
        prevCropRect = null
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
        inPreview = false
        prevBox = null
        prevCropRect = null
        visibility = GONE
        invalidate()
    }

    /** host 的 delegate.onPageChanged 桥入口 */
    fun onHostPageChanged(page: Int) {
        val s = session ?: return
        if (page == shownPage) return

        showPage(page)
    }

    // ———— 运行时热更新（设置面板即改即生效，不重开 begin）————

    /** 显示类：压暗程度/翻格动画/选中格显示模式——纯绘制参数，即改即生效，不触发重识别 */
    fun onRenderSettingsChanged(
        dimAlpha: Int,
        animEnabled: Boolean,
        dimMode: Int,
        transitionMode: Int = 0,
        displayMode: Int = 0,
        cropMode: Int = 2,
        slideMs: Long = 180L,
        previewEnabled: Boolean = false,
        progressEnabled: Boolean = false,
        swipeInvert: Boolean = false,
        tapInvert: Boolean = false,
        foldJoinEnabled: Boolean = false,
        autoRotateEnabled: Boolean = false,
        autoRotateDir: Int = 2,
        crossPageEnabled: Boolean = false,
    ) {
        val s = session ?: return
        if (s.dimAlpha == dimAlpha && s.animEnabled == animEnabled && s.dimMode == dimMode &&
            s.transitionMode == transitionMode && s.displayMode == displayMode &&
            s.cropMode == cropMode && s.slideMs == slideMs && s.previewEnabled == previewEnabled &&
            s.progressEnabled == progressEnabled && s.swipeInvert == swipeInvert &&
            s.tapInvert == tapInvert && s.foldJoinEnabled == foldJoinEnabled &&
            s.autoRotateEnabled == autoRotateEnabled && s.autoRotateDir == autoRotateDir &&
            s.crossPageEnabled == crossPageEnabled
        ) return
        val foldChanged = s.foldJoinEnabled != foldJoinEnabled
        session = s.copy(
            dimAlpha = dimAlpha, animEnabled = animEnabled, dimMode = dimMode,
            transitionMode = transitionMode, displayMode = displayMode,
            cropMode = cropMode, slideMs = slideMs, previewEnabled = previewEnabled,
            progressEnabled = progressEnabled, swipeInvert = swipeInvert,
            tapInvert = tapInvert, foldJoinEnabled = foldJoinEnabled,
            autoRotateEnabled = autoRotateEnabled, autoRotateDir = autoRotateDir,
            crossPageEnabled = crossPageEnabled,
        )
        scrimPaint.alpha = dimAlpha
        cropRect = null
        if (foldChanged) {
            PanelFoldJoin.Cache.clearBook(s.treePath)
            showPage(shownPage)
        } else {
            invalidate()
        }
    }

    /** 识别类：置信门槛/阅读方向——清本书结果缓存并重检当前页（方向变了阅读序也要重排） */
    fun onDetectSettingsChanged(minConf: Float, rtl: Boolean, splitMode: Int, precision: Int = 1) {
        val s = session ?: return
        if (s.minConf == minConf && s.rtl == rtl && s.splitMode == splitMode && s.precision == precision) return
        session = s.copy(minConf = minConf, rtl = rtl, splitMode = splitMode, precision = precision)
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

        visibility = VISIBLE
        loadJob?.cancel()
        val sCaptured = s
        loadJob = scope.launch {
            // ★ 超分联动（用户定则"分格用超分后的图"）：先问超分产物——
            //   Ready=超分位图既做显示也做检测（更锐利，二值化更稳）；
            //   Pending=先载原图占位显示"等待画质增强…"，挂轮询，产物就绪后重进本函数
            val sr = sCaptured.srSource?.invoke(page)
            if (sr is SrPage.Ready) {
                srWaiting = false
                val res = PanelDetector.Cache.get(sCaptured.treePath, page)
                    ?: runCatching { PanelDetector.detect(sr.bitmap, sCaptured.rtl, sCaptured.minConf, sCaptured.precision) }
                        .getOrDefault(PanelDetectorCore.fallbackResult())
                post {
                    if (session !== sCaptured || shownPage != page) return@post
                    pageBitmap = sr.bitmap
                    applyResult(page, res)
                }
                return@launch
            }
            val waiting = sr === SrPage.Pending
            // 位图必载（显示用）；检测结果命中缓存则只跳检测。
            // ★ 此前缓存命中提前 return 导致位图不装载——旧位图配新格框会切错画面
            val bmp = withContext(Dispatchers.Default) {
                runCatching { sCaptured.engine.loadPage(sCaptured.pages[page], sCaptured.treePath) }.getOrNull()
            }
            if (waiting) {
                sCaptured.srPoll?.invoke(page) {
                    post {
                        if (session !== sCaptured || shownPage != page) return@post
                        showPage(page)   // 超分产物就绪 → 重载：下次 srSource 返回 Ready
                    }
                }
            }
            val res = if (waiting) null else PanelDetector.Cache.get(sCaptured.treePath, page)
                ?: (if (bmp == null) PanelDetectorCore.fallbackResult()
                    else runCatching { PanelDetector.detect(bmp, sCaptured.rtl, sCaptured.minConf, sCaptured.precision) }
                        .getOrDefault(PanelDetectorCore.fallbackResult()))
            post {
                if (session !== sCaptured || shownPage != page) return@post
                srWaiting = waiting
                pageBitmap = bmp
                if (res != null) applyResult(page, res) else invalidate()
            }
        }
    }

    private fun applyResult(page: Int, r: PanelPageResult) {
        val s = session ?: return
        PanelDetector.Cache.put(s.treePath, page, r)
        srWaiting = false
        result = r
        val landing = pendingLanding
        pendingLanding = null
        panelIdx = when (landing) {
            LANDING_LAST -> r.boxes.size - 1
            null -> panelIdx.coerceIn(0, r.boxes.size - 1)
            else -> landing.coerceIn(0, r.boxes.size - 1)
        }
        cropRect = null
        prevBox = null
        prevCropRect = null
        inPreview = s.previewEnabled && !r.fallback && r.boxes.size > 1
        invalidate()
        if (!r.fallback) prefetchNextPage()
    }

    // ———— 格导航 ————

    private fun nextPanel() {
        val r = result ?: return
        if (panelIdx < r.boxes.size - 1) {
            val prev = r.boxes[panelIdx]
            panelIdx++
            animateCropTo(currentCropTarget(), prev)
        } else {
            val s = session
            if (s != null && s.splitMode == 1 && r.boxes.size > 1 &&
                boxesUnionArea(r.boxes) < PanelTunables.FULL_PAGE_AREA
            ) {
                visibility = GONE
                invalidate()
            } else {
                requestPage((s?.currentPage?.invoke() ?: 0) + 1, 0)
            }
        }
    }

    private fun prevPanel() {
        val r = result ?: return
        if (panelIdx > 0) {
            val prev = r.boxes[panelIdx]
            panelIdx--
            animateCropTo(currentCropTarget(), prev)
        } else {
            requestPage((session?.currentPage?.invoke() ?: 0) - 1, LANDING_LAST)
        }
    }

    private fun requestPage(target: Int, landing: Int) {
        val s = session ?: return
        if (target !in s.pages.indices) {
            // 末页末格再前进 → 退出逐格，交回普通阅读的章末流程；首页首格回退不动作
            if (target >= s.pages.size) s.onChapterEnd()
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
            val r = runCatching { PanelDetector.detect(bmp, s.rtl, s.minConf, s.precision) }.getOrNull() ?: return@launch
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
                if (inPreview) {
                    inPreview = false
                    cropRect = null
                    invalidate()
                    return true
                }
                when {
                    abs(dx) > thr && abs(dx) > abs(dy) -> {
                        val invert = session?.swipeInvert == true
                        if ((dx < 0) != invert) nextPanel() else prevPanel()
                    }
                    abs(dx) < thr / 2 && abs(dy) < thr / 2 -> handleTap(event.x, event.y)
                }
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val s = session ?: return
        if (width <= 0 || height <= 0) return
        val raw = s.nav.getAction(x / width, y / height)
        val act = if (s.tapInvert) when (raw) {
            com.mangareader.native.NavigationAction.PREV -> com.mangareader.native.NavigationAction.NEXT
            com.mangareader.native.NavigationAction.NEXT -> com.mangareader.native.NavigationAction.PREV
            else -> raw
        } else raw
        when (act) {
            com.mangareader.native.NavigationAction.PREV -> if (s.rtl) nextPanel() else prevPanel()
            com.mangareader.native.NavigationAction.NEXT -> if (s.rtl) prevPanel() else nextPanel()
            com.mangareader.native.NavigationAction.MENU -> s.onCloseActive()
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

        val animTarget = animTo
        if (animTarget != null) {
            val duration = if (s.slideMs > 0) s.slideMs else ANIM_MS
            val t = ((System.currentTimeMillis() - animStartAt).toFloat() / duration).coerceIn(0f, 1f)
            val f = animFrom ?: animTarget
            if (s.transitionMode == 2) {
                cropRect = RectF(animTarget)
            } else {
                cropRect = RectF(
                    lerp(f.left, animTarget.left, t), lerp(f.top, animTarget.top, t),
                    lerp(f.right, animTarget.right, t), lerp(f.bottom, animTarget.bottom, t),
                )
            }
            if (t >= 1f) {
                animTo = null
                animFrom = null
                prevBox = null
                prevCropRect = null
            } else {
                postInvalidateOnAnimation()
            }
        }

        if (bmp == null || bmp.isRecycled) {
            canvas.drawColor(Color.BLACK)
            val msg = if (result != null) "页面加载失败，点击中键退出" else "分格识别中…"
            canvas.drawText(msg, vw / 2 - hintPaint.textSize * msg.length / 2.2f, vh / 2, hintPaint)
            return
        }

        val m = minOf(vw / bmp.width, vh / bmp.height)
        val fw = bmp.width * m
        val fh = bmp.height * m
        fullRect.set((vw - fw) / 2, (vh - fh) / 2, (vw + fw) / 2, (vh + fh) / 2)
        srcRect.set(0, 0, bmp.width, bmp.height)

        val partialSkip = s.splitMode == 0 && r != null && !r.fallback && r.boxes.size > 1 &&
            boxesUnionArea(r.boxes) < PanelTunables.FULL_PAGE_AREA

        if (inPreview && r != null && !r.fallback && r.boxes.size > 1) {
            canvas.drawBitmap(bmp, srcRect, fullRect, cropPaint)
            borderPaint.color = s.accentColor
            for (box in r.boxes) {
                panelRect.set(
                    fullRect.left + box.x * fullRect.width(),
                    fullRect.top + box.y * fullRect.height(),
                    fullRect.left + box.right * fullRect.width(),
                    fullRect.top + box.bottom * fullRect.height(),
                )
                canvas.drawRect(panelRect, borderPaint)
            }
            drawHint(canvas, r)
            return
        }

        if (r == null || r.fallback || r.boxes.size <= 1 || partialSkip) {
            canvas.drawBitmap(bmp, srcRect, fullRect, cropPaint)
            drawHint(canvas, r, partialSkip)
            return
        }

        if (s.eink || s.cropMode == 3) {
            canvas.drawBitmap(bmp, srcRect, fullRect, cropPaint)
            val box = r.boxes[panelIdx.coerceIn(0, r.boxes.size - 1)]
            panelRect.set(
                fullRect.left + box.x * fullRect.width(),
                fullRect.top + box.y * fullRect.height(),
                fullRect.left + box.right * fullRect.width(),
                fullRect.top + box.bottom * fullRect.height(),
            )
            val bp = if (s.eink) einkBorderPaint else borderPaint
            bp.color = s.accentColor
            canvas.drawRect(panelRect, bp)
            drawHint(canvas, r)
            drawProgress(canvas)
            return
        }

        val box = r.boxes[panelIdx.coerceIn(0, r.boxes.size - 1)]
        panelRect.set(
            fullRect.left + box.x * fullRect.width(),
            fullRect.top + box.y * fullRect.height(),
            fullRect.left + box.right * fullRect.width(),
            fullRect.top + box.bottom * fullRect.height(),
        )
        if (s.displayMode == 0) {
            canvas.drawBitmap(bmp, srcRect, fullRect, cropPaint)
            if (s.dimMode == 0) {
                canvas.drawRect(0f, 0f, vw, vh, scrimPaint)
            } else {
                dimOutside(canvas, vw, vh)
            }
        }
        drawCrop(canvas, bmp, box)
        drawHint(canvas, r)
        drawProgress(canvas)
    }

    /** 当前格以外四矩形压暗（scrimPaint.alpha=dimAlpha） */
    private fun dimOutside(canvas: Canvas, vw: Float, vh: Float) {
        canvas.drawRect(0f, 0f, vw, panelRect.top, scrimPaint)
        canvas.drawRect(0f, panelRect.bottom, vw, vh, scrimPaint)
        canvas.drawRect(0f, panelRect.top, panelRect.left, panelRect.bottom, scrimPaint)
        canvas.drawRect(panelRect.right, panelRect.top, vw, panelRect.bottom, scrimPaint)
    }

    private fun drawCrop(canvas: Canvas, bmp: Bitmap, box: PanelBox) {
        val target = currentCropTarget()
        if (cropRect == null) cropRect = target
        val s = session
        val animTarget = animTo
        val pb = prevBox
        val pcr = prevCropRect
        if (s != null && s.transitionMode == 2 && animTarget != null && pb != null && pcr != null && cropRect != null) {
            val duration = if (s.slideMs > 0) s.slideMs else ANIM_MS
            val t = ((System.currentTimeMillis() - animStartAt).toFloat() / duration).coerceIn(0f, 1f)
            srcRect.set(
                (pb.x * bmp.width).toInt().coerceIn(0, bmp.width - 1),
                (pb.y * bmp.height).toInt().coerceIn(0, bmp.height - 1),
                (pb.right * bmp.width).toInt().coerceIn(1, bmp.width),
                (pb.bottom * bmp.height).toInt().coerceIn(1, bmp.height),
            )
            fadePaint.alpha = ((1f - t) * 255).toInt()
            canvas.drawBitmap(bmp, srcRect, pcr, fadePaint)
            srcRect.set(
                (box.x * bmp.width).toInt().coerceIn(0, bmp.width - 1),
                (box.y * bmp.height).toInt().coerceIn(0, bmp.height - 1),
                (box.right * bmp.width).toInt().coerceIn(1, bmp.width),
                (box.bottom * bmp.height).toInt().coerceIn(1, bmp.height),
            )
            fadePaint.alpha = (t * 255).toInt()
            canvas.drawBitmap(bmp, srcRect, cropRect!!, fadePaint)
            borderPaint.color = s.accentColor
            canvas.drawRect(cropRect!!, borderPaint)
            return
        }
        cropRect?.let { cr ->
            srcRect.set(
                (box.x * bmp.width).toInt().coerceIn(0, bmp.width - 1),
                (box.y * bmp.height).toInt().coerceIn(0, bmp.height - 1),
                (box.right * bmp.width).toInt().coerceIn(1, bmp.width),
                (box.bottom * bmp.height).toInt().coerceIn(1, bmp.height),
            )
            canvas.drawBitmap(bmp, srcRect, cr, cropPaint)
            canvas.drawRect(cr, borderPaint.apply { color = session?.accentColor ?: 0 })
        }
    }

    /** 当前格的裁切目标矩形（视图坐标，适配居中 + 8dp 边距） */
    private fun currentCropTarget(): RectF {
        val r = result
        val bmp = pageBitmap
        val s = session
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (r == null || bmp == null || vw <= 0 || vh <= 0 || s == null) return RectF()
        val box = r.boxes[panelIdx.coerceIn(0, r.boxes.size - 1)]
        val sw = box.w * bmp.width
        val sh = box.h * bmp.height
        val margin = when (s.cropMode) {
            0 -> 0f
            1 -> 0.05f * maxOf(sw, sh)
            else -> CROP_MARGIN_DP * resources.displayMetrics.density
        }
        val m = minOf((vw - margin * 2) / sw, (vh - margin * 2) / sh)
        val cw = sw * m
        val ch = sh * m
        return RectF((vw - cw) / 2, (vh - ch) / 2, (vw + cw) / 2, (vh + ch) / 2)
    }

    private fun animateCropTo(target: RectF, prev: PanelBox? = null) {
        val s = session ?: return
        if (s.eink || !s.animEnabled || s.transitionMode == 0) {
            cropRect = target
            prevBox = null
            prevCropRect = null
            invalidate()
            return
        }
        if (s.transitionMode == 2 && prev != null) {
            prevBox = prev
            prevCropRect = cropRect?.let { RectF(it) }
        }
        animFrom = cropRect?.let { RectF(it) }
        animTo = RectF(target)
        animStartAt = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    private fun drawHint(canvas: Canvas, r: PanelPageResult?, partialSkip: Boolean = false) {
        when {
            r == null && srWaiting ->
                canvas.drawText("等待画质增强…", width - hintPaint.textSize * 6.9f, height - hintPaint.textSize * 1.6f, hintPaint)
            r == null ->
                canvas.drawText("识别中…", width - hintPaint.textSize * 3.6f, height - hintPaint.textSize * 1.6f, hintPaint)
            r.fallback || partialSkip ->
                canvas.drawText(
                    "本页未识别到分格",
                    width - hintPaint.textSize * 8.4f, height - hintPaint.textSize * 1.6f, hintPaint,
                )
        }
    }

    private fun drawProgress(canvas: Canvas) {
        val s = session ?: return
        val r = result ?: return
        if (!s.progressEnabled || r.fallback || r.boxes.size <= 1) return
        val text = "${panelIdx + 1}/${r.boxes.size}"
        val oldAlpha = hintPaint.alpha
        hintPaint.alpha = 128
        canvas.drawText(text, width / 2f - hintPaint.measureText(text) / 2f, height - hintPaint.textSize * 1.6f, hintPaint)
        hintPaint.alpha = oldAlpha
    }

    private fun boxesUnionArea(boxes: List<PanelBox>): Float {
        if (boxes.isEmpty()) return 0f
        var union = 0f
        for (i in boxes.indices) {
            val a = boxes[i]
            var area = a.w * a.h
            for (j in boxes.indices) {
                if (j >= i) break
                val b = boxes[j]
                val ix = minOf(a.right, b.right) - maxOf(a.x, b.x)
                val iy = minOf(a.bottom, b.bottom) - maxOf(a.y, b.y)
                if (ix > 0 && iy > 0) area -= ix * iy
            }
            union += maxOf(0f, area)
        }
        return union.coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
