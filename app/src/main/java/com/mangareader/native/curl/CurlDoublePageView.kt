package com.mangareader.native.curl

import com.mangareader.native.ScalePlan

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.native.ReaderViewDelegate
import com.mangareader.native.RemotePageSupply
import com.mangareader.native.SpreadTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CurlDoublePageView(context: Context) : FrameLayout(context), ReaderViewDelegate {

    private val reloadTrigger = mutableIntStateOf(0)
    private val tapForwardTrigger = mutableIntStateOf(0)
    private val tapBackwardTrigger = mutableIntStateOf(0)
    private val composeView = ComposeView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    private val cache = CurlBitmapCache(12)
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loader = CurlPageLoader(context, cache, scope)

    private var engine: ArchiveEngine? = null
    private var treePath: String = ""
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()
    // 当前 spread 页索引用 Compose 状态承载：renderCompose 根作用域读它，goToPage（目录/书签
    // 跳转）改值才能触发重组把新 currentSpread 传进 CurlDoublePageContent，其
    // LaunchedEffect(currentSpread)→state.snapTo 才会执行。曾经是普通字段，参数闭包过期，
    // 跳转后画面停在旧页而进度照走（画面与进度脱节）
    private val currentPageState = mutableIntStateOf(0)
    private var currentPage: Int
        get() = currentPageState.intValue
        set(value) {
            currentPageState.intValue = value
            curlZoomState.reset()   // 翻跨页复位双击缩放
        }
    private var isRtl = false
    private var doublePageShift = false
    private var doublePageReverse = false
    // ★ 双页显式跨页表（2026-10-05 重做，对齐 PagerReaderView）：SpreadMapping 序号算术退役，
    //   智能配对（宽图/长图 solo）判定在 SpreadTable，尺寸由 Reader.kt 扫描经 setPageDims 回填
    private var spreadTable: SpreadTable = SpreadTable.build(0)
    private var pageDims: IntArray? = null
    private var smartMergeEnabled = true
    // ★ 双页合并起始页（2026-10-06 与 PagerReaderView 同口径）：仅 doublePageShift 档生效
    private var doublePageStart = 2
    // ★ 单本单页豁免（当前章，Reader.kt 按 chPath 读写 DB，本视图只持集合并入表）
    private var soloPages: Set<Int> = emptySet()
    // ★ 跨页表版本号（Compose state）：renderCompose 根作用域读它——表重排后根作用域重组
    //   才会把新表实例作为参数传进 CurlDoublePageContent（普通字段变化不可观察，只 bump
    //   reloadTrigger 时 content 收到的还是 setup 时的旧表，参数一次性 capture 陷阱）
    private val tableVersion = mutableIntStateOf(0)

    /** 表重算唯一构建点（本视图恒双页布局：shift/智能按配置启用） */
    private fun rebuildSpreadTable() {
        spreadTable = SpreadTable.build(
            pageList.size,
            double = true,
            startOffset = if (doublePageShift && pageList.size > 1) doublePageStart - 1 else 0,
            smart = smartMergeEnabled,
            dims = pageDims?.takeIf { it.size >= pageList.size * 2 },
            soloPages = soloPages,
        )
    }

    /** 起始页变化（设置滑条）：表重排 + 根作用域重组锚定 */
    fun setDoublePageStart(start: Int) {
        val v = start.coerceIn(2, 10)
        if (doublePageStart == v) return
        doublePageStart = v
        rebuildSpreadTable()
        tableVersion.intValue++
    }

    /** 页豁免变化（目录面板长按切换/全重置）：表映射变了才 bump 触发重组锚定 */
    fun setSoloPages(pages: Set<Int>) {
        val fresh = pages.filter { it in pageList.indices }.toSet()
        if (soloPages == fresh) return
        val old = spreadTable
        soloPages = fresh
        rebuildSpreadTable()
        if (!spreadTable.sameMapping(old)) tableVersion.intValue++
    }

    /** ★ 尺寸表回填：重排后触发根作用域重组（新表实例传进 content，
     *  content 的 LaunchedEffect(currentSpread)→snapTo 自动锚定新表位） */
    fun setPageDims(dims: IntArray?) {
        val valid = dims?.takeIf { pageList.isEmpty() || it.size >= pageList.size * 2 }
        if (pageDims != null && valid != null && pageDims.contentEquals(valid)) return
        pageDims = valid
        val old = spreadTable
        rebuildSpreadTable()
        if (!spreadTable.sameMapping(old)) tableVersion.intValue++
    }

    /** ★ 智能配对开关（config.doubleMergeSmart） */
    fun setSmartMerge(enabled: Boolean) {
        if (smartMergeEnabled == enabled) return
        smartMergeEnabled = enabled
        val old = spreadTable
        rebuildSpreadTable()
        if (!spreadTable.sameMapping(old)) tableVersion.intValue++
    }

    var pageBgColor: Int = android.graphics.Color.parseColor("#FFFFFF")
    // ★ 缩放参数用 Compose state 承载（同 CurlReaderView）：普通 var 改了不重组
    private val landscapeZoomState = mutableStateOf(false)
    var landscapeZoom: Boolean
        get() = landscapeZoomState.value
        set(value) { landscapeZoomState.value = value }
    // ★ 画面缩放档位/缩放起始位置（ScalePlan 唯一公式出处；2026-10-05 替代 fitScreen 布尔）
    private val scaleModeState = mutableIntStateOf(ScalePlan.MODE_FIT_WIDTH)
    var scaleMode: Int
        get() = scaleModeState.intValue
        set(value) { scaleModeState.intValue = value }
    private val zoomStartState = mutableIntStateOf(ScalePlan.ZOOM_DEFAULT)
    var zoomStart: Int
        get() = zoomStartState.intValue
        set(value) { zoomStartState.intValue = value }
    // ★ 双击缩放（同单页，作用于整个跨页显示矩形）
    // state 承载（同缩放参数）：书内面板运行时改开关也要重组生效
    private val doubleTapZoomState = mutableStateOf(true)
    var doubleTapZoom: Boolean
        get() = doubleTapZoomState.value
        set(value) { doubleTapZoomState.value = value }
    val curlZoomState = CurlZoomState()
    // 「仅画面区域」用 Compose 状态承载：NES 里 delegate.setup()（内部 renderCompose 组合）
    // 先执行、本字段赋值在后，普通 var 赋值不触发重组 → 勾选设置后当前页不变，翻页时
    // 其他状态变化引起重组才读到新值。改 state 后赋值即重组，当前页立即应用留边。
    private val curlContentAreaOnlyState = mutableStateOf(false)
    var curlContentAreaOnly: Boolean
        get() = curlContentAreaOnlyState.value
        set(value) { curlContentAreaOnlyState.value = value }
    // 翻页背面透出程度同用 state 承载（NES 侧经 SideEffect 同步，滑块拖动即重组生效）
    private val curlBackContentAlphaState = mutableFloatStateOf(0.1f)
    var curlBackContentAlpha: Float
        get() = curlBackContentAlphaState.floatValue
        set(value) { curlBackContentAlphaState.floatValue = value }
    // 翻页阴影浓度同上（0=关闭阴影）
    private val curlShadowAlphaState = mutableFloatStateOf(0.2f)
    var curlShadowAlpha: Float
        get() = curlShadowAlphaState.floatValue
        set(value) { curlShadowAlphaState.floatValue = value }
    // 书脊沟阴影浓度同上（0=关闭；仅双页 curl 的书脊贴合过渡渐变）
    private val spineShadowAlphaState = mutableFloatStateOf(0.09f)
    var spineShadowAlpha: Float
        get() = spineShadowAlphaState.floatValue
        set(value) { spineShadowAlphaState.floatValue = value }
    // ★ 纸背纹理 preset（-1=不启用；NES SideEffect 写入）
    private val paperBackPresetIdState = mutableIntStateOf(-1)
    var paperBackPresetId: Int
        get() = paperBackPresetIdState.intValue
        set(value) { paperBackPresetIdState.intValue = value }
    // ★ 整屏幕纸纹（textureScope=1）：背景铺纹理开关与浓度（NES 按 config 传入）
    private val paperBgOnState = mutableStateOf(false)
    var paperBgOn: Boolean
        get() = paperBgOnState.value
        set(value) { paperBgOnState.value = value }
    private val paperBgConcState = mutableIntStateOf(100)
    var paperBgConcentration: Int
        get() = paperBgConcState.intValue
        set(value) { paperBgConcState.intValue = value }
    var remotePageSupply: RemotePageSupply? = null
    /** 当前注册的页数增长监听（setup 先摘后挂、release 摘除） */
    private var growListener: ((Int) -> Unit)? = null
    var onChapterEnd: ((Boolean) -> Unit)? = null

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onPageCountChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null

    private var clickFlipType: Int = 1
    private var clickFlipInvertH = false
    private var navigation: com.mangareader.native.ViewerNavigation = com.mangareader.native.DefaultNavigation

    private fun applyNavigation() {
        // ★ 映射收敛到 buildNavigation（与 PagerReaderView 同源）
        navigation = com.mangareader.native.buildNavigation(clickFlipType, clickFlipInvertH, verticalPager = false)
    }

    override fun setClickFlipInvert(invert: Boolean) {
        if (clickFlipInvertH == invert) return
        clickFlipInvertH = invert
        applyNavigation()
    }

    override fun setClickFlipTypeLive(type: Int) {
        if (clickFlipType == type) return
        clickFlipType = type
        applyNavigation()
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // ★ 仿真双击缩放（作用于整个跨页）：1x↔2x；isDoubleTapZoom=false 完全关闭
            if (!doubleTapZoom || width == 0 || height == 0) return false
            if (curlZoomState.scale.floatValue > 1.5f) {
                curlZoomState.reset()
            } else {
                curlZoomState.originX.floatValue = (e.x / width).coerceIn(0f, 1f)
                curlZoomState.originY.floatValue = (e.y / height).coerceIn(0f, 1f)
                curlZoomState.scale.floatValue = 2f
            }
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width == 0 || height == 0) return false
            val fx = e.x / width
            val fy = e.y / height
            // RTL 与 pager 同款：区域固定（左 PREV/右 NEXT），RTL 在动作层翻转
            when (navigation.getAction(fx, fy)) {
                com.mangareader.native.NavigationAction.PREV -> if (isRtl) tapForwardTrigger.intValue++ else tapBackwardTrigger.intValue++
                com.mangareader.native.NavigationAction.NEXT -> if (isRtl) tapBackwardTrigger.intValue++ else tapForwardTrigger.intValue++
                com.mangareader.native.NavigationAction.MENU -> onToggleUi?.invoke()
            }
            return true
        }
    })

    private val flipSound = CurlFlipSound(context)

    /** 落页纸声：翻到新 spread 时播放（设置里可关，读实时 pref 免重建视图） */
    private fun playFlipSound() {
        flipSound.play()
    }

    init {
        addView(composeView)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return true
    }

    fun setTrimWhiteBorder(trim: Boolean, threshold: Int = 220, whiteRatio: Float = 0.90f) {
        loader.trimWhiteBorder = trim
        loader.trimThreshold = threshold
        loader.trimWhiteRatio = whiteRatio
    }

    /** ★ 固定裁剪即时生效（仅本书功能）：清缓存重载当前 spread（同 applyEinkDitherNow 模式，低频操作可接受） */
    fun setFixedCrop(l: Float, t: Float, r: Float, b: Float) {
        if (loader.cropL == l && loader.cropT == t && loader.cropR == r && loader.cropB == b) return
        loader.cropL = l; loader.cropT = t; loader.cropR = r; loader.cropB = b
        cache.clear()
        reloadTrigger.intValue++
        loader.preloadAround(currentPage, pageList.size) { reloadTrigger.intValue++ }
    }

    /** ★ 墨水屏灰阶抖动透传 loader（单/双页 curl 共用解码管线） */
    fun setEinkDither(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        loader.einkDither = enabled
        loader.einkDitherAlgorithm = algorithm
        loader.einkDitherLevels = levels
        loader.einkEndpointPassthrough = endpointPassthrough
    }

    /** ★ 抖动参数即时生效：清缓存重载当前 spread（重组短暂白帧后按新参数回显，低频操作可接受） */
    fun applyEinkDitherNow(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        if (loader.einkDither == enabled && loader.einkDitherAlgorithm == algorithm && loader.einkDitherLevels == levels && loader.einkEndpointPassthrough == endpointPassthrough) return
        loader.einkDither = enabled
        loader.einkDitherAlgorithm = algorithm
        loader.einkDitherLevels = levels
        loader.einkEndpointPassthrough = endpointPassthrough
        cache.clear()
        reloadTrigger.intValue++
        preloadSpread(currentPage)
    }

    /** ★ 纸张纹理即时生效：全局 PaperPageTexture 已由 NES 先行更新（含升代），清缓存重载当前 spread */
    fun applyPaperTextureNow() {
        cache.clear()
        loader.clearBakeTracking()   // 换纹理后旧"已烘焙"标记作废，否则新浓度不会重烘
        reloadTrigger.intValue++
        preloadSpread(currentPage)
    }

    fun setPanNavigation(pan: Boolean) {
    }

    fun setDoublePageModeAndShift(doubleMode: Boolean, shiftMode: Boolean) {
        if (this.doublePageShift == shiftMode) return
        this.doublePageShift = shiftMode
        rebuildSpreadTable()
        tableVersion.intValue++
    }

    fun setDoublePageReverse(reverse: Boolean) {
        this.doublePageReverse = reverse
    }

    override fun setup(
        engine: ArchiveEngine,
        treePath: String,
        pages: List<ArchiveEngine.PageMeta>,
        startPage: Int,
        vertical: Boolean,
        clickFlip: Int,
        rtl: Boolean,
        verticalPager: Boolean,
    ) {
        this.engine = engine
        this.treePath = treePath
        this.pageList = pages
        this.currentPage = startPage
        this.isRtl = rtl
        // ★ 跨页表随页表重建（pageDims 长度不匹配时自动回退朴素配对）
        rebuildSpreadTable()
        // ★ 点击翻页区域接线（同 CurlReaderView）
        if (clickFlipType != clickFlip) {
            clickFlipType = clickFlip
            applyNavigation()
        }
        // ★ 上报真实页数，防上一章条漫的展开页数残留（见 PagerReaderView.setup 同注）
        onPageCountChanged?.invoke(pageList.size)
        loader.bind(engine, treePath, pages, remotePageSupply)
        loader.setCurrentPage(startPage)
        // ★ 远程 solid 书页数增长接入（2026-10-11 审查）：与 CurlReaderView 同款——
        //   此前 curl 双页不接 onPageCountGrow，页表恒为开局快照，增长页永远翻不到
        growListener?.let { old -> remotePageSupply?.onPageCountGrow?.remove(old) }
        val listener = { newCount: Int ->
            if (newCount > pageList.size) {
                val src = treePath.substringAfterLast('.', "").lowercase().let { e -> if (e == "cbz") "zip" else e }
                pageList = pageList + (pageList.size until newCount).map {
                    ArchiveEngine.PageMeta(it, src, "", localIdx = it, originalPageIdx = it, name = "")
                }
                engine?.let { e -> loader.bind(e, treePath, pageList, remotePageSupply) }
                rebuildSpreadTable()
                onPageCountChanged?.invoke(pageList.size)
                renderCompose()
                preloadSpread(currentPage)
            }
        }
        remotePageSupply?.onPageCountGrow?.add(listener)
        growListener = listener
        renderCompose()
        preloadSpread(startPage)
    }

    private fun currentSpread(): Int =
        spreadTable.pageToSpread(currentPage)

    private fun totalSpreads(): Int =
        spreadTable.slotCount

    private fun preloadSpread(page: Int) {
        val spread = spreadTable.pageToSpread(page)
        val pages = buildSet {
            for (s in (spread - 1)..(spread + 1)) {
                if (s < 0 || s >= spreadTable.slotCount) continue
                add(spreadTable.firstPage(s))
                val r = spreadTable.secondPage(s)
                if (r >= 0) add(r)
            }
        }
        // 接入缓存保护（对齐单页 preloadAround 的 protectThreeLayers）：protectedSet 恒空时
        // trimMemory→trimNonProtected 会清光全部位图包括正在显示的 spread，白屏到下次翻页
        cache.protectPages(pages)
        val currentLeft = spreadTable.firstPage(spread)
        val currentRight = spreadTable.secondPage(spread)
        for (p in pages) {
            loader.loadPageAsync(p, isPreload = (p != currentLeft && p != currentRight)) { reloadTrigger.intValue++ }
        }
    }

    private fun renderCompose() {
        composeView.setContent {
            // ★ 根作用域订阅表版本：跨页表重排（尺寸回填/智能开关/shift 切换）后
            //   重组重读 spreadTable 字段，新表实例才会传进 CurlDoublePageContent
            tableVersion.intValue
            CurlDoublePageContent(
                totalSpreads = totalSpreads(),
                currentSpread = currentSpread(),
                totalPages = pageList.size,
                table = spreadTable,
                isRtl = isRtl,
                doublePageReverse = doublePageReverse,
                pageBgColor = pageBgColor,
                cache = cache,
                landscapeZoom = landscapeZoom,
                scaleMode = scaleMode,
                zoomStart = zoomStart,
                zoom = if (doubleTapZoom) curlZoomState else null,
                curlContentAreaOnly = curlContentAreaOnly,
                backContentAlpha = curlBackContentAlpha,
                shadowAlpha = curlShadowAlpha,
                spineShadowAlpha = spineShadowAlpha,
                paperBackPresetId = paperBackPresetId,
                bgTextureOn = paperBgOn,
                bgTextureConcentration = paperBgConcentration,
                reloadTrigger = reloadTrigger,
                tapForwardTrigger = tapForwardTrigger,
                tapBackwardTrigger = tapBackwardTrigger,
                onFlipTargetNeeded = { targetSpread ->
                    // ★ 翻页动画开始的提权通道：目标跨页两页以非预载(绕过信号量)立即解码，
                    //   已在缓存则 loadPageAsync 幂等空转。快翻连续触发也安全（5s 去重）
                    //   （读视图级 spreadTable 字段——尺寸回填后闭包自动用新表，无闭包过期）
                    loader.loadPageAsync(spreadTable.firstPage(targetSpread), isPreload = false) { reloadTrigger.intValue++ }
                    val r = spreadTable.secondPage(targetSpread)
                    if (r >= 0) loader.loadPageAsync(r, isPreload = false) { reloadTrigger.intValue++ }
                },
                onSpreadChanged = { newSpread ->
                    // ★ 表重排瞬态（尺寸回填/开关切换恰逢翻页拖拽中）PageCurl 落位可能越出新表
                    //   范围——clamp 防 firstPage 的 getOrElse 兜底把进度拽回首页并误触章末回调
                    val slot = newSpread.coerceIn(0, (spreadTable.slotCount - 1).coerceAtLeast(0))
                    val newPage = spreadTable.firstPage(slot)
                    playFlipSound()
                    currentPage = newPage
                    loader.setCurrentPage(newPage)
                    preloadSpread(newPage)
                    if (slot == totalSpreads() - 1) onChapterEnd?.invoke(true)
                    if (slot == 0) onChapterEnd?.invoke(false)
                    onPageChanged?.invoke(newPage)
                },
            )
        }
    }

    override fun goToPage(page: Int) {
        if (page < 0 || page >= pageList.size) return
        currentPage = page
        loader.setCurrentPage(page)
        reloadTrigger.intValue++
        preloadSpread(page)
    }

    override fun release() {
        growListener?.let { old -> remotePageSupply?.onPageCountGrow?.remove(old) }
        growListener = null
        flipSound.release()
        loader.cancelAll()
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        loader = CurlPageLoader(context, cache, scope)
        composeView.disposeComposition()
    }

    override fun trimMemory(level: Int) {
        cache.trimNonProtected(level)
    }

    override fun isCurrentPageReady(): Boolean {
        val spread = currentSpread()
        val leftIdx = spreadTable.firstPage(spread)
        val leftBmp = cache.get(leftIdx)
        if (leftBmp == null || leftBmp.isRecycled) return false
        // 右页存在时也需就绪：该方法是"当前页已渲染出内容"的就绪信号，右页缺位时
        // 返回 true 会让调用方提前撤遮罩露出半边白
        val rightIdx = spreadTable.secondPage(spread)
        if (rightIdx < 0) return true
        val rightBmp = cache.get(rightIdx)
        return rightBmp != null && !rightBmp.isRecycled
    }

    override fun drainLiveBitmaps(): Map<Int, Bitmap> {
        return cache.drainAll()
    }

    override fun adoptBitmaps(map: Map<Int, Bitmap>) {
        cache.adopt(map)
    }
}