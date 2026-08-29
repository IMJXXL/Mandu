package com.mangareader.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.FrameLayout

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import com.mangareader.data.Book
import com.mangareader.data.ReadingHistory
import com.mangareader.data.ReadingProgress
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.viewer.NaturalComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** ★ §24 P9 调节点：动图数量阈值 —— 默认 3 张动图才弹"切条漫"对话框 */
object ReaderParams {
    // ★ 动图打开提速（2026-08-27）：采样页数 5→3、绝对门限 3→2。动图卷第 1 页常为静态封面，
    //   3 页里 ≥2 张动图（比例 ≥60% 不变）既保判定又砍 40% 采样 IO。Webtoon/Pager 的
    //   onAnimDetected 累计路径也共用此常量，语义仍成立（≥2 张即提示）。
    const val ANIM_PROMPT_THRESHOLD: Int = 2
}

    enum class Mode(val label: String) {
        FLIP("单页式（从左到右）"),
        RTL("单页式（从右到左）"),
        VERT_PAGER("单页式（从上到下）"),
        VERT("条漫"),
        WEBTOON_GAP("条漫（页间有空隙）")
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Reader(bookId: Long, jumpPath: String? = null, back: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val app = ctx.applicationContext as App
    val dao = app.db.mangaDao()
    val engine = remember { ArchiveEngine(ctx) }
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var currentBookId by remember { mutableLongStateOf(bookId) }
    var config by remember { mutableStateOf(com.mangareader.data.ReaderConfig.load(ctx, bookId)) }
    var configVersion by remember { mutableIntStateOf(0) }

    // 监听 SharedPreferences 外部修改，自动触发 config 重读
    DisposableEffect(bookId) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("book_${bookId}_") == true || key == "scrollMode" || key == "readBackground") {
                configVersion++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var book by remember { mutableStateOf<Book?>(null) }
    var pageList by remember { mutableStateOf(listOf<ArchiveEngine.PageMeta>()) }
    var cur by remember { mutableIntStateOf(0) }
    // ★ v1.11 全局默认阅读模式：原先三处重复的 mode 加载逻辑统一收敛到此函数。
    //   优先级（用户拍板：全局最低，只作"新书第一次进入"的初始状态）：
    //     ① read_mode_$bookId（书内手动切换过，最高）
    //     ② default_read_mode（设置页全局默认）
    //     ③ config.scrollMode 推导兜底
    //   autoWebtoon 不在此列：enterReader 判定块自带 userSetMode<0 守卫——
    //   开启时新书首开仍按宽高比智能覆盖②（切 VERT），关闭时严格用②，正好满足"全局最低"语义。
    fun resolveInitialMode(targetBookId: Long): Mode {
        val savedMode = prefs.getInt("read_mode_$targetBookId", -1)
        if (savedMode in Mode.entries.indices) return Mode.entries[savedMode]
        val globalDefault = prefs.getInt("default_read_mode", -1)
        if (globalDefault in Mode.entries.indices) return Mode.entries[globalDefault]
        return when (config.scrollMode) {
            com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL -> Mode.VERT
            com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT -> Mode.FLIP
            com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT -> Mode.RTL
        }
    }
    var mode by remember { mutableStateOf(resolveInitialMode(bookId)) }

    // Reload mode when bookId or config changes
    LaunchedEffect(bookId, configVersion) {
        // 二次签名校验（与 MainActivity 互补，任一被移除则崩溃）
        com.mangareader.SignatureVerifier.verify(ctx, "F6D95465BE7D17C1B3BE2B0C59969C22")
        config = com.mangareader.data.ReaderConfig.load(ctx, bookId)
        mode = resolveInitialMode(bookId)
        // 同步：确保 scrollMode 和 read_mode 一致
        val expectedScrollMode = when (mode) {
            Mode.VERT, Mode.WEBTOON_GAP -> com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL
            Mode.RTL -> com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT
            else -> com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT
        }
        if (config.scrollMode != expectedScrollMode) {
            config = config.copy(scrollMode = expectedScrollMode)
            config.save(ctx, bookId)
        }
    }
    // ★ v1.11 P0-B6：进卷默认沉浸（ui=false）——底部进度条不在屏上，快滑手指扫过底栏区域
    //   不会再误触 Slider（实机堆栈实锤的回跳根因）。单击画面 toggleUi 呼出，10s 自动隐藏照旧。
    var ui by remember { mutableStateOf(false) }
    var uiJob by remember { mutableStateOf<Job?>(null) }
    // ★ 1.001 沉浸式锁定：false=开锁（UI 照常唤出）；true=锁定（toggleUi 被拦截，沉浸式阅读）
    var isLocked by remember { mutableStateOf(false) }
    // 锁定状态下点锁位置后弹出锁图标；3 秒自动消失；点它解锁
    var showLockIcon by remember { mutableStateOf(false) }
    var lockIconJob by remember { mutableStateOf<Job?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var navStack by remember { mutableStateOf(listOf<String>()) }
    var effectiveRootPath by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<ArchiveEngine.Item>()) }
    var tocItems by remember { mutableStateOf(listOf<ArchiveEngine.Item>()) }
    var viewing by remember { mutableStateOf(false) }
    var showComplete by remember { mutableStateOf(false) }
    var hasShownCompleteThisSession by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    // ★ ④ 修复：listPages 失败（源文件丢失或 path 错乱）时友好提示 + 用户主动选删单卷 progress
    //   与 showErrorDialog（loadedBook==null → 整本删除）分开：本弹窗针对单卷丢失，删除只限本卷 progress，不过激。
    var showFileMissingDialog by remember { mutableStateOf(false) }
    var missingFilePath by remember { mutableStateOf("") }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSrPanel by remember { mutableStateOf(false) }
    var showSrQueue by remember { mutableStateOf(false) }
    var srReadyTick by remember { mutableStateOf(0) }
    // ★ v1.13 C-2 无感切换遮罩：提交 mode 前同步 view.draw 截当前画面，盖住
    //   "旧 delegate release → 新 delegate 全量重建+解码"的占位空窗；500ms 兜底撤除。
    //   动图弹窗路径（useAnimPager 直设）不经 onModeChange，一期无遮罩降级（C-1 已保不白屏）
    var switchMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var switchMaskVisible by remember { mutableStateOf(false) }
    var readerContainerRef by remember { mutableStateOf<FrameLayout?>(null) }
    // ★ §34.3：VERT_PAGER 是竖向翻页（不是条漫），不归入 isVerticalMode（条漫连续滚动）。
    //   isVerticalMode 只覆盖真正连续滚动的条漫（VERT / WEBTOON_GAP）。
    val isVerticalMode = mode == Mode.VERT || mode == Mode.WEBTOON_GAP
    val isRtl = mode == Mode.RTL
    // ★ §34.3：VERT_PAGER 翻页模式（竖向 ViewPager2）——与 isVerticalMode（条漫）分开
    val isVerticalPagerMode = mode == Mode.VERT_PAGER

    // ★ §36 目录/网格子项阅读状态徽章 + §37 刷 progressForToc
    //   一次拉该书所有 reading_progress，共享给 ReaderTocSheet 与 ItemGrid 两处徽章计算，
    //   不在 sheet 内每项一把 dao 查询，避免打开目录时 50+ 列目录就 50+ 次 DB 查询。
    //   ★ §37(15) 条件加 viewing 依赖——子项 grid(ItemsGrid) 退出阅读(viewing=false)回到目录时也刷一次，
    //     确保最近读过的子项状态能在网格徽章反映出来。
    var progressForToc by remember { mutableStateOf<List<com.mangareader.data.ReadingProgress>>(emptyList()) }
    LaunchedEffect(showToc, viewing, book?.id) {
        if (showToc || !viewing) {
            val b = book
            if (b != null) {
                progressForToc = withContext(Dispatchers.IO) { dao.getAllProgressForBook(b.id) }
            }
        }
    }

    var showNavOverlay by remember { mutableStateOf(false) }
    var scrollX by remember { mutableIntStateOf(0) }
    var scrollY by remember { mutableIntStateOf(0) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var showChapterTransition by remember { mutableStateOf(false) }
    var chapterTransitionIsLast by remember { mutableStateOf(false) }
    var showSwitchConfirm by remember { mutableStateOf(false) }
    var switchTargetName by remember { mutableStateOf("") }
    var switchTargetPath by remember { mutableStateOf("") }

    val displayPages = remember(pageList) { pageList }

    var saveJob by remember { mutableStateOf<Job?>(null) }
    var activePagerRef by remember { mutableStateOf<com.mangareader.native.PagerReaderView?>(null) }
    var activeWebtoonRef by remember { mutableStateOf<com.mangareader.native.WebtoonReaderView?>(null) }

    // ★ §24 P9 Compose state：动图对话框
    var showAnimSwitchDialog by remember { mutableStateOf(false) }
    var animSuggestedCount by remember { mutableStateOf(0) }
    /** §24 P9c：进入本卷时缓存的 choice 值——=0/未答 → 阈值达标时弹; =1/2 已答 → 跳过弹 */
    var animDialogChoiceForChapter by remember { mutableStateOf(0) }
    /** ★ §34.5：动图专属视图开关——进卷批量 peek 达阈值且用户确认后置 true。
     *  NativeReaderScreen 用它路由到 AnimPagerReaderView（不进 PagerReaderView 的复杂 workaround）。 */
    var useAnimPager by remember { mutableStateOf(false) }
    /** ★ §34.5：批量 peek 进行中标志——避免重复触发 + 阻止 useAnimPager 决策前先显示 loading */
    var animPeeking by remember { mutableStateOf(false) }

    fun hideUi() { hideSystemBars(act, config.isImmersiveMode, config.isKeepScreenOn) }
    fun showUi() { showSystemBars(act, config.isKeepScreenOn) }
    fun restoreUi() { restoreSystemBarsSync(act) }

    LaunchedEffect(viewing) {
        if (viewing) { delay(100); hideUi() }
    }

    /**
     * ★ §37(14) 父书三态聚合——综合方案 D：纯 SQL + 后台递归核验。
     *
     *   语义（与原版一致，不算页数、完全基于子卷的 completed 状态）：
     *     - 所有读过子卷的 progress 行都 completed=true → "已读完"
     *     - 含至少一卷进度但有未完成卷 → "阅读中"
     *     - 全无进度（progress 表无行）→ 不动 Book 字段（保持"未读"）
     *
     *   ★ 与原版（turn 4 全扫模式）的根本差异：
     *     原版在每次调用都递归 engine.children 算"父书真实子卷数 totalSubs"
     *     → 大父书几百上千次 DocumentsContract.query → 几秒阻塞。
     *     综合方案 D 改成"纯 SQL 判定 + 异步递归核验"：
     *       ① 常规路径（progress 行不全 completed）→ 直接 1 选 + 1 写 = 几毫秒
     *       ② "全部 completed" 候选分支 → 先占位写"在读中" + 后台异步递归 children 算真实子卷数
     *          → 核验通过才写"已读完"，失败保守保留占位"
     *          → 不阻 UI、用户在 Reader 翻页期间后台跑完
     *     进 Reader 一行 SQL、Library 显示靠 Book 字段（Flow 自动重渲染）。
     *
     *   ★ z: 父书天然支持：
     *     原版 §37(18) 加的 itemToChPath z 分支是为"递归 children 拼 chPath"而存在，
     *     综合方案 D 砍掉递归 children 后、progress 行的 chPath 由 enterReader upsert 写入原本就统一，
     *     不再需要 §37(18) 的 chPath 拼接辅助 → 整段 itemToChPath / collectLeavesRec 已删除。
     *
     *   ★ 边界：单卷书（a: 类型，filePath 以 a: 开头）保留传统页数累计算法，不算子卷。
     *
     *   函数定义在 save() 之前——Kotlin 局部函数不支持前向引用。
     */
    suspend fun syncBookStatusFromProgress(b: com.mangareader.data.Book, now: Long, totalHint: Int = -1) {
        val allProgress = dao.getAllProgressForBook(b.id)
        if (allProgress.isEmpty()) return  // 全无进度 → 保持"未读"，不写 Book

        // ★ 单卷书（a:）：保留传统页数累加 → readPages = sum(completed 卷的 lastPage+1)
        //   多卷父书走下面的子卷计数路径。
        //   ★ v1.12 修复（MOBI/EPUB 一翻页即"已读完"实锤）：旧逻辑 newReadPages 只累加
        //     completed 行——未读完时恒 0；totalPages 又因 enterReader 只写 DB 未同步内存
        //     state 而为 0 → 双兜底 totalPages=1/readPages=1 → Library isFinished 误判徽章绿。
        //   现改为：readPages 取最新进度行 lastPage+1；totalPages 用 max(totalHint, b.totalPages)。
        if (b.filePath.startsWith("a:")) {
            val latest = allProgress.maxByOrNull { it.updatedAt }
            val realTotal = listOf(totalHint, b.totalPages).filter { it > 0 }.maxOrNull()
                ?: ((latest?.lastPage ?: 0) + 1)
            if (latest != null && (latest.lastPage > 0 || latest.completed)) {
                val rp = if (latest.completed) realTotal else (latest.lastPage + 1).coerceIn(1, realTotal)
                dao.updateBook(b.copy(totalPages = realTotal, readPages = rp, lastReadAt = now))
            } else {
                dao.updateBook(b.copy(lastReadAt = now))
            }
            return
        }

        // ★ 综合方案 D 辅助：递归 engine.children 数父书所有"叶子卷"数。
        //   仅在下面"全 completed" 候选分支被调用；z: 父书只递归一层（listZipDirs 不嵌套），
        //   d: 父书 BFS 递归深度 ≤8 防爆栈。返回所有叶子卷数。
        suspend fun countAllSubLeaves(filePath: String): Int {
            var count = 0
            suspend fun rec(fp: String, depth: Int) {
                if (depth > 8) return
                val items = try { engine.children(fp) } catch (_: Exception) { emptyList() }
                if (items.isEmpty()) return
                for (item in items) {
                    if (!item.dir) {
                        count++  // dir=false 子文件 = 叶子卷
                    } else if (fp.startsWith("z:")) {
                        // z: 不再递归——压缩包内子卷不嵌套；dir=true 子卷本身即"叶子"算 1 个
                        count++
                    } else {
                        // d: 父书的 dir=true 子夹：递归进去
                        val childPath = "d:${item.auth}|${item.tree}|${item.doc}"
                        rec(childPath, depth + 1)
                    }
                }
            }
            rec(filePath, 0)
            return count
        }

        // ★ 多卷父书（d: / z:）：走 progress 表 + completed 计数
        val completedCount = allProgress.count { it.completed }
        val freshUnread = allProgress.any { !it.completed }  // 有未完成卷 in progress
        val progressSubs = allProgress.size                 // 这是"用户已接触过的子卷数"下界

        if (freshUnread) {
            // 有未完成卷 → 确定在读中：直接写新字段
            // ★ 修复（2026-08-21 根因排查）：⑥ 修复给 getAllProgressForBook 加了 `updatedAt > 0`
            //   过滤占位行后，真实读过的子卷可能只有 1 卷 → progressSubs=1 → newTotal=1 →
            //   readPages=1 与 totalPages=1 相等 → Library.kt:550 的 isFinished
            //   `totalPages > 0 && readPages >= totalPages` 命中 → 误判"已读完"。
            //   原 readPages=1 写死的占位常量是 design 假设 totalPages 永远 > 1；现在不成立。
            //   修复：readPages 用 newTotal-1，保证 totalPages 永远比 readPages 大 1，落在
            //   isFinished=false、isReading=true（lastReadAt>0）的"在读中"语义。
            //   newTotal-1 作为占位不参与精度展示（父书未读完时 totalPages/readPages 仅做
            //   徽章聚合指示，不是章数真实值；真实子卷数只在下面候选分支完全完成时才 settle）。
            val newTotal = maxOf(b.totalPages, progressSubs)
            val newRead = (newTotal - 1).coerceAtLeast(0)
            dao.updateBook(b.copy(totalPages = newTotal, readPages = newRead, lastReadAt = now))
            return
        }

        // ★ 候选分支：allProgress 行全 completed=true
        //   两种可能：
        //     ① 真的读完了全部子卷（progress 行数 == 真实子卷总数）→ "已读完"
        //     ② 只读完了已接触过的某些卷、还有其它卷没进 progress 表（老数据空白场景）→ 实际"在读中"
        //   立即先写"在读中"占位（不偏到"已读完"），再后台异步递归 children 核验真实子卷数。
        val placeholderTotal = maxOf(b.totalPages, progressSubs)
        dao.updateBook(b.copy(totalPages = placeholderTotal, readPages = 1, lastReadAt = now))
        // ★ 后台核验：递归 engine.children 数所有真实子卷（叶子卷）→ completedCount >= realSubs 才确认"已读完"
        //   跑在 Dispatchers.IO 后台、Reader Composable scope 内、用户退出 Reader 时自动取消
        scope.launch(Dispatchers.IO) {
            try {
                val realSubs = countAllSubLeaves(b.filePath)
                // 核验通过：真已读完 → 更新为"已读完"；totalPages 设为 realSubs 让徽章基数变准
                // 核验失败（completedCount < realSubs）：保守保持占位"在读中"，下次 save 再试一次
                if (completedCount >= realSubs && realSubs > 0) {
                    dao.updateBook(b.copy(totalPages = realSubs, readPages = realSubs, lastReadAt = now))
                }
            } catch (t: Throwable) {
            }
        }
    }

    // ★ 3+5 改动4：扩展 onDispose 兜底 save —— 与 goBack() 的 save() 互补。
    //   场景：用户按 Home 键离开后系统回收 Activity、或非 goBack 路径退出 Reader 时，
    //   Composable dispose 但 saveJob 可能未 commit。兜底再发一次 save 到 appScope 确保写盘。
    //   ★ R1 主线程预快照：onDispose 触发时 NativeReaderScreen 内层 DisposableEffect 已先 dispose
    //     （Compose 逆序 dispose）→ activePagerRef 指向的 view 已 release，不能再调 updateZoomStateFromSSIV，
    //     只能读 scrollX/scrollY/zoomLevel（上次 save 时已同步过的快照值）
    //   ★ 定义在 syncBookStatusFromProgress 之后——Kotlin 局部函数不支持前向引用，
    //     本块 onDispose 兜底的 appScope.launch lambda 内调 syncBookStatusFromProgress 必须在其定义之后。
    DisposableEffect(Unit) {
        onDispose {
            restoreUi()
            // ★ ⑤ onDispose 兜底 save：与 goBack() 的 save() 互补
            saveJob?.cancel()
            // ★ R1 主线程预快照：onDispose 触发时 NativeReaderScreen 内层 DisposableEffect 已先 dispose
            //   → activePagerRef 指向的 view 已 release，不能再调 updateZoomStateFromSSIV
            val b = book
            val pageListSnap = pageList
            if (b != null && pageListSnap.isNotEmpty()) {
                val curSnap = cur
                val chPathSnap = navStack.lastOrNull() ?: b.filePath
                val now = System.currentTimeMillis()
                // ★ v1.11：与 save() 同款多页守卫（单页列表不构成"读完"）
                val done = pageListSnap.size > 1 && curSnap >= pageListSnap.size - 1
                val sx = scrollX; val sy = scrollY; val zs = zoomLevel
                app.appScope.launch {
                    try {
                        val history = ReadingHistory(
                            bookId = b.id, pagesRead = curSnap + 1,
                            scrollX = sx, scrollY = sy, zoomLevel = zs
                        )
                        withContext(Dispatchers.IO) {
                            dao.saveReaderProgressBatch(b.id, curSnap + 1, now, chPathSnap, curSnap, done, history, pageListSnap.size)
                        }
                        syncBookStatusFromProgress(b, now, pageListSnap.size)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun save() {
        val b = book ?: return
        // ★ R10/R11 预快照：必须在 appScope.launch 之前主线程同步完成
        //   原因：activePagerRef?.updateZoomStateFromSSIV() 内部调 viewPager.getChildAt(0)、
        //   rv.layoutManager.findFirstVisibleItemPosition()、rv.findViewHolderForAdapterPosition()、
        //   holder.ssiv.scale/center —— 全是 View / RecyclerView 主线程 API，
        //   appScope 用 Dispatchers.Default，跨线程调会撞 view hierarchy 同步约束 + 可能读到不一致 layout 状态
        activePagerRef?.updateZoomStateFromSSIV()
        val cx = activePagerRef?.currentCenterX?.toInt() ?: scrollX
        val cy = activePagerRef?.currentCenterY?.toInt() ?: scrollY
        val zs = activePagerRef?.currentZoomScale ?: zoomLevel
        scrollX = cx; scrollY = cy; zoomLevel = zs
        // ★ R2 主线程同步快照所有进入协程的 Compose state
        val pageListSnap = pageList
        if (pageListSnap.isEmpty()) return
        val curSnap = cur
        val chPathSnap = navStack.lastOrNull() ?: b.filePath
        val now = System.currentTimeMillis()
        // ★ v1.11 修复：done 加多页守卫。原 `cur >= size-1` 在 pageList.size==1 时一进即 true → completed=1
        //   单向锁死后每开必跳第 1 页（实机 book=39 实锤）。单页/空列表不构成"读完"。
        val done = pageListSnap.size > 1 && curSnap >= pageListSnap.size - 1

        saveJob?.cancel()
        saveJob = app.appScope.launch {
            try {
                val history = ReadingHistory(
                    bookId = b.id, pagesRead = curSnap + 1,
                    scrollX = cx, scrollY = cy, zoomLevel = zs
                )
                withContext(Dispatchers.IO) {
                    dao.saveReaderProgressBatch(b.id, curSnap + 1, now, chPathSnap, curSnap, done, history, pageListSnap.size)
                }
                syncBookStatusFromProgress(b, now, pageListSnap.size)
            } catch (_: Exception) {
                // CrashHandler 杀进程防护：catch 全部 Exception 不传
            }
        }
    }

    var goJob by remember { mutableStateOf<Job?>(null) }
    fun go(d: Int) {
        if (displayPages.isEmpty()) return
        val n = (cur + d).coerceIn(0, displayPages.size - 1)
        if (n == cur) return
        if (n != cur) { cur = n; save() }
        if (cur >= displayPages.size - 1 && viewing && displayPages.size > 1 && !showComplete && !hasShownCompleteThisSession) {
            showComplete = true; hasShownCompleteThisSession = true
        }
    }

    /**
     * ★ §23 阅读记忆方案 — 统一"进入阅读"入口，对齐 mihon `ReaderViewModel.init` 的恢复语义。
     * 不论从书架直点 / openFolder 进子目录 / openItem 进嵌套 / switchToNext 切下一卷 / TOC 跳章 ——
     * 全部走 enterReader，由它统一设定 pageList + 从 reading_progress 表读出 lastPage 设到 cur。
     *
     * 返回 pages 供 caller 做附加处理（如 updateBook totalPages）。
     *
     * 决策 1（用户 2026-08-15）：completed==true 时跳第 1 页（对齐 mihon `!chapter.read` 才恢复语义）。
     */
    suspend fun enterReader(path: String): List<com.mangareader.viewer.ArchiveEngine.PageMeta> {
        val b = book ?: return emptyList()
        val pages = withContext(Dispatchers.IO) {
            try { engine.listPages(path) } catch (_: Exception) { emptyList() }
        }
        // ★ ④ 修复：listPages 失败/返回空 → 提示 + 不进 reading 死态。
        //   原 bug：pageList=空+viewing=true 落"没有内容"死胡同，FAB 卡死、退出键也可能无效必须杀进程。
        //   兜底：记 path → 弹 dialog 让用户选「移除本卷进度」或「返回书架」。
        //     - 点「移除本卷进度」才执行 dao.deleteProgress(b.id, path)——避免误删合法 progress（SAF 临时失败等场景）。
        //     - 默认 viewing=false 不进死态。
        if (pages.isEmpty()) {
            missingFilePath = path
            showFileMissingDialog = true
            pageList = emptyList()
            cur = 0
            viewing = false
            loading = false
            return emptyList()
        }
        pageList = pages
        val progress = withContext(Dispatchers.IO) { dao.getProgress(b.id, path) }
        // ★ v1.11 脏数据自愈：completed=1 且 lastPage==0 且多页卷 → 必为历史污染
        //   （正常"读完"路径写入的 lastPage 恒为末页索引 size-1 或 markAsReadNow 的 size-1，不可能是 0；
        //     唯一来源是旧版 done 误判/单向锁时代把 0 页码配上了 completed）。
        //   重置 completed=false 后走下方正常恢复分支，用户下次翻页即彻底自愈，无需手动清数据。
        val healed = if (progress != null && progress.completed && progress.lastPage == 0 && pages.size > 1) {
            try {
                withContext(Dispatchers.IO) { dao.upsertProgress(b.id, path, 0, false, System.currentTimeMillis(), pages.size) }
            } catch (_: Exception) {}
            true
        } else false
        // ★ §37(14) 要点2 修"读完未变已读"：progress.completed=false 但 lastPage 已是末页 → 历史上曾读到末页，
        //   但完成位没被 save() 写进去（停在末页未翻页 → onPageChanged 没 fire → done=true 那次 save 没机会触发）。
        //   顺手在此时补 completed=true，保证下次回卷/查徽章能命中"已读完"。
        // ★ ⑦B 修复：willMarkRead 是「本次进卷正在补完成位」的本地信号。
        //   下方 lastPage 判定必须用同一个信号——否则 388 行 upsert 把 DB 改 completed=true
        //   但本地 val progress 仍 false → 走 else 分支 cur=末页，与"已读完卷再入应跳第 1 页"语义矛盾。
        //   修复后：willMarkRead=true 时也跳第 1 页，与 progress.completed==true 一致。
        //   下次回卷进 enterReader 重读 progress（DB 已 true）走第 1 个分支，自愈。
        // ★ v1.11：willMarkRead 加 pages.size > 1 守卫（单页列表不构成"读到过末页"，与 save() 的 done 守卫对齐）
        val willMarkRead = !healed && progress != null && !progress.completed && pages.size > 1 && progress.lastPage >= pages.size - 1
        if (willMarkRead) {
            val now = System.currentTimeMillis()
            try { withContext(Dispatchers.IO) { dao.upsertProgress(b.id, path, progress.lastPage, true, now, pages.size) } } catch (_: Exception) {}
        }
        // ★ 已读完卷再次进入跳第 1 页（progress.completed==true 或本次正补写完成位 willMarkRead → lastPage=0）
        val lastPage = if (progress?.completed == true || willMarkRead) 0 else (progress?.lastPage ?: 0)
        cur = lastPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        // ★ §24 P9c：本卷动图对话框的选择（0=未答/1=切条漫/2=保持现状）——
        //   setup 末尾 webtoon onAnimDetected 触发 P9 弹窗时读这个标志决定是否弹
        animDialogChoiceForChapter = withContext(Dispatchers.IO) { dao.getAnimDialogChoice(b.id, path) ?: 0 }
        // ★ v1.13 用户需求：卷级封面后台生成（阅读历史显示各卷自己的封面而非书架总封面）
        //   写 covers/md5(卷path).thumb；HistoryScreen CoverThumb 双键查找时命中
        if (pages.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val key = java.security.MessageDigest.getInstance("MD5")
                        .digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
                    val f = java.io.File(ctx.filesDir, "covers/$key.thumb")
                    if (!f.exists()) {
                        val bmp = engine.loadPage(pages[0], path) ?: return@launch
                        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                    }
                } catch (_: Exception) {}
            }
        }
        // ★ §34.5：切卷时先重置 useAnimPager——避免上一卷的 useAnimPager=true 残留到"用户已答 choice==4 不再弹"的本卷。
        //   真欲进动图视图的卷会在下面 choice==3 分支里重置为 true。
        //   同时重置 showAnimSwitchDialog/animSuggestedCount/animPeeking 防止跨卷串扰
        useAnimPager = false
        showAnimSwitchDialog = false
        animSuggestedCount = 0
        animPeeking = false
        // ★ autoWebtoon 修复：原 Reader.kt:989-1011 的 LaunchedEffect(viewing, displayPages.size) 在
        //   viewing=true 之后才判 mode → 命中 VERT 时 mode 切换触发 NativeReaderScreen DisposableEffect(isVertical)
        //   重启 delegate(pager→webtoon) → webtoon RV 首帧用 WRAP_CONTENT ImageView 占位高度估算偏差 →
        //   scrollToPositionWithOffset(0,0) 定位错位 → Bug#13 校准循环 abs(f-sp)>2 命中但 isSetupScrollPending
        //   不释放 → 用户首屏看到 page 3 而非 page 0（前三页结构性不可见，退出重进正常）。
        //   修复：把判定提前到 viewing=true 之前，首次 Compose 组合时 isVertical 已是目标值，
        //   NativeReaderScreen 直接走目标 delegate 首次 setup，杜绝重启 race。
        //   语义不变（不写 prefs 保持「用户没手动设过模式」语义），仅把判定时机提前。
        //   ★ 审查修正：（1）用 enterReader 内已声明的 pages 而非闭包外的 displayPages，避免空集合跳过判定；
        //                 （2）照搬原 mode != VERT && != VERT_PAGER && != WEBTOON_GAP 短路守卫，
        //                     避免切下一卷时打掉用户上一卷手动设的模式。
        if (config.autoWebtoon && pages.size >= 3 && mode != Mode.VERT && mode != Mode.VERT_PAGER && mode != Mode.WEBTOON_GAP) {
            val userSetMode = prefs.getInt("read_mode_$bookId", -1)
            if (userSetMode < 0) {
                val treeStr = path
                var totalRatio = 0f
                var count = 0
                // ★ P2 优化：loadPageSize 只读尺寸不解帧。
                //   原版 for-i 串行 loadPage 全解码 3 页 ~300ms；
                //   本版串行 loadPageSize ~0-5ms/页（inJustDecodeBounds 扫头部 + pdfGetPageSize metadata）总 ~15ms 内。
                //   ★ 不用 async 并发：loadPageSize 已是毫秒级，3 页并发收益 <15ms 不值增加内存峰值；
                //     原 async 方案是为对冲 loadPage 的全解码耗时，P2 解决根源后不需要。
                for (i in 0 until minOf(3, pages.size)) {
                    try {
                        val sz = withContext(Dispatchers.IO) { engine.loadPageSize(pages[i], treeStr) }
                        if (sz != null && sz.size >= 2 && sz[0] > 0 && sz[1] > 0) {
                            totalRatio += sz[0].toFloat() / sz[1].toFloat()
                            count++
                        }
                    } catch (_: Exception) {}
                }
                if (count > 0 && totalRatio / count < 0.55f) {
                    mode = Mode.VERT
                }
            }
        }
        viewing = true
        // ★ §34.5：进卷批量 peek 判定动图卷。
        //   旧设计 (§24)：webtoon/pager onAnimDetected 单页 peek 累计阈值 → 弹"切翻页"框，
        //   缺点：(1) 阈值时机被动（用户翻到位后才 pop）(2) pager 自身的动图 workaround 复杂时序带来闪白/回翻白/idx 23 白一系列问题
        //   新设计 (§34.5)：enterReader 末尾后台并发 peek 全卷所有页，算动图比例 → 比例 × 达阈值 → 弹动图专属视图入口对话框
        //   确认 → useAnimPager=true → NativeReaderScreen 路由到 AnimPagerReaderView（与 PagerReaderView 并存独立的有自己的状态机）
        //   拒绝 → 持久化 choice=4，下次再不弹；user 仍能看 PagerReaderView 里 workaround 路径动图（降级兼容）
        //   优化：≤30 页全 peek；>30 页采样首 N 页（首 10 页 + 等 10 步采样），避免 1000 页全 peek 卡语言。
        //   choice 已为 3 → 上次同卷确认了进动图视图 → 直接 useAnimPager=true 不再弹
        //   choice 已为 4 → 上次同卷拒绝过 → 不弹
        //   ★ 时机：choice 服务于"用户已答"语义；choice==3 持久化后下次同卷直进动图视图，省一次弹窗；
        //     choice==4 持久化后下次同卷仍走普通 pager（看 workaround 动图 fallback）。
        // ★ v1.13 T10（用户裁定）：仅 choice==3（已进过动图视图）不再询问；
        //   其余情况（含历史 choice=4）每次打开都询问——普通模式已支持静态首帧显示
        if (animDialogChoiceForChapter == 3) {
            useAnimPager = true
        } else if (pages.isNotEmpty()) {
            animPeeking = true
            scope.launch {
                try {
                    // ★ 动图打开提速（2026-08-27）：采样 5→3 页。动图卷动图占比极高，3 页足够判定；
                    //   第 1 页常为静态封面 → 门限 3→2（比例 ≥60% 不变）。漏弹无损（普通视图有静图兜底）。
                    //   ★ ANIMT 探针：tag=ANIMT 计时采样全程，装进正式包用于实测定位剩余瓶颈。
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val sampleSize = minOf(3, pages.size)
                    val sampleIndices = (0 until sampleSize).toList()
                    var animCount = 0
                    // 并发 peek 但限制并发度 4（避免磁盘 IO 暴冲）
                    val sem = Semaphore(4)
                    val jobs = sampleIndices.map { idx ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                try {
                                    val t1 = android.os.SystemClock.elapsedRealtime()
                                    val k = engine.peekAnimKindFast(path, pages[idx])
                                    android.util.Log.i("ANIMT", "peekFast idx=$idx kind=$k ${android.os.SystemClock.elapsedRealtime() - t1}ms bytes=${engine.lastPeekBytes}")
                                    k
                                } catch (_: Exception) { com.mangareader.viewer.AnimKind.NONE }
                            }
                        }
                    }
                    val kinds = jobs.awaitAll()
                    for (k in kinds) if (k != com.mangareader.viewer.AnimKind.NONE) animCount++
                    val sampleRatio = if (sampleIndices.isNotEmpty()) animCount.toFloat() / sampleIndices.size else 0f
                    // ★ 阈值：门限 2（ANIM_PROMPT_THRESHOLD）+ 比例 ≥60%（采 3 页时即 ≥2/3）
                    if (animCount >= com.mangareader.ui.ReaderParams.ANIM_PROMPT_THRESHOLD && sampleRatio >= 0.60f) {
                        animSuggestedCount = animCount
                        showAnimSwitchDialog = true
                    }
                    android.util.Log.i("ANIMT", "sample done total=${android.os.SystemClock.elapsedRealtime() - t0}ms animCount=$animCount/$sampleSize")
                } catch (t: Throwable) {
                } finally {
                    animPeeking = false
                }
            }
        }
        return pages
    }

    fun openFolder(path: String) {
        loading = true; viewing = false; pageList = emptyList(); errorMsg = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    var r = try { engine.children(path) } catch (_: Exception) { emptyList() }
                    var newTocItems = r
                    var newPath = navStack.lastOrNull() ?: path
                    while (r.size == 1 && r[0].dir && r[0].name.isNotEmpty()) {
                        val child = r[0]
                        if (newPath.startsWith("z:")) {
                            val inner = newPath.removePrefix("z:")
                            val sepIdx = inner.indexOf("//")
                            if (sepIdx >= 0) {
                                var archivePath = inner.substring(0, sepIdx)
                                if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
                                val currentSubPath = inner.substring(sepIdx + 2)
                                val base = if (currentSubPath.isEmpty()) "" else if (currentSubPath.endsWith("/")) currentSubPath else "$currentSubPath/"
                                newPath = "z:${archivePath}//${base}${child.name}/"
                                r = try { engine.children(newPath) } catch (_: Exception) { emptyList() }
                                if (r.isNotEmpty()) newTocItems = r
                                continue
                            }
                        }
                        break
                    }
                    val pages = if (r.isEmpty()) {
                        try { engine.listPages(newPath) } catch (_: Exception) { emptyList() }
                    } else emptyList()
                    Triple(newTocItems, r, pages)
                }
                val zCount = navStack.count { it.startsWith("z:") }
                if (zCount <= 1 && result.first.isNotEmpty()) {
                    tocItems = result.first
                }
                val childItems = result.second
                val pages = result.third
                effectiveRootPath = navStack.lastOrNull() ?: path
                items = childItems
                if (items.isEmpty()) {
                    // ★ §23 统一走 enterReader 读 reading_progress 恢复 cur（pages 仅作空判，enterReader 内会复读 listPages）
                    val chapPath = effectiveRootPath
                    if (pages.isNotEmpty()) enterReader(chapPath)
                    else {
                        // ★ ④ 修复：openFolder 进入子目录 child 列空且 listPages 失败 → 同样走兜底 dialog，
                        //   不进 viewing=true 死态（原代码会落"没有内容"分支无 UI 出口）
                        missingFilePath = chapPath
                        showFileMissingDialog = true
                        pageList = emptyList(); cur = 0; viewing = false
                    }
                }
            } catch (e: Exception) { errorMsg = "加载失败: ${e.message}" }
            loading = false
        }
    }

    fun openItem(item: ArchiveEngine.Item) {
        if (item.dir) {
            val currentPath = navStack.lastOrNull()
            if (currentPath?.startsWith("z:") == true) {
                val inner = currentPath.removePrefix("z:")
                val sepIdx = inner.indexOf("//")
                if (sepIdx >= 0) {
                    val archivePath = inner.substring(0, sepIdx)
                    val currentSubPath = inner.substring(sepIdx + 2)
                    val base = if (currentSubPath.isEmpty()) "" else if (currentSubPath.endsWith("/")) currentSubPath else "$currentSubPath/"
                    val newSubPath = "${base}${item.name}/"
                    navStack = navStack + "z:${archivePath}//${newSubPath}"
                    openFolder(navStack.last())
                    return
                }
            }
            navStack = navStack + "d:${item.auth}|${item.tree}|${item.doc}"
            openFolder(navStack.last())
        } else {
            val currentPath = navStack.lastOrNull()
            if (currentPath?.startsWith("z:") == true) {
                loading = true; viewing = false; pageList = emptyList(); errorMsg = null
                scope.launch {
                    try {
                        // ★ §23 改走 enterReader 恢复 lastPage
                        enterReader(currentPath)
                    } catch (e: Exception) { errorMsg = "加载失败: ${e.message}" }
                    loading = false
                }
                return
            }
            loading = true; viewing = false; pageList = emptyList(); errorMsg = null
            scope.launch {
                try {
                    val path = withContext(Dispatchers.IO) {
                        "a:${item.auth}|${item.tree}|${item.doc}|${item.name.substringAfterLast(".").lowercase()}"
                    }
                    navStack = navStack + path
                    // ★ §23 改走 enterReader 恢复 lastPage
                    enterReader(path)
                } catch (e: Exception) { errorMsg = "加载失败: ${e.message}" }
                loading = false
            }
        }
    }

    suspend fun findAdjacentBook(direction: Int): com.mangareader.data.Book? {
        return try {
            val allBooks = dao.getAllBooksList()
            val currentPath = book?.filePath ?: return null
            val idx = allBooks.indexOfFirst { b -> b.filePath == currentPath }
            if (idx == -1) return null
            val targetIdx = idx + direction
            if (targetIdx in allBooks.indices) allBooks[targetIdx] else null
        } catch (_: Exception) { null }
    }

    fun extractSubPathFromNav(): String {
        val last = navStack.lastOrNull() ?: return ""
        if (!last.startsWith("z:")) return ""
        val inner = last.removePrefix("z:")
        val sep = inner.indexOf("//")
        return if (sep >= 0) inner.substring(sep + 2) else ""
    }

    fun switchToNext() {
        val curSubPath = extractSubPathFromNav()
        if (tocItems.size > 1) {
            val curIdx = tocItems.indexOfFirst { it.subPath == curSubPath }
            val nextIdx = if (curIdx == -1) 1 else curIdx + 1
            if (nextIdx < tocItems.size) {
                switchTargetName = tocItems[nextIdx].name
                switchTargetPath = tocItems[nextIdx].subPath
                showSwitchConfirm = true
            } else {
                scope.launch {
                    val nextBook = findAdjacentBook(1)
                    if (nextBook != null) {
                        switchTargetName = nextBook.title
                        switchTargetPath = nextBook.filePath
                        showSwitchConfirm = true
                    } else {
                        android.widget.Toast.makeText(ctx, "没有下一本了", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            scope.launch {
                val nextBook = findAdjacentBook(1)
                if (nextBook != null) {
                    switchTargetName = nextBook.title
                    switchTargetPath = nextBook.filePath
                    showSwitchConfirm = true
                } else {
                    android.widget.Toast.makeText(ctx, "没有下一本了", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun switchToPrevious() {
        val curSubPath = extractSubPathFromNav()
        if (tocItems.size > 1) {
            val curIdx = tocItems.indexOfFirst { it.subPath == curSubPath }
            val prevIdx = if (curIdx <= 0) -1 else curIdx - 1
            if (prevIdx >= 0) {
                switchTargetName = tocItems[prevIdx].name
                switchTargetPath = tocItems[prevIdx].subPath
                showSwitchConfirm = true
            } else {
                scope.launch {
                    val prevBook = findAdjacentBook(-1)
                    if (prevBook != null) {
                        switchTargetName = prevBook.title
                        switchTargetPath = prevBook.filePath
                        showSwitchConfirm = true
                    } else {
                        android.widget.Toast.makeText(ctx, "没有上一本了", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            scope.launch {
                val prevBook = findAdjacentBook(-1)
                if (prevBook != null) {
                    switchTargetName = prevBook.title
                    switchTargetPath = prevBook.filePath
                    showSwitchConfirm = true
                } else {
                    android.widget.Toast.makeText(ctx, "没有上一本了", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun confirmSwitch() {
        showSwitchConfirm = false
        if (switchTargetPath.isEmpty()) return
        if (tocItems.size > 1 && tocItems.any { it.subPath == switchTargetPath }) {
            // 切章节：重置navStack到书根+新章节，不嵌套
            val bookRoot = navStack.firstOrNull { it.startsWith("z:") } ?: switchTargetPath
            navStack = listOf(bookRoot, switchTargetPath)
            scope.launch {
                withContext(Dispatchers.IO) {
                    items = emptyList(); viewing = false; pageList = emptyList()
                    val chItems = try { engine.children(switchTargetPath) } catch (_: Exception) { emptyList() }
                    if (chItems.isEmpty()) {
                        // ★ §23 改走 enterReader 恢复 lastPage
                        enterReader(switchTargetPath)
                    } else {
                        items = chItems
                    }
                }
            }
        } else {
            save()
            viewing = false; pageList = emptyList(); items = emptyList(); tocItems = emptyList()
            navStack = listOf(switchTargetPath)
            hasShownCompleteThisSession = false
            scope.launch {
                val newBook = withContext(Dispatchers.IO) { dao.getBookByPath(switchTargetPath) }
                if (newBook != null) {
                    book = newBook
                    val newBookId = newBook.id
                    config = com.mangareader.data.ReaderConfig.load(ctx, newBookId)
                    act?.requestedOrientation = prefs.getInt("orientation_$newBookId", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                    mode = resolveInitialMode(newBookId)
                    // 同步：确保 scrollMode 和 read_mode 一致
                    val expectedScrollMode = when (mode) {
                        Mode.VERT, Mode.WEBTOON_GAP -> com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL
                        Mode.RTL -> com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT
                        else -> com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT
                    }
                    if (config.scrollMode != expectedScrollMode) {
                        config = config.copy(scrollMode = expectedScrollMode)
                        config.save(ctx, newBookId)
                    }
                }
                openFolder(switchTargetPath)
            }
        }
    }

    fun goBack() {
        restoreUi()
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        save()
        if (viewing && tocItems.isNotEmpty() && tocItems.size > 1) {
            // 有多个目录项 → 回到目录列表
            viewing = false; pageList = emptyList()
            items = tocItems
        } else if (navStack.size > 1 && navStack.last() != effectiveRootPath) {
            // 有上一级 → 回到上一级目录
            viewing = false; pageList = emptyList()
            navStack = navStack.dropLast(1)
            openFolder(navStack.last())
        } else {
            // 退出阅读器
            try { back() } catch (_: Exception) {}
        }
    }

    // ★ 3+5 改动5：重写 markAsReadNow —— 改 suspend → 普通 fun，与 save() 对称改造：
    //   appScope 脱钩（不受 Reader dispose 影响）+ 主线程预快照 + 整块 try/catch。
    //   原版是 suspend fun + withContext(IO)，用户按按钮后立即退出 → scope cancel → 砍断不完成写盘。
    fun markAsReadNow() {
        val b = book ?: return
        val pageListSnap = pageList
        if (pageListSnap.isEmpty()) return
        val chPathSnap = navStack.lastOrNull() ?: b.filePath
        val curPageCount = pageListSnap.size
        val now = System.currentTimeMillis()
        app.appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dao.upsertProgress(b.id, chPathSnap, curPageCount - 1, true, now, curPageCount)
                }
                // ★ §37(14) 父书状态聚合：按子卷 completed 计数判定阅读中/已读完/未读
                //   单卷书（a: 类型无子卷）保留原 readPages>=totalPages 语义
                syncBookStatusFromProgress(b, now, curPageCount)
            } catch (_: Exception) {}
        }
    }

    fun saveOrientation(orientation: Int) {
        act?.requestedOrientation = orientation
        prefs.edit().putInt("orientation_$bookId", orientation).apply()
    }

    fun toggleUi() {
        // ★ 1.001 锁定拦截：锁定态下不唤 UI，改为唤出锁图标（3s 自动消失）
        if (isLocked) {
            showLockIcon = true
            lockIconJob?.cancel()
            lockIconJob = scope.launch { delay(3000); if (isLocked && showLockIcon) showLockIcon = false }
            return
        }
        ui = !ui
        if (ui && viewing) {
            showSystemBars(act, config.isKeepScreenOn)
            val delayMs = if (config.isEinkMode) config.einkUiHideDelay else 10000L
            uiJob?.cancel()
            uiJob = scope.launch { delay(delayMs); if (ui) { ui = false; hideUi() } }
        } else if (!ui) {
            uiJob?.cancel()
            hideUi()
        }
    }

    // ★ 1.001 锁定/解锁切换（由左侧锁位置点击触发）
    fun toggleLock() {
        if (isLocked) {
            // 已锁定 → 唤出锁图标（不直接解锁，要用户点锁图标才解锁）
            showLockIcon = true
            lockIconJob?.cancel()
            lockIconJob = scope.launch { delay(3000); if (isLocked && showLockIcon) showLockIcon = false }
        } else {
            // 开锁 → 进入锁定沉浸式
            isLocked = true
            if (ui) { ui = false; uiJob?.cancel(); hideUi() }   // 锁定时若 UI 正显示，立刻藏掉
            showLockIcon = false
        }
    }

    // ★ 1.001 点锁图标解锁（画面干净，不自动唤 UI）
    fun unlock() {
        isLocked = false
        showLockIcon = false
        lockIconJob?.cancel()
    }

    fun restartAutoHide() {
        uiJob?.cancel()
        if (ui && viewing) {
            val delayMs = if (config.isEinkMode) config.einkUiHideDelay else 10000L
            uiJob = scope.launch { delay(delayMs); if (ui) { ui = false; hideUi() } }
        }
    }

    // ★ 1.001 viewing 转 false（回目录/退出）时重置锁定，避免 bookId 不变带着锁重进
    LaunchedEffect(viewing) {
        if (!viewing) {
            isLocked = false
            showLockIcon = false
            lockIconJob?.cancel()
        }
    }

    // Book loading - only runs when bookId changes
    LaunchedEffect(bookId) {
        // ★ 1.001 进新书重置锁定状态
        isLocked = false
        showLockIcon = false
        lockIconJob?.cancel()
        try {
            // Step 1: All IO work on Dispatchers.IO (no state mutation)
            val loadedBook = withContext(Dispatchers.IO) { dao.getBookById(bookId) }
            book = loadedBook
            loadedBook?.let { b ->
                act?.requestedOrientation = prefs.getInt("orientation_$bookId", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                val filePath = b.filePath

                if (!jumpPath.isNullOrEmpty()) {
                    // ★ 继续阅读：直达上次阅读的实际章节（reading_progress.path），不经过目录
                    navStack = listOf(filePath, jumpPath)
                    enterReader(jumpPath)
                } else if (filePath.startsWith("d:")) {
                    navStack = listOf(filePath)
                    val childItems = withContext(Dispatchers.IO) { try { engine.children(filePath) } catch (_: Exception) { emptyList() } }
                    items = childItems
                    if (childItems.isNotEmpty()) tocItems = childItems
                    // ★ §37(14) 综合方案 D：进书首屏异步刷，绝对不阻塞 UI。
                    //   原版（turn 4 全扫模式）在这里同步调 syncBookStatusFromProgress，该函数会
                    //   递归 engine.children 几百次 → 进大父书卡几秒空白。
                    //   现版 syncBookStatusFromProgress 内部本身已是纯 SQL + 后台核验（几毫秒路径），
                    //   外层再套 scope.launch 让 Book 字段刷新完全不挡 enterReader / 首屏渲染 / 预解码。
                    //   仅在父书有子卷时刷；无子卷直接走 enterReader 那条。
                    if (childItems.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            syncBookStatusFromProgress(b, System.currentTimeMillis())
                        }
                    }
                    if (childItems.isEmpty()) {
                        // ★ §23 改走 enterReader 恢复 lastPage
                        enterReader(filePath)
                    }
                } else if (filePath.startsWith("z:")) {
                    navStack = listOf(filePath)
                    var result = withContext(Dispatchers.IO) { try { engine.children(filePath) } catch (_: Exception) { emptyList() } }
                    if (result.isNotEmpty()) tocItems = result
                    var currentNavStack = navStack
                    while (result.size == 1 && result[0].dir && result[0].name.isNotEmpty()) {
                        val child = result[0]
                        val cp = currentNavStack.lastOrNull()
                        if (cp?.startsWith("z:") == true) {
                            val inner = cp.removePrefix("z:")
                            val sepIdx = inner.indexOf("//")
                            if (sepIdx >= 0) {
                                var archivePath = inner.substring(0, sepIdx)
                                if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
                                val currentSubPath = inner.substring(sepIdx + 2)
                                val base = if (currentSubPath.isEmpty()) "" else if (currentSubPath.endsWith("/")) currentSubPath else "$currentSubPath/"
                                val newPath = "z:${archivePath}//${base}${child.name}/"
                                currentNavStack = currentNavStack + newPath
                                navStack = currentNavStack
                                result = withContext(Dispatchers.IO) { try { engine.children(newPath) } catch (_: Exception) { emptyList() } }
                                if (result.isNotEmpty()) tocItems = result
                                continue
                            }
                        }
                        break
                    }
                    effectiveRootPath = navStack.lastOrNull() ?: ""
                    items = result
                    if (items.isEmpty()) {
                        val chapPath = navStack.lastOrNull() ?: filePath
                        // ★ §23 改走 enterReader 恢复 lastPage
                        val pages = enterReader(chapPath)
                        if (b.totalPages == 0 && pages.isNotEmpty()) withContext(Dispatchers.IO) { dao.updateBook(b.copy(totalPages = pages.size)) }
                    }
                } else {
                    // ★ §23 改走 enterReader 恢复 lastPage
                    val pages = enterReader(filePath)
                    if (b.totalPages == 0 && pages.isNotEmpty()) withContext(Dispatchers.IO) { dao.updateBook(b.copy(totalPages = pages.size)) }
                }
            }
            // Pre-decode first page SYNCHRONOUSLY before showing reader (instant open)
            if (pageList.isNotEmpty() && loadedBook?.filePath?.startsWith("a:") == true) {
                try {
                    val realPath = withContext(Dispatchers.IO) { engine.getRealFileForPath(loadedBook.filePath) }
                    if (realPath.isNotEmpty()) {
                        val archive = com.mangareader.native.NativeEngine.openArchive(realPath)
                        if (archive != null) {
                            try {
                                val screenMax = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
                                val raw = com.mangareader.native.NativeEngine.readPageRaw(archive, cur)
                                if (raw != null) {
                                    val bmp = com.mangareader.native.NativeEngine.decodeRaw(raw, screenMax, screenMax)
                                    if (bmp != null) {
                                        try { com.mangareader.native.NativeEngine.cacheDecodedBitmap(archive, cur, bmp) } catch (_: Throwable) {}
                                    }
                                }
                                for (i in 1 until minOf(5, pageList.size)) {
                                    try {
                                        val raw2 = com.mangareader.native.NativeEngine.readPageRaw(archive, i)
                                        if (raw2 != null) {
                                            val bmp2 = com.mangareader.native.NativeEngine.decodeRaw(raw2, screenMax, screenMax)
                                            if (bmp2 != null) {
                                                try { com.mangareader.native.NativeEngine.cacheDecodedBitmap(archive, i, bmp2) } catch (_: Throwable) {}
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            } finally { archive.close() }
                        }
                    }
                } catch (_: Exception) {}
            }
            loading = false; if (viewing) hideUi()
            if (loadedBook == null) { showErrorDialog = true; loading = false }
        } catch (e: Exception) { errorMsg = "加载失败: ${e.message}"; loading = false }
    }

    // Config refresh - only re-reads config when settings change, does NOT reload book
    LaunchedEffect(configVersion) {
        config = com.mangareader.data.ReaderConfig.load(ctx, bookId)
    }

    val bgColor = remember(configVersion) {
        // 读取全局背景色设置（无 bookId 前缀），默认白色
        val globalBgName = prefs.getString("readBackground", "WHITE") ?: "WHITE"
        val globalBg = try { com.mangareader.data.ReaderConfig.ReadBackground.valueOf(globalBgName) } catch (_: Exception) { com.mangareader.data.ReaderConfig.ReadBackground.DEFAULT }
        when (globalBg) {
            com.mangareader.data.ReaderConfig.ReadBackground.WHITE -> Color(0xFFE8E8E8)
            com.mangareader.data.ReaderConfig.ReadBackground.GRAY -> Color(0xFF202125)
            com.mangareader.data.ReaderConfig.ReadBackground.DARK -> Color(0xFF000000)
            com.mangareader.data.ReaderConfig.ReadBackground.LIGHT -> Color(0xFFF5F5F5)
            com.mangareader.data.ReaderConfig.ReadBackground.DEFAULT -> Color(0xFF111111)
        }
    }

    // ★ v1.13 C-2：遮罩截取/撤除（定义在 bgColor 之后——局部函数不支持前向引用）
    fun showSwitchMask() {
        if (switchMaskVisible) return
        val c = readerContainerRef ?: return
        val w = c.width; val h = c.height
        if (w <= 0 || h <= 0) return
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(bgColor.toArgb())
            c.draw(canvas)
            switchMaskBitmap = bmp
            switchMaskVisible = true
        } catch (_: Throwable) { }
    }

    fun hideSwitchMask() {
        switchMaskVisible = false
        switchMaskBitmap?.recycle()
        switchMaskBitmap = null
    }

    // ★ v1.13 就绪轮询撤除（mihon 式"数据就绪才可见"）：60ms 步进探测当前页是否出图，出图立即撤除遮罩。
    //   ★ P1-2：1.5s 仍不就绪不直接暴露白屏——主动踢一次当前页重载，遮罩延到 3s 硬上限；仍不就绪才撤，留 W 日志
    LaunchedEffect(switchMaskVisible) {
        if (!switchMaskVisible) return@LaunchedEffect
        var waited = 0L
        var kicked = false
        while (waited < 3000L) {
            delay(60); waited += 60
            val ready = when {
                useAnimPager -> true
                isVerticalMode -> activeWebtoonRef?.isCurrentPageReady() == true
                else -> activePagerRef?.isCurrentPageReady() == true
            }
            if (ready) break
            if (waited >= 1500L && !kicked) {
                kicked = true
                try {
                    if (!useAnimPager && isVerticalMode) {
                        activeWebtoonRef?.let { it.goToPage(it.currentPageForKick) }
                    } else if (!isVerticalMode) {
                        activePagerRef?.let { it.goToPage(it.currentPageForKick) }
                    }
                } catch (_: Throwable) {}
            }
        }
        if (!useAnimPager) {
            val stillNotReady = (if (isVerticalMode) activeWebtoonRef?.isCurrentPageReady() else activePagerRef?.isCurrentPageReady()) == false
            if (stillNotReady) android.util.Log.w("Reader", "switch mask 3s 超时未就绪（页=${if (isVerticalMode) activeWebtoonRef?.currentPageForKick else activePagerRef?.currentPageForKick}），撤罩（重载已触发，页面会在后台自愈）")
        }
        hideSwitchMask()
    }
    LaunchedEffect(viewing) {
        if (!viewing && switchMaskVisible) hideSwitchMask()
    }

    // Live-sync flip animation and side padding to pager/webtoon views
    LaunchedEffect(configVersion) {
        activePagerRef?.flipAnimationEnabled = config.isFlipAnimation
        activePagerRef?.imageMarginH = config.imageMarginH
        activePagerRef?.setTrimWhiteBorder(config.isTrimWhiteBorder, config.trimThreshold, config.trimWhiteRatio)
        activePagerRef?.pageBgColor = bgColor.toArgb()
        activeWebtoonRef?.imageMarginH = config.imageMarginH
        activeWebtoonRef?.gapEnabled = (mode == Mode.WEBTOON_GAP)
        activeWebtoonRef?.trimWhiteBorder = config.isTrimWhiteBorder
        activeWebtoonRef?.trimThreshold = config.trimThreshold
        activeWebtoonRef?.trimWhiteRatio = config.trimWhiteRatio
        activeWebtoonRef?.pageBgColor = bgColor.toArgb()
    }

    var webtoonViewRef by remember { mutableStateOf<View?>(null) }
    var pagerViewRef by remember { mutableStateOf<View?>(null) }
    var autoScrollPaused by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (ui || !viewing) {
                val pageSubtitle = if (viewing && displayPages.isNotEmpty() && cur in displayPages.indices) {
                    val p = displayPages[cur]
                    val name = p.name.ifEmpty { p.ext.substringAfterLast("/").substringBeforeLast(".") }
                    "${cur + 1}/${displayPages.size}  $name"
                } else ""
                ReaderTopBar(
                    title = book?.title ?: "",
                    subtitle = pageSubtitle,
                    viewing = viewing,
                    bgColor = bgColor,
                    isFavorite = book?.isFavorite ?: false,
                    onBack = ::goBack,
                    onFavoriteClick = {
                        book?.let { b ->
                            val newFav = !(b.isFavorite)
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.updateBook(b.copy(isFavorite = newFav)) }
                                book = b.copy(isFavorite = newFav)
                            }
                        }
                    }
                )
            }
        },
        containerColor = bgColor,
        bottomBar = {
            if (viewing && ui) {
                Column(Modifier.fillMaxWidth()) {
                    ReaderBottomBar(actions = buildList {
                        add(BottomBarAction(Icons.Default.Menu, "目录") { showToc = !showToc })
                        add(BottomBarAction(Icons.Default.AutoFixHigh, "超分") { showSrPanel = true })
                        // 超分作用域已开启 → 右侧出现"对比"按钮；开/关两种状态实时切换
                        if (com.mangareader.sr.SrUiState.scope.intValue > 0) {
                            add(BottomBarAction(Icons.Default.CompareArrows, if (com.mangareader.sr.SrUiState.compareActive.value) "退出对比" else "对比") {
                                com.mangareader.sr.SrUiState.compareActive.value = !com.mangareader.sr.SrUiState.compareActive.value
                            })
                        }
                        add(BottomBarAction(Icons.Default.Settings, "设置") { showSettings = true })
                    }, bgColor = bgColor)
                    // 进度条放在底部（横竖都一样）
                    ChapterNavigator(
                        isVertical = false,
                        currentPage = cur,
                        totalPages = displayPages.size,
                        onPageChange = { newCur ->
                            // ★ P0-B6：Slider 直通不设限（用户要求：任何时候都不能限制进度条操作）。
                            //   误触防护由两处承担：① ChapterNavigator 内部改为松手才提交（拖动过程不触发本回调）
                            //   ② settleWindow 承诺"用户滚过绝不拉回"
                            android.util.Log.d("WebtoonRV", "ChapterNav.onPageChange newCur=$newCur cur=$cur")
                            cur = newCur
                        },
                        onSliderFinished = { save(); restartAutoHide() },
                        onPreviousChapter = ::switchToPrevious,
                        onNextChapter = ::switchToNext,
                        isShowPageNum = config.isShowPageNum,
                        isRtl = isRtl,
                        bgColor = bgColor
                    )
                }
            }
        }
    ) { pad ->
        val shouldApplyPadding = !config.isImmersiveMode || ui || !viewing
        Box(Modifier.fillMaxSize().background(bgColor)) {
            // 内容区域（带 padding）
            Box(Modifier.fillMaxSize().then(if (shouldApplyPadding) Modifier.padding(pad) else Modifier)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF7C8CF8)) }
                    errorMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(errorMsg!!) }
                    viewing && displayPages.isNotEmpty() -> {
                    val safeCur = cur.coerceIn(0, (displayPages.size - 1).coerceAtLeast(0))
                    if (safeCur != cur) cur = safeCur
                    // ★ §34.3：isVertical 仅条漫（VERT/WEBTOON_GAP）走 webtoonView；
                    //   VERT_PAGER 不属于 isVertical，走 pagerView（传 verticalPager=true）。
                    val vertical = mode == Mode.VERT || mode == Mode.WEBTOON_GAP
                    // ★ §34.5 verticalPager 扩展：useAnimPager=true 时条漫 mode 也走竖向翻页（AnimPager 内 ViewPager2 VERTICAL）
                    //   非 useAnimPager 时维持 §34.3 语义（仅 VERT_PAGER true）
                    val verticalPagerEffective = isVerticalPagerMode || (useAnimPager && (mode == Mode.VERT || mode == Mode.WEBTOON_GAP))
                    Box(Modifier.fillMaxSize()) {
                        // ★ v1.13 C-3（mihon 同构）：模式五元组作 key——变化时 NativeReaderScreen 连同
                        //   三个 View 实例整体销毁重建。此前 C-1 是"同实例软重置"，实测证明内部复杂状态机
                        //   （settleWindow/动图map/inflight/transformer定时器）必有残留 → 切换后翻页异常。
                        //   页码已提升在 Reader 层 cur（startPage 传参回填），重建零成本恢复位置；
                        //   旧实例由原 DisposableEffect.onDispose 正常 release（bitmap 回收不泄漏）
                        // 超分：阅读会话挂接（进书/切章/换源时刷新会话，退出阅读销毁）
                        val srTreeStr = navStack.lastOrNull() ?: book?.filePath ?: ""
                        LaunchedEffect(srTreeStr, book?.id, viewing) {
                            if (viewing && srTreeStr.isNotEmpty()) {
                                com.mangareader.sr.SrPipeline.beginSession(ctx, srTreeStr, book?.id ?: -1L, book?.title ?: "", displayPages)
                            } else {
                                com.mangareader.sr.SrPipeline.endSession()
                            }
                        }
                        // 超分完成事件 → 仅对比模式开着才驱动刷新（平时零重组开销，防卡顿）
                        LaunchedEffect(Unit) {
                            try {
                                if (com.mangareader.sr.SrCore.initialized) {
                                    com.mangareader.sr.SrCore.readyEvents.collect {
                                        if (com.mangareader.sr.SrUiState.compareActive.value) srReadyTick++
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                        key(navStack.lastOrNull(), vertical, isRtl, verticalPagerEffective, useAnimPager) {
                        com.mangareader.native.NativeReaderScreen(
                            engine = engine,
                            treePath = navStack.lastOrNull() ?: book?.filePath ?: "",
                            pages = displayPages,
                            startPage = safeCur,
                            isVertical = vertical,
                            isRtl = isRtl,
                            grayscale = config.isGrayscale,
                            invertColors = config.isInvertColors,
                            centerGapType = config.centerGapType,
                            landscapeZoom = config.landscapeZoom,
                            trimWhiteBorder = config.isTrimWhiteBorder,
                            trimThreshold = config.trimThreshold,
                            trimWhiteRatio = config.trimWhiteRatio,
                            panNavigation = config.panNavigation,
                            pageLayout = when (config.pageLayout) {
                                com.mangareader.data.ReaderConfig.PageLayout.SINGLE -> 0
                                com.mangareader.data.ReaderConfig.PageLayout.DOUBLE -> 1
                                com.mangareader.data.ReaderConfig.PageLayout.DOUBLE_SHIFTED -> 2
                            },
                            pageBgColor = bgColor.toArgb(),
                            doublePageReverse = config.doublePageReverse,
                            doublePageSplit = config.doublePageSplit,
                            verticalPager = verticalPagerEffective,
                            useAnimPager = useAnimPager,
                            modifier = Modifier.fillMaxSize(),
                            onContainerReady = { readerContainerRef = it },
                            onPageChanged = { newCur ->
                                // ★ Bug C 修复：onPageChanged 可能在 setup 滚到位前被触发（webtoon RV layout 过程 / pager setCurrentItem 同步），
                                //   首个 visible item != sp，会把 currentPage 重写为错的页码 → 冲掉 lastPage 并 save 持久化。
                                //   守卫通过 mode 判定后用 WebtoonReaderView.isSetupScrollPending 闸：
                                //   - 条漫类（VERT/WEBTOON_GAP）：setup 时 isSetupScrollPending=true → release/timeout 才 false；pending 期间 SKIP。
                                //   - 翻页类（FLIP/RTL/VERT_PAGER）：wv=null → 不 SKIP（onPageSelected 内 `if (realPos != currentPage)` 已守卫初始 sp 同页 fire；APPLY 是新 sp 用户翻页）
                                //   ★ Bug C-fix2：条漫模式 onViewReady 之前 wv=null → 等同 race 情况：条漫模式没 wv 也 SKIP
                                val wv = activeWebtoonRef
                                val isVert = isVerticalMode  // §34.3：VERT / WEBTOON_GAP（不含 VERT_PAGER——它走 PagerReaderView）
                                // ★ v1.11 P0-B 双保险：settleWindow 防污染窗内同样 SKIP（Webtoon 层已吞 onScrolled，
                                //   此处兜底其他触发路径），窗口结束由 WebtoonRV 统一 updatePage 校准放行
                                val skip = isVert && (wv == null || wv.isSetupScrollPending || wv.isSettleWindowActive)
                                if (skip) {
                                    android.util.Log.d("WebtoonRV", "Reader.onPageChanged SKIP newCur=$newCur pend=${wv?.isSetupScrollPending} win=${wv?.isSettleWindowActive}")
                                } else {
                                    cur = newCur; save()
                                }
                            },
                            onToggleUi = ::toggleUi,
                            onViewReady = { w, p ->
                                webtoonViewRef = w; pagerViewRef = p
                                activePagerRef = p as? com.mangareader.native.PagerReaderView
                                activeWebtoonRef = w as? com.mangareader.native.WebtoonReaderView
                                // ★ §34.5：onAnimDetected 旧钩子废弃——动图检测由 enterReader 末尾批量 peek 取代。
                                //   本 hook 只留作 dump 日志（peek 流程的兜底信号），不再触发任何对话框。
                                (w as? com.mangareader.native.WebtoonReaderView)?.let { wv ->
                                    wv.onAnimDetected = { count ->
                                    }
                                }
                                // ★ §24 二代：PagerReaderView 接动图播放，hook 同 wait——但翻页模式下不需"切翻页"对话框（已在翻页中）
                                (p as? com.mangareader.native.PagerReaderView)?.let { pv ->
                                    pv.onAnimDetected = { count ->
                                    }
                                }
                                (p as? com.mangareader.native.PagerReaderView)?.let {
                                    it.flipAnimationEnabled = config.isFlipAnimation
                                    it.imageMarginH = config.imageMarginH
                                    it.onChapterEnd = { isLast ->
                                        if (config.chapterTransition && tocItems.size > 1) {
                                            chapterTransitionIsLast = isLast
                                            showChapterTransition = true
                                        }
                                    }
                                    it.onPageLongPress = { pageIdx ->
                                        if (pageIdx in displayPages.indices) {
                                            scope.launch {
                                                try {
                                                    val bmp = withContext(Dispatchers.IO) { engine.loadPage(displayPages[pageIdx], book?.filePath ?: "") }
                                                    if (bmp != null) {
                                                        val dir = java.io.File(ctx.getExternalFilesDir(null), "saved_pages"); dir.mkdirs()
                                                        val file = java.io.File(dir, "page_${System.currentTimeMillis()}.png")
                                                        java.io.FileOutputStream(file).use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
                                                        android.widget.Toast.makeText(ctx, "已保存到 ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) { android.widget.Toast.makeText(ctx, "保存失败", android.widget.Toast.LENGTH_SHORT).show() }
                                            }
                                        }
                                    }
                                }
                                (w as? com.mangareader.native.WebtoonReaderView)?.let {
                                    it.imageMarginH = config.imageMarginH
                                }
                            }
                        )
                        } // ★ v1.13 C-3 key(navStack, vertical, isRtl, verticalPager, useAnimPager) end
                        // 超分前后对比叠层（底栏"对比"按钮开启后显示，拖动分割线看原图/超分图）
                        if (viewing) {
                            SrCompareOverlay(
                                pageIndex = cur,
                                tick = srReadyTick,
                                onClose = { com.mangareader.sr.SrUiState.compareActive.value = false },
                                bgColor = bgColor.toArgb(),
                            )
                        }
                        // ★ v1.13 C-2 无感切换遮罩：截取的旧画面盖在内容区顶，500ms 兜底撤除
                        if (switchMaskVisible && switchMaskBitmap != null) {
                            Image(
                                bitmap = switchMaskBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // ★ 1.001 沉浸式锁定按钮
                        //   命中区：ui 显示 或 锁定态才挂（开锁+UI 隐时完全无 pointerInput → 不挡 native 翻页）
                        //   视觉：仅 ui 显示 或 锁图标弹出才渲染（锁定沉浸态锁按钮也隐藏，画面完全干净）
                        if (ui || isLocked) {
                            val lum = bgColor.red * 0.299f + bgColor.green * 0.587f + bgColor.blue * 0.114f
                            val iconColor = if (lum > 0.5f) Color.Black else Color.White
                            // 是否绘制视觉：UI 显示（开锁态，跟顶/底栏一起显）或 锁图标弹出（锁定+点过锁位置唤出）
                            // 超分运行状态指示灯（右上角）：当前页的在算什么一看就知道
                            if (com.mangareader.sr.SrUiState.scope.intValue > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 80.dp, end = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x88000000))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val computing = com.mangareader.sr.SrPipeline.computing(cur)
                                        val done = !computing && com.mangareader.sr.SrPipeline.peekSr(cur) != null
                                        when {
                                            computing -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(12.dp),
                                                    color = Color.White,
                                                    strokeWidth = 1.5.dp,
                                                )
                                                Text("超分计算中", color = Color.White, fontSize = 11.sp)
                                            }
                                            done -> {
                                                Icon(Icons.Default.Check, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(12.dp))
                                                Text("超分完成", color = Color.White, fontSize = 11.sp)
                                            }
                                            else -> Text("未启动", color = Color(0xFFFFA726), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }

                            val showVisual = ui || showLockIcon
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(top = 80.dp, start = 4.dp)
                                    // 命中区始终 40dp 圆（沉浸态无视觉时点击落此区域也能唤锁图标）
                                    .size(40.dp)
                                    .pointerInput(isLocked, showLockIcon) {
                                        detectTapGestures(onTap = {
                                            if (isLocked && showLockIcon) unlock() else toggleLock()
                                        })
                                    }
                            ) {
                                if (showVisual) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(bgColor.copy(alpha = 0.6f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            // UI 显示而未锁定 → LockOpen（点击可锁）；锁图标弹出（锁定态下点过锁位置）→ Lock（点击可解）
                                            imageVector = if (isLocked && showLockIcon) Icons.Default.Lock else Icons.Default.LockOpen,
                                            contentDescription = if (isLocked) "解锁" else "锁定",
                                            tint = iconColor.copy(alpha = 0.85f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                items.isNotEmpty() -> ItemGrid(items, engine, bgColor, progressForToc, { openItem(it) }, Modifier.fillMaxSize().background(bgColor))
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有内容") }
                }
                // 翻页闪烁效果
                com.mangareader.ui.FlipFlashOverlay(
                    currentPage = cur,
                    enabled = config.isFlipFlashEnabled,
                    durationMs = config.flipFlashDuration,
                    intervalPages = config.flipFlashInterval,
                    modifier = Modifier.fillMaxSize()
                )
                // 导航区域提示 — 仅水平模式（单页从左到右/从右到左）显示
                if (showNavOverlay && viewing && !isVerticalMode) {
                    com.mangareader.ui.ReaderNavigationOverlay(
                        visible = true,
                        isVertical = false,
                        onDismiss = { showNavOverlay = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showErrorDialog) {
        AlertDialog(onDismissRequest = { showErrorDialog = false }, title = { Text("源文件丢失") },
            text = { Text("该漫画的源文件已不存在或无法访问。\n\n是否从书架中移除？") },
            confirmButton = { TextButton(onClick = { scope.launch {
                // ★ v1.12 补丁：book==null（历史页点了"已删除书籍"的悬空记录进来的场景）
                //   也要清掉该 bookId 的残留进度与历史，否则点移除毫无效果
                val b0 = book
                if (b0 != null) {
                    dao.deleteBook(b0)
                } else {
                    try { withContext(Dispatchers.IO) {
                        dao.clearProgress(bookId)
                        dao.deleteHistoryForBookIds(listOf(bookId))
                    } } catch (_: Exception) {}
                }
                showErrorDialog = false; try { back() } catch (_: Exception) {}
            } }) { Text("移除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showErrorDialog = false }) { Text("保留") } })
    }

    // ★ ④ 修复：单卷缺失友好提示。区别 showErrorDialog（整本 / CASCADE 删所有 progress）：本弹窗只删本卷 progress。
    //   - 「移除本卷进度」→ 仅清 missingFilePath 这一卷 progress，保留其它卷完整历史。
    //   - 「返回」→ 直接 viewing=false 回目录/书架，不动 progress（SAF 临时失败后用户重进即可恢复）。
    if (showFileMissingDialog) {
        AlertDialog(onDismissRequest = {
            showFileMissingDialog = false
            // 关闭 = 等同返回：不删 progress，留在原处
            viewing = false
        }, title = { Text("文件暂时找不到") },
            text = { Text("这一卷的源文件暂时无法访问，可能已被移动、删除，或权限发生变化。\n\n本卷的其它进度仍会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showFileMissingDialog = false
                    val p = missingFilePath
                    scope.launch {
                        try { book?.let { dao.deleteProgress(it.id, p) } } catch (_: Exception) {}
                    }
                    // 删除后回退：与 goBack 同语义地处理目录栈
                    viewing = false
                    try { back() } catch (_: Exception) {}
                }) { Text("移除本卷进度", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFileMissingDialog = false
                    viewing = false
                }) { Text("返回") }
            })
    }

    if (showComplete) {
        AlertDialog(onDismissRequest = { showComplete = false }, title = { Text("阅读完成") },
            text = { Text("已读完「${book?.title ?: ""}」的所有页面") },
            confirmButton = {
                TextButton(onClick = { markAsReadNow(); showComplete = false }) { Text("标记已读") }
            },
            dismissButton = { TextButton(onClick = { showComplete = false }) { Text("继续浏览") } })
    }

    // ★ §34.5 动图卷专属视图入口对话框 —— 进卷批量 peek 判定动图卷后弹。
    //   旧的"切翻页"(choice=1) / "保持条漫"(choice=2) 语义：
    //     - 仅条漫下使用（pager 自身有 workaround 动图播放）
    //     - 用户点"切翻页" → mode 强切至 FLIP/RTL → PagerReaderView 走 workaround 路径播动图
    //   新版 §34.5：弹"进入动图视图"(choice=3) / "保持普通视图"(choice=4)。
    //     - 适用所有 mode（条漫+翻页）— 因为普通翻页 PagerReaderView 的动图 workaround 体验差（闪白等）
    //     - 选择 3 → useAnimPager=true → NativeReaderScreen 路由到 AnimPagerReaderView
    //     - 选择 4 → useAnimPager=false → 保持当前 mode 下的普通视图
    //   旧 choice=1/2 的持久化值含义不变——老数据继续生效（无迁移动作）：
    //     - choice=1 原切翻页，保留旧 mode 行为（mode 已被切到翻页），不会重弹
    //     - choice=2 原保持条漫，不会重弹
    //     - choice=3/4 走新流程
    if (showAnimSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showAnimSwitchDialog = false },
            title = { Text("检测到动图") },
            text = { Text("本卷含动图。\n\n普通模式下动图无法正常播放（会显示首帧、闪白或卡顿）。\n是否进入动图专属视图？") },
            confirmButton = {
                // ★ "进入动图视图" → choice=3 → useAnimPager=true
                //   mode 维持用户当前选择 —— AnimPagerReaderView 内部据 verticalPager 参数决定竖向/横向翻页
                //   条漫模式 (VERT/WEBTOON_GAP) 下进动图视图时按竖向翻页 VERT_PAGER 风格展示
                TextButton(onClick = {
                    val chPath = navStack.lastOrNull() ?: book?.filePath ?: ""
                    useAnimPager = true
                    animDialogChoiceForChapter = 3
                    scope.launch {
                        try {
                            book?.let { b ->
                                // ★ v1.11：原 upsertProgress(...cur,false) 会把当前页码/未完成态覆盖进已有进度行
                                //   （污染源之一）。改用 INSERT OR IGNORE 占位——仅在该卷从无进度行时补建，
                                //   已有行原样保留，随后 setAnimDialogChoice 纯 UPDATE 只动 choice 列。
                                dao.preinsertProgressIfAbsent(b.id, chPath)
                                dao.setAnimDialogChoice(b.id, chPath, 3)
                            }
                        } catch (_: Exception) {}
                    }
                    showAnimSwitchDialog = false
                    }) { Text("进入动图视图") }
            },
            dismissButton = {
                // ★ v1.13 T10（用户裁定）："保持普通"不再持久化 choice=4——每次打开该卷仍会询问
                TextButton(onClick = {
                    animDialogChoiceForChapter = 4
                    showAnimSwitchDialog = false
                    }) { Text("保持普通") }
            }
        )
    }

    if (showSrPanel) {
        com.mangareader.ui.SrPanel(
            bookId = book?.id ?: -1L,
            bookTitle = book?.title ?: "",
            onOpenQueue = {
                showSrPanel = false
                showSrQueue = true
            },
            onDismiss = { showSrPanel = false },
        )
    }

    if (showSrQueue) {
        com.mangareader.ui.SrQueueScreen(onBack = { showSrQueue = false })
    }

    if (showSettings) {
        com.mangareader.ui.ReaderSettingsSheet(
            config = config,
            mode = mode,
            bookId = bookId,
            useAnimPager = useAnimPager,
            onConfigChange = { newConfig ->
                config = newConfig
                newConfig.save(ctx, bookId)
                configVersion++
            },
            onModeChange = { newMode ->
                // ★ v1.13 C-2：mode state 提交【前】同步截图——此刻旧 delegate 还在屏上，
                //   拿到的是切换前的真实画面（Compose 重组在下一帧，同步 draw 零竞态）
                showSwitchMask()
                mode = newMode
                prefs.edit().putInt("read_mode_$bookId", newMode.ordinal).apply()
                configVersion++
                // 切换模式时显示导航区域提示，3秒后自动消失
                showNavOverlay = true
                scope.launch { delay(3000); showNavOverlay = false }
            },
            onOrientationChange = { orientation ->
                saveOrientation(orientation)
                config = config.copy(orientation = orientation)
                config.save(ctx, bookId)
                configVersion++
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showToc) {
        val displayItems = if (viewing && tocItems.isNotEmpty()) tocItems else items
        ReaderTocSheet(
            displayItems = displayItems,
            currentNavStack = navStack,
            viewing = viewing,
            progress = progressForToc,
            bookId = book?.id ?: 0L,
            onDismiss = { showToc = false },
            onChapterNavigate = { chapterPath ->
                // 保留书根路径 + 新章节路径，不丢弃整个 navStack
                val bookRoot = navStack.firstOrNull { it.startsWith("z:") } ?: chapterPath
                navStack = listOf(bookRoot, chapterPath)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        items = emptyList()
                        val chItems = try { engine.children(chapterPath) } catch (_: Exception) { emptyList() }
                        if (chItems.isEmpty()) {
                            // ★ §23 改走 enterReader 恢复 lastPage
                            enterReader(chapterPath)
                        } else {
                            items = chItems
                        }
                    }
                }
            },
            onItemOpen = { openItem(it) }
        )
    }

    // Chapter transition overlay
    ChapterTransitionOverlay(
        visible = showChapterTransition,
        isLast = chapterTransitionIsLast,
        onNextChapter = {
            showChapterTransition = false
            switchToNext()
        },
        onPreviousChapter = {
            showChapterTransition = false
            switchToPrevious()
        },
        modifier = Modifier.fillMaxSize()
    )

    // Switch confirmation dialog
    if (showSwitchConfirm) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = false },
            title = { Text("切换确认") },
            text = { Text("确认切换到：$switchTargetName ?") },
            confirmButton = {
                TextButton(onClick = { confirmSwitch() }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ItemGrid(items: List<ArchiveEngine.Item>, engine: ArchiveEngine, bgColor: Color, progress: List<ReadingProgress>, tap: (ArchiveEngine.Item) -> Unit, mod: Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var sortAsc by remember { mutableStateOf(true) }
    var cols by remember { mutableIntStateOf(prefs.getInt("item_grid_cols", 3)) }
    LaunchedEffect(Unit) {
        val saved = prefs.getInt("item_grid_cols", 3)
        if (saved != cols) cols = saved
    }
    val sorted = remember(items, sortAsc) {
        val base = items.sortedWith(compareByDescending<ArchiveEngine.Item> { it.dir }.thenComparing({ it.name }, NaturalComparator))
        if (sortAsc) base else base.reversed()
    }
    Column(mod) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${sorted.size} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { sortAsc = !sortAsc }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (sortAsc) "正序" else "倒序", fontSize = 12.sp)
                }
                TextButton(onClick = { cols = when(cols) { 2 -> 3; 3 -> 4; else -> 2 }; prefs.edit().putInt("item_grid_cols", cols).apply() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.GridView, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${cols}列", fontSize = 12.sp)
                }
            }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(cols), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(sorted, key = { i, e -> "${e.auth}|${e.doc}_$i" }) { _, e -> ItemCard(e, engine, bgColor, progress, { tap(e) }) }
        }
    }
}

@Composable
private fun ItemCard(item: ArchiveEngine.Item, engine: ArchiveEngine, bgColor: Color, progress: List<ReadingProgress>, tap: () -> Unit) {
    var cover by remember(item.doc, item.subPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.doc, item.subPath) {
        cover = null
        withContext(Dispatchers.IO) { try { cover = engine.loadCoverForItem(item) } catch (_: Exception) { cover = null } }
    }
    // ★ §37(17) 子项网格徽章：复用 ReaderTocSheet 的状态判定——三态全画（已读完绿/阅读中橙/未读红），
    //   替代原"文件夹/压缩包"类型标签。未读也画以让用户一眼分辨未点开的子项。
    val status = remember(progress, item.name) { com.mangareader.ui.statusForItem(item, progress) }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = tap).shadow(4.dp, RoundedCornerShape(12.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(bgColor.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
            if (cover != null) {
                val bitmap = cover!!
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(bitmap.asImageBitmap())
                }
            }
            Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
            // ★ §37(17) 状态徽章——三态都画（已读完绿、阅读中橙、未读红），替代原"文件夹/压缩包"类型标签
            Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = status.color.copy(alpha = 0.9f)) {
                Text(status.label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
            }
        }
        Box(Modifier.fillMaxWidth().background(bgColor.copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
            val luminance = bgColor.red * 0.299f + bgColor.green * 0.587f + bgColor.blue * 0.114f
            val textColor = if (luminance > 0.5f) Color(0xFF333333) else Color(0xFFCCCCCC)
            Text(item.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = textColor)
        }
    }
}
