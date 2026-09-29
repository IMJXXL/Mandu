package com.mangareader.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.FrameLayout


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.cancel
import okio.Path.Companion.toPath
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
fun Reader(bookId: Long, jumpPath: String? = null, startPage: Int = -1, back: () -> Unit, onOpenBook: ((Long, String) -> Unit)? = null) {
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
            if (key?.startsWith("book_${bookId}_") == true || key in setOf("scrollMode", "readBackground", "autoFlipInterval", "tileModeEnabled", "downsampleTier", "isEinkMode", "isTrimWhiteBorder", "trimThreshold", "trimWhiteRatio", "imageMarginH", "isFlipAnimation", "isPageCurlAnimation", "isCurlContentAreaOnly", "landscapeZoom", "fitScreen", "panNavigation", "doublePageSplit", "doublePageReverse", "pageLayout", "useSSIV", "forceFullDecode", "tallSplitEnabled", "isFlipFlashEnabled", "flipFlashDuration", "flipFlashInterval", "flipFlashColor", "einkDecodeSize", "einkUiHideDelay", "pdfQuality", "isGrayscale", "isInvertColors", "centerGapType", "doublePageShift", "seamlessNextVolume", "isEinkDither", "einkDitherAlgorithm", "einkDitherLevels", "einkEndpointPassthrough")) {
                configVersion++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    DisposableEffect(Unit) {
        com.mangareader.smbbridge.SmbCoverLoader.cancelAll()
        com.mangareader.webdavbridge.WebDavCoverLoader.cancelAll()
        onDispose { }
    }

    var book by remember { mutableStateOf<Book?>(null) }
    var pageList by remember { mutableStateOf(listOf<ArchiveEngine.PageMeta>()) }
    var cur by remember { mutableIntStateOf(0) }
    var expandedPageCount by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(config.isEinkMode) {
        com.hippo.ehviewer.Settings.isEinkMode = config.isEinkMode
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
    var showRename by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showSrQueue by remember { mutableStateOf(false) }
    var srReadyTick by remember { mutableStateOf(0) }
    // ★ 子卷自定义封面（2026-09-26）：目录面板 dir 子项（A→a1→aa1 的中间层文件夹）长按换封面。
    //   封面文件 covers/custom_<md5(chPath)>_*.jpg（无 DB 字段，文件存在即生效），rev 变更触发重查。
    var subCoverRev by remember { mutableIntStateOf(0) }
    var subVolCoverMap by remember { mutableStateOf<Map<String, Pair<String, Long>>>(emptyMap()) }
    var subVolMenu by remember { mutableStateOf<Pair<ArchiveEngine.Item, String>?>(null) }
    var subVolEdit by remember { mutableStateOf<Pair<ArchiveEngine.Item, String>?>(null) }
    // 满屏子项网格（ItemGrid）与目录面板（ReaderTocSheet）共用的 dir 子项长按入口
    val onSubItemLongPress: (ArchiveEngine.Item) -> Unit = { itm ->
        if (book?.source == "local") {
            tocItemChPath(navStack, itm)?.let { cp -> subVolMenu = itm to cp }
        }
    }
    // ★ v1.13 C-2 无感切换遮罩：提交 mode 前同步 view.draw 截当前画面，盖住
    //   "旧 delegate release → 新 delegate 全量重建+解码"的占位空窗；500ms 兜底撤除。
    //   动图弹窗路径（useAnimPager 直设）不经 onModeChange，一期无遮罩降级（C-1 已保不白屏）
    // ★ 书签：章节身份= navStack.last()（与 reading_progress.path 同源）；page = cur（五模式统一页索引）
    var bookmarks by remember { mutableStateOf(listOf<com.mangareader.data.Bookmark>()) }
    var bookmarkTick by remember { mutableIntStateOf(0) }
    var pageBookmarked by remember { mutableStateOf(false) }
    var pendingPageSaveIdx by remember { mutableIntStateOf(-1) }


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

    // ★ N1 防抖：无缝续读 3 秒内不重复触发（模式切换/重复章末回调的并发窗口）
    var lastSeamlessAt by remember { mutableStateOf(0L) }
    var switchTargetName by remember { mutableStateOf("") }
    var switchTargetPath by remember { mutableStateOf("") }
    var isSwitching by remember { mutableStateOf(false) }

    LaunchedEffect(viewing) {
        if (viewing) isSwitching = false
    }

    val displayPages = remember(pageList) { pageList }

    var saveJob by remember { mutableStateOf<Job?>(null) }
    var activePagerRef by remember { mutableStateOf<com.mangareader.native.PagerReaderView?>(null) }
    var activeWebtoonRef by remember { mutableStateOf<com.mangareader.native.WebtoonReaderView?>(null) }

    // ★ SMB/WebDAV 远程阅读会话（smb_embed_lv119 任务4.3/14.4）：非 null 时阅读器页面走远程供给
    var smbSession by remember { mutableStateOf<com.mangareader.native.RemotePageSupply?>(null) }
    // ★ 不支持格式占位（任务6.2/16.2）：filePath to kind("smb"/"webdav")
    var smbUnsupportedInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    var smbDownloadBusy by remember { mutableStateOf(false) }

    // ★ 重新下载弹窗（本地文件丢失，originalPath 存在时可恢复为远程书）
    var showRedownloadDialog by remember { mutableStateOf(false) }
    var redownloadBook by remember { mutableStateOf<com.mangareader.data.Book?>(null) }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordDeferred by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<String>?>(null) }

    val archivePasswdProvider: com.hippo.ehviewer.gallery.PasswdProvider = passwdProvider@ { invalidator ->
        while (true) {
            val deferred = kotlinx.coroutines.CompletableDeferred<String>()
            passwordDeferred = deferred
            showPasswordDialog = true
            passwordError = false
            passwordInput = ""
            val pwd = deferred.await()
            if (pwd.isEmpty()) throw IllegalStateException("用户取消密码输入")
            if (invalidator(pwd)) {
                showPasswordDialog = false
                return@passwdProvider pwd
            }
            passwordError = true
            passwordInput = ""
        }
        error("unreachable")
    }

    // ★ 远程供给挂接：视图随模式切换整体重建（key 五元组），这里响应式重挂到当前活跃视图
    LaunchedEffect(smbSession, activePagerRef, activeWebtoonRef) {
        val s = smbSession
        activePagerRef?.remotePageSupply = s
        activeWebtoonRef?.remotePageSupply = s
    }
    DisposableEffect(smbSession) {
        val s = smbSession
        onDispose {
            // 捕获 effect 创建时的会话：smbSession 已换新时这里关闭的是旧会话
            if (s != null) {
                activePagerRef?.remotePageSupply = null
                activeWebtoonRef?.remotePageSupply = null
                scope.launch(Dispatchers.IO) { runCatching { s.close() } }
            }
        }
    }


    fun hideUi() { hideSystemBars(act, config.isImmersiveMode, config.isKeepScreenOn) }
    fun showUi() { showSystemBars(act, config.isKeepScreenOn) }
    fun restoreUi() { restoreSystemBarsSync(act) }

    LaunchedEffect(viewing) {
        if (viewing) { delay(100); hideUi() } else { restoreUi() }
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
                val effectiveSize = if (expandedPageCount > 0) expandedPageCount else pageListSnap.size
                val done = effectiveSize > 1 && curSnap >= effectiveSize - 1
                android.util.Log.d("ReaderProgress", "onDispose-save bookId=${b.id} chPath=$chPathSnap cur=$curSnap size=$effectiveSize done=$done")
                val sx = scrollX; val sy = scrollY; val zs = zoomLevel
                app.appScope.launch {
                    try {
                        val history = ReadingHistory(
                            bookId = b.id, pagesRead = curSnap + 1,
                            scrollX = sx, scrollY = sy, zoomLevel = zs
                        )
                        withContext(Dispatchers.IO) {
                            dao.saveReaderProgressBatch(b.id, curSnap + 1, now, chPathSnap, curSnap, done, history, effectiveSize)
                        }
                        syncBookStatusFromProgress(b, now, effectiveSize)
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
        val effectiveSize = if (expandedPageCount > 0) expandedPageCount else pageListSnap.size
        val done = effectiveSize > 1 && curSnap >= effectiveSize - 1
        android.util.Log.d("ReaderProgress", "save bookId=${b.id} chPath=$chPathSnap cur=$curSnap size=$effectiveSize done=$done")

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
        val pageCount = if (expandedPageCount > 0) expandedPageCount else displayPages.size
        val n = (cur + d).coerceIn(0, pageCount - 1)
        if (n == cur) return
        if (n != cur) { cur = n; save() }
        if (cur >= pageCount - 1 && viewing && pageCount > 1 && !showComplete && !hasShownCompleteThisSession) {
            showComplete = true; hasShownCompleteThisSession = true
        }
    }

    /** 建立远程供给会话：页面元表 + 进度恢复（与本地 completed→跳第1页 语义一致） */
    fun attachRemoteSession(
        supply: com.mangareader.native.RemotePageSupply,
        saved: com.mangareader.data.ReadingProgress?,
        targetPage: Int,
    ): List<com.mangareader.viewer.ArchiveEngine.PageMeta> {
        smbSession = supply
        val pages = (0 until supply.pageCount.coerceAtLeast(0)).map {
            com.mangareader.viewer.ArchiveEngine.PageMeta(it, "", "", name = "")
        }
        pageList = pages
        supply.onPageCountGrow.add { newCount ->
            if (newCount > pageList.size) {
                pageList = (0 until newCount).map {
                    com.mangareader.viewer.ArchiveEngine.PageMeta(it, "", "", name = "")
                }
            }
        }
        val lastPage = if (saved?.completed == true) 0 else (saved?.lastPage ?: 0)
        cur = (if (targetPage >= 0) targetPage else lastPage).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        viewing = true
        loading = false
        return pages
    }
    /**
     * ★ SMB/WebDAV 远程阅读入口（smb_embed_lv119 任务4.1-4.3 / 14.1-14.4）。
     * Router 返回 Supported → 建供给会话 + 页面元表 + 恢复进度；
     * Unsupported → 占位 UI（下载按钮）；Blocked/Failed → toast。
     */
    suspend fun enterRemoteReader(
        b: com.mangareader.data.Book,
        path: String,
        targetPage: Int,
        isSmb: Boolean,
    ): List<com.mangareader.viewer.ArchiveEngine.PageMeta> {
        loading = true
        smbUnsupportedInfo = null
        try {
            val saved = withContext(Dispatchers.IO) {
                runCatching { dao.getProgress(b.id, path) }.getOrNull()
            }
            val resumePage = if (targetPage >= 0) targetPage else (saved?.lastPage ?: 0)
            if (isSmb) {
                when (val r = com.mangareader.smbbridge.SmbBookRouter.openSmbBook(path, resumePage, archivePasswdProvider)) {
                    is com.mangareader.smbbridge.SmbBookRouter.OpenResult.Supported ->
                        return attachRemoteSession(r.supply, saved, targetPage)
                    is com.mangareader.smbbridge.SmbBookRouter.OpenResult.Unsupported -> {
                        com.mangareader.smbbridge.RemoteDownloadDialogState.showDialog(path, "smb", b.id, b.title)
                        viewing = false; loading = false
                        back()
                        return emptyList()
                    }

                    is com.mangareader.smbbridge.SmbBookRouter.OpenResult.Failed -> {
                        android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
                        viewing = false; loading = false; return emptyList()
                    }
                }
            } else {
                when (val r = com.mangareader.webdavbridge.WebDavBookRouter.openWebDavBook(path, resumePage, archivePasswdProvider)) {
                    is com.mangareader.webdavbridge.WebDavBookRouter.OpenResult.Supported ->
                        return attachRemoteSession(r.supply, saved, targetPage)
                    is com.mangareader.webdavbridge.WebDavBookRouter.OpenResult.Unsupported -> {
                        com.mangareader.smbbridge.RemoteDownloadDialogState.showDialog(path, "webdav", b.id, b.title)
                        viewing = false; loading = false
                        back()
                        return emptyList()
                    }
                    is com.mangareader.webdavbridge.WebDavBookRouter.OpenResult.Failed -> {
                        android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
                        viewing = false; loading = false; return emptyList()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("Reader", "enterRemoteReader failed", t)
            android.widget.Toast.makeText(ctx, "打开失败：${t.message?.take(80)}", android.widget.Toast.LENGTH_SHORT).show()
            viewing = false
            loading = false
            return emptyList()
        }
    }

    suspend fun enterKomgaReader(
        b: com.mangareader.data.Book,
        path: String,
        targetPage: Int,
    ): List<com.mangareader.viewer.ArchiveEngine.PageMeta> {
        loading = true
        smbUnsupportedInfo = null
        try {
            val saved = withContext(Dispatchers.IO) {
                runCatching { dao.getProgress(b.id, path) }.getOrNull()
            }
            val resumePage = if (targetPage >= 0) targetPage else (saved?.lastPage ?: 0)
            when (val r = com.mangareader.komgabridge.reader.KomgaBookRouter.openKomgaBook(path, resumePage)) {
                is com.mangareader.komgabridge.reader.KomgaBookRouter.OpenResult.Supported ->
                    return attachRemoteSession(r.supply, saved, targetPage)
                is com.mangareader.komgabridge.reader.KomgaBookRouter.OpenResult.Unsupported -> {
                    android.widget.Toast.makeText(ctx, "该 EPUB 为文字电子书，不支持图片阅读", android.widget.Toast.LENGTH_SHORT).show()
                    viewing = false; loading = false; back()
                    return emptyList()
                }
                is com.mangareader.komgabridge.reader.KomgaBookRouter.OpenResult.Failed -> {
                    android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
                    viewing = false; loading = false; return emptyList()
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("Reader", "enterKomgaReader failed", t)
            android.widget.Toast.makeText(ctx, "打开失败：${t.message?.take(80)}", android.widget.Toast.LENGTH_SHORT).show()
            viewing = false
            loading = false
            return emptyList()
        }
    }


    /**
     * ★ 本地文件系统路径的归档阅读入口（下载到本地缓存的 mobi/azw3/rar/7z 等）。
     * ArchiveEngine.listPages 只支持 SAF 路径，文件系统路径走 useArchivePageLoader +
     * SmbPageAdapter 适配到 Reader UI（与远程阅读器同构）。
     */
    suspend fun enterLocalArchiveReader(
        b: com.mangareader.data.Book,
        localPath: String,
        targetPage: Int,
    ): List<com.mangareader.viewer.ArchiveEngine.PageMeta> {
        loading = true
        smbUnsupportedInfo = null
        try {

            val saved = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { dao.getProgress(b.id, localPath) }.getOrNull()
            }
            val resumePage = if (targetPage >= 0) targetPage else (saved?.lastPage ?: 0)
            val adapter = com.mangareader.smbbridge.SmbPageAdapter()
            val sessionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            sessionScope.launch {
                try {
                    com.hippo.ehviewer.gallery.useArchivePageLoader(
                        file = localPath.toPath(),
                        info = null,
                        startPage = resumePage,
                        passwdProvider = archivePasswdProvider,
                    ) { loader ->
                        adapter.attach(loader, null)
                        adapter.awaitClose()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.w("Reader", "local archive open failed", t)
                    adapter.failAsync(t)
                } finally {
                    sessionScope.cancel()
                }
            }
            try {
                kotlinx.coroutines.withTimeout(45_000L) { adapter.awaitReady() }
                return attachRemoteSession(adapter, saved, targetPage)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                adapter.close()
                android.widget.Toast.makeText(ctx, "打开超时", android.widget.Toast.LENGTH_SHORT).show()
                viewing = false; loading = false; return emptyList()
            }
        } catch (t: Throwable) {
            android.util.Log.w("Reader", "enterLocalArchiveReader failed", t)
            android.widget.Toast.makeText(ctx, "打开失败：${t.message?.take(80)}", android.widget.Toast.LENGTH_SHORT).show()
            viewing = false
            loading = false
            return emptyList()
        }
    }

    suspend fun enterOpdsReader(
        b: com.mangareader.data.Book,
        path: String,
        targetPage: Int,
    ): List<com.mangareader.viewer.ArchiveEngine.PageMeta> {
        loading = true
        smbUnsupportedInfo = null
        try {
            val saved = withContext(Dispatchers.IO) {
                runCatching { dao.getProgress(b.id, path) }.getOrNull()
            }
            val resumePage = if (targetPage >= 0) targetPage else (saved?.lastPage ?: 0)
            when (val r = com.mangareader.opdsbridge.reader.OpdsBookRouter.openOpdsBook(path, resumePage)) {
                is com.mangareader.opdsbridge.reader.OpdsBookRouter.OpenResult.Ready ->
                    return enterLocalArchiveReader(b, r.localCachePath, targetPage)
                is com.mangareader.opdsbridge.reader.OpdsBookRouter.OpenResult.Failed -> {
                    android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
                    viewing = false; loading = false; return emptyList()
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("Reader", "enterOpdsReader failed", t)
            android.widget.Toast.makeText(ctx, "打开失败：${t.message?.take(80)}", android.widget.Toast.LENGTH_SHORT).show()
            viewing = false
            loading = false
            return emptyList()
        }
    }

    fun convertRemoteEntriesToItems(
        entries: List<com.hippo.ehviewer.library.BrowseEntryRemote>,
        parentPath: String,
        auth: String,
    ): List<ArchiveEngine.Item>? {
        // ★ 2026-09-19 修"点图片进了奇怪目录、循环打不开"：
        //   分类器会给"当前文件夹自身的图片集合"发一条 relativeName="" 的 FolderGallery（自画廊行），
        //   下面原来的过滤把它当成了子项 → 纯图文件夹书弹出只有"自己"一个目录项的网格，
        //   点进去又回到同一网格 → 死循环。自画廊/空路径行不是真实子项，必须剔除；
        //   剔完没有真实子项 → 返回 null → 走 enterRemoteReader 直接按图片页打开。
        fun realChild(rel: String): Boolean =
            rel.replace('\\', '/').trim('/').isNotEmpty()
        val subEntries = entries.filter {
            when (it) {
                is com.hippo.ehviewer.library.BrowseEntryRemote.ArchiveGallery -> true
                is com.hippo.ehviewer.library.BrowseEntryRemote.Directory -> realChild(it.relativeName)
                is com.hippo.ehviewer.library.BrowseEntryRemote.RegularFile -> true
                is com.hippo.ehviewer.library.BrowseEntryRemote.VideoFile -> !it.virtual
                else -> false
            }
        }
        if (subEntries.isEmpty()) return null
        return subEntries.map { entry ->
            val isDir = entry is com.hippo.ehviewer.library.BrowseEntryRemote.Directory
            val childName = when (entry) {
                is com.hippo.ehviewer.library.BrowseEntryRemote.Directory -> entry.relativeName
                is com.hippo.ehviewer.library.BrowseEntryRemote.ArchiveGallery -> entry.fileName
                is com.hippo.ehviewer.library.BrowseEntryRemote.RegularFile -> entry.fileName
                is com.hippo.ehviewer.library.BrowseEntryRemote.VideoFile -> entry.fileName
                else -> entry.name
            }
            val childPath = if (parentPath.endsWith("/")) "$parentPath$childName" else "$parentPath/$childName"
            ArchiveEngine.Item(
                name = entry.name,
                auth = auth,
                tree = "",
                doc = childPath,
                dir = isDir,
                subPath = "",
            )
        }
    }

    suspend fun listRemoteFolderAsItems(path: String): List<ArchiveEngine.Item>? {
        return withContext(Dispatchers.IO) {
            try {
                if (path.startsWith("smb:")) {
                    val ref = com.mangareader.smbbridge.SmbPaths.parseBookPath(path) ?: return@withContext null
                    val source = com.hippo.ehviewer.smb.SmbRepository.load(ref.serverId) ?: return@withContext null
                    val password = com.hippo.ehviewer.smb.SmbPasswordStore.get(ref.serverId)
                    val remoteDir = if (source.share.isBlank()) ref.fullRel else ref.rel
                    val entries = com.hippo.ehviewer.smb.SmbGateway.listDirectory(source, password, remoteDir)
                    convertRemoteEntriesToItems(entries, path, "smb")
                } else if (path.startsWith("webdav:")) {
                    val ref = com.mangareader.webdavbridge.WebDavPaths.parseBookPath(path) ?: return@withContext null
                    val source = com.hippo.ehviewer.webdav.WebDavRepository.load(ref.serverId) ?: return@withContext null
                    val password = com.hippo.ehviewer.webdav.WebDavPasswordStore.get(ref.serverId)
                    val entries = com.hippo.ehviewer.webdav.WebDavGateway.listDirectory(source, password, ref.remotePath)
                    convertRemoteEntriesToItems(entries, path, "webdav")
                } else null
            } catch (e: Exception) {
                android.util.Log.w("Reader", "listRemoteFolderAsItems failed", e)
                null
            }
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
    /** @param targetPage ≥0 = 书签直跳目标页（覆盖 progress 恢复值）；默认 -1 走既有恢复逻辑 */
    suspend fun enterReader(path: String, targetPage: Int = -1): List<com.mangareader.viewer.ArchiveEngine.PageMeta> {
        val b = book ?: return emptyList()
        // ★ 方案1：远程书优先检查本地缓存（originalPath），归档格式从本地读（离线可读），mobi/azw3 走远程
        if (path.startsWith("smb:") || path.startsWith("webdav:")) {
            val cachePath = b.originalPath
            if (cachePath != null && cachePath.startsWith("/")) {
                val cacheExists = withContext(Dispatchers.IO) { java.io.File(cachePath).exists() }
                if (cacheExists) {
                    // ★ 进度迁移：旧进度存在远程路径(smb:/webdav:)下，本地路径查不到 → 迁移到 cachePath
                    withContext(Dispatchers.IO) {
                        val oldProgress = dao.getProgress(b.id, path)
                        if (oldProgress != null && dao.getProgress(b.id, cachePath) == null) {
                            dao.upsertProgress(b.id, cachePath, oldProgress.lastPage, oldProgress.completed, oldProgress.updatedAt, oldProgress.totalPages)
                        }
                    }
                    return enterLocalArchiveReader(b, cachePath, targetPage)
                } else {

                    withContext(Dispatchers.IO) { runCatching { dao.restoreBookToRemote(b.id) } }
                }
            } else if (cachePath != null && cachePath.startsWith("a:")) {
                // ★ 先尝试 SAF 路径（zip/cbz 等），空则转文件系统路径走 enterLocalArchiveReader（mobi/azw3）
                val safPages = withContext(Dispatchers.IO) { runCatching { engine.listPages(cachePath) }.getOrDefault(emptyList()) }
                if (safPages.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        val oldProgress = dao.getProgress(b.id, path)
                        if (oldProgress != null && dao.getProgress(b.id, cachePath) == null) {
                            dao.upsertProgress(b.id, cachePath, oldProgress.lastPage, oldProgress.completed, oldProgress.updatedAt, oldProgress.totalPages)
                        }
                    }
                    navStack = listOf(cachePath)
                    return enterReader(cachePath, targetPage)
                } else {
                    // a:auth|treePath|docId|ext → primary:xxx → /storage/emulated/0/xxx
                    val docId = cachePath.split("|").getOrNull(2) ?: ""
                    val realPath = if (docId.startsWith("primary:")) {
                        android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + docId.substringAfter("primary:")
                    } else null
                    if (realPath != null && withContext(Dispatchers.IO) { java.io.File(realPath).exists() }) {
                        return enterLocalArchiveReader(b, realPath, targetPage)
                    } else {
                        withContext(Dispatchers.IO) { runCatching { dao.restoreBookToRemote(b.id) } }
                    }
                }
            }
            val downloading = com.mangareader.smbbridge.SmbDownloadQueue.tasks.value.find {
                it.sourcePath == path && (it.status == com.mangareader.smbbridge.DownloadStatus.Downloading || it.status == com.mangareader.smbbridge.DownloadStatus.Queued)
            }
            if (downloading != null) {
                android.widget.Toast.makeText(ctx, "正在下载中，请稍候…", android.widget.Toast.LENGTH_SHORT).show()
                viewing = false; loading = false
                return emptyList()
            }
            if (b.fileType.isEmpty() && path == b.filePath) {
                val remoteItems = listRemoteFolderAsItems(path)
                if (remoteItems != null) {
                    items = remoteItems
                    navStack = listOf(path)
                    effectiveRootPath = path
                    viewing = false
                    loading = false
                    return emptyList()
                }
            }
            return if (path.startsWith("smb:")) enterRemoteReader(b, path, targetPage, isSmb = true)
                   else enterRemoteReader(b, path, targetPage, isSmb = false)
        }

        if (path.startsWith("komga:")) return enterKomgaReader(b, path, targetPage)

        if (path.startsWith("opds:")) return enterOpdsReader(b, path, targetPage)

        // ★ 修复：文件系统路径（下载到本地缓存的归档）走 useArchivePageLoader，
        //   ArchiveEngine.listPages 只支持 SAF 路径（a:/d: 前缀），文件系统路径会返回空。
        if (path.startsWith("/")) return enterLocalArchiveReader(b, path, targetPage)
        val pages = withContext(Dispatchers.IO) {
            try { engine.listPages(path) } catch (_: Exception) { emptyList() }
        }
        // ★ ④ 修复：listPages 失败/返回空 → 提示 + 不进 reading 死态。
        //   原 bug：pageList=空+viewing=true 落"没有内容"死胡同，FAB 卡死、退出键也可能无效必须杀进程。
        //   兜底：记 path → 弹 dialog 让用户选「移除本卷进度」或「返回书架」。
        //     - 点「移除本卷进度」才执行 dao.deleteProgress(b.id, path)——避免误删合法 progress（SAF 临时失败等场景）。
        //     - 默认 viewing=false 不进死态。
        if (pages.isEmpty()) {
            // ★ 已下载书本地文件丢失 → 触发恢复为远程书弹窗（originalPath 存在时可重新下载）
            if (b.originalPath != null) {
                redownloadBook = b
                showRedownloadDialog = true
            } else {
                missingFilePath = path
                showFileMissingDialog = true
            }
            pageList = emptyList()
            cur = 0
            viewing = false
            loading = false
            return emptyList()
        }
        if (config.autoWebtoon && pages.size >= 2 && mode != Mode.VERT && mode != Mode.VERT_PAGER && mode != Mode.WEBTOON_GAP) {
            val userSetMode = prefs.getInt("read_mode_$bookId", -1)
            if (userSetMode < 0) {
                val treeStr = path
                var totalRatio = 0f
                var count = 0
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
        val expandedPages = if (config.tallSplitEnabled &&
            (mode == Mode.VERT || mode == Mode.WEBTOON_GAP)) {
            val sm = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            val sw = ctx.resources.displayMetrics.widthPixels
            // ★ 长图分割：入口放宽到全部本地书（PDF + 图片族），白名单在 expander 内把守；
            //   allowProbe=true 探测尺寸并落尺寸表，全程 IO（B3）
            try { withContext(Dispatchers.IO) { com.mangareader.engine.pdf.VirtualPageExpander.expand(pages, true, true, sm, sw, engine, path, allowProbe = true) } }
            catch (_: Exception) { pages }
        } else pages
        pageList = expandedPages
        // ★ 分割诊断取证：入口展开统计进诊断日志（unprobed>0 = 有页探测失败被整页透传）
        try { com.mangareader.DiagTracer.mark(ctx, "tallSplit expand ${com.mangareader.engine.pdf.VirtualPageExpander.lastStats} path=$path") } catch (_: Exception) {}
        // ★ B1：分割实际生效（存在展开段）时提升共享位图缓存额度（仅大内存设备）
        try { engine.setSegmentCacheBoost(expandedPages.size > pages.size) } catch (_: Exception) {}
        val expandedSize = expandedPages.size
        val progress = withContext(Dispatchers.IO) { dao.getProgress(b.id, path) }
        android.util.Log.d("ReaderProgress", "enterReader bookId=${b.id} path=$path pages=${pages.size} progress=${progress?.let { "lastPage=${it.lastPage} completed=${it.completed} updatedAt=${it.updatedAt}" } ?: "null"}")
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
        // ★ PDF 分割跨形态重映射（2026-09-25 实查）：进度行存的是"保存时的展开形态"索引
        //   （totalPages=当时的展开总页数）。分割开关切换/换设备（屏宽不同→段数不同）后两个
        //   空间对不上：直接用会落错页，且 lastPage(旧空间) ≥ 当前末页会误触发"读完"标记。
        //   按比例映射回当前形态；同形态（含旧数据 totalPages=0）原样透传。
        val savedIdx = progress?.lastPage ?: 0
        val savedSize = progress?.totalPages ?: 0
        val remappedLastPage = if (progress != null && savedSize > 0 && savedSize != expandedSize && savedIdx > 0) {
            ((savedIdx.toLong() * expandedSize) / savedSize).toInt()
                .coerceIn(0, (expandedSize - 1).coerceAtLeast(0))
        } else savedIdx
        // ★ ⑦B 修复：willMarkRead 是「本次进卷正在补完成位」的本地信号。
        //   下方 lastPage 判定必须用同一个信号——否则 388 行 upsert 把 DB 改 completed=true
        //   但本地 val progress 仍 false → 走 else 分支 cur=末页，与"已读完卷再入应跳第 1 页"语义矛盾。
        //   修复后：willMarkRead=true 时也跳第 1 页，与 progress.completed==true 一致。
        //   下次回卷进 enterReader 重读 progress（DB 已 true）走第 1 个分支，自愈。
        // ★ v1.11：willMarkRead 加 pages.size > 1 守卫（单页列表不构成"读到过末页"，与 save() 的 done 守卫对齐）
        val willMarkRead = !healed && progress != null && !progress.completed && expandedSize > 1 && remappedLastPage >= expandedSize - 1
        if (willMarkRead) {
            val now = System.currentTimeMillis()
            try { withContext(Dispatchers.IO) { dao.upsertProgress(b.id, path, remappedLastPage, true, now, expandedSize) } } catch (_: Exception) {}
        }
        // ★ 已读完卷再次进入跳第 1 页（progress.completed==true 或本次正补写完成位 willMarkRead → lastPage=0）
        val lastPage = if (progress?.completed == true || willMarkRead) 0 else remappedLastPage
        // 书签直跳优先级最高：targetPage 显式覆盖
        cur = (if (targetPage >= 0) targetPage else lastPage).coerceIn(0, (expandedSize - 1).coerceAtLeast(0))

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

        viewing = true

        return pages
    }

    // ★ 书签状态同步：翻页/切章/增删后刷新"当前页是否有书签"；TOC 打开时拉一次该书书签列表
    LaunchedEffect(cur, navStack, bookmarkTick, viewing, book) {
        val bid = book?.id
        val ch = if (viewing) (navStack.lastOrNull() ?: book?.filePath) else null
        pageBookmarked = if (bid != null && bid > 0 && ch != null) {
            withContext(Dispatchers.IO) { runCatching { dao.countBookmark(bid, ch, cur) }.getOrDefault(0) } > 0
        } else false
    }

    DisposableEffect(viewing) {
        if (viewing) {
            val prefs = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
            VolumeKeyHandler.enabled = prefs.getBoolean("reader_volume_keys", false)
            VolumeKeyHandler.inverted = prefs.getBoolean("reader_volume_keys_inverted", false)
            VolumeKeyHandler.onNext = { go(1) }
            VolumeKeyHandler.onPrev = { go(-1) }
        }
        onDispose { VolumeKeyHandler.onNext = null; VolumeKeyHandler.onPrev = null }
    }
    DisposableEffect(ui) { VolumeKeyHandler.menuVisible = ui; onDispose { } }

    LaunchedEffect(showToc, bookmarkTick, book) {
        val bid = book?.id
        if (showToc && bid != null) {
            bookmarks = withContext(Dispatchers.IO) { runCatching { dao.getBookmarksForBook(bid) }.getOrDefault(emptyList()) }
        }
    }
    /** ★ 不支持格式下载：后台下载到本地缓存，完成后 Toast 提示，不自动进阅读（用户可返回书架） */
    fun downloadRemoteUnsupported(filePath: String, kind: String) {
        if (smbDownloadBusy) return
        smbDownloadBusy = true
        val b = book
        // ★ 关闭弹窗 + 退出 Reader，让用户回到书架（后台下载）
        smbUnsupportedInfo = null
        viewing = false; loading = false
        smbSession?.let { old -> smbSession = null; scope.launch(Dispatchers.IO) { runCatching { old.close() } } }
        scope.launch {
            val localPath = withContext(Dispatchers.IO) {
                try {
                    if (kind == "smb") com.mangareader.smbbridge.SmbUnsupportedFormatHandler.downloadWhole(filePath)
                    else com.mangareader.webdavbridge.WebDavUnsupportedFormatHandler.downloadWhole(filePath)
                } catch (t: Throwable) {
                    android.util.Log.w("Reader", "downloadWhole failed", t); null
                }
            }
            smbDownloadBusy = false
            if (localPath == null) {
                android.widget.Toast.makeText(ctx, "下载失败，可重试", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (b != null) {
                withContext(Dispatchers.IO) {
                    runCatching { dao.rehomeBookToLocal(b.id, localPath) }
                }
            }
            android.widget.Toast.makeText(ctx, "下载完成，点击书籍阅读", android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    /** 兜底：私有目录（原行为），相册不可见但绝不丢图 */
    fun savePageFallback(bmp: Bitmap): String {
        return try {
            val dir = java.io.File(ctx.getExternalFilesDir(null), "saved_pages"); dir.mkdirs()
            val file = java.io.File(dir, "page_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            "已保存到应用目录 saved_pages"
        } catch (e: Exception) { "保存失败" }
    }

    /** MediaStore / 公共目录写图（调用方在 IO 线程）；任何环节失败一律回退私有目录，绝不抛 */
    fun writePageImage(bmp: Bitmap): String {
        val name = "mandu_page_${System.currentTimeMillis()}.png"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/漫读")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return savePageFallback(bmp)
                val ok = resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: false
                if (ok != true) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    return savePageFallback(bmp)
                }
                val done = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                "已保存到相册 Pictures/漫读"
            } else {
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "漫读"
                )
                dir.mkdirs()
                val f = java.io.File(dir, name)
                java.io.FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/png"), null)
                "已保存到相册 Pictures/漫读"
            }
        } catch (e: Exception) {
            savePageFallback(bmp)
        }
    }

    // ★ 长按存图 v2（API 26-28 存储权限通道）：结果回来后重跑 savePageAt；拒绝则回退应用私有目录（不丢图）
    val savePagePermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val idx = pendingPageSaveIdx
        pendingPageSaveIdx = -1
        if (idx >= 0) {
            if (granted) {
                scope.launch {
                    try {
                        val bmp = withContext(Dispatchers.IO) { engine.loadPage(displayPages[idx], navStack.lastOrNull() ?: book?.filePath ?: "") }
                        val msg = if (bmp != null) withContext(Dispatchers.IO) { writePageImage(bmp) } else "保存失败"
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { android.widget.Toast.makeText(ctx, "保存失败", android.widget.Toast.LENGTH_SHORT).show() }
                }
            } else {
                // ★ 权限拒绝不存私有目录（saved_pages 用户无法导出，存了无意义）——明确提示未保存
                android.widget.Toast.makeText(ctx, "未获得存储权限，未能保存；可在系统设置中授权后重试", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ★ 长按存图 v2：存公共 Pictures/漫读（相册可见）。API29+ 走 MediaStore（无需权限）；
    //   API26-28 先查 WRITE_EXTERNAL_STORAGE，未授权走权限请求（回调重试），任何失败回退私有目录
    fun savePageAt(pageIdx: Int) {
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingPageSaveIdx = pageIdx
            savePagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { engine.loadPage(displayPages[pageIdx], navStack.lastOrNull() ?: book?.filePath ?: "") }
                val msg = if (bmp != null) withContext(Dispatchers.IO) { writePageImage(bmp) } else "保存失败"
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { android.widget.Toast.makeText(ctx, "保存失败", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    // ★ 书签 click/toggle：同页 idempotent（唯一索引+IGNORE）；toggle 结果 Toast 反馈
    var showBookmarkNoteDialog by remember { mutableStateOf(false) }
    var pendingBookmarkNote by remember { mutableStateOf("") }

    fun toggleBookmark() {
        val b = book ?: return
        if (!viewing || pageList.isEmpty() || b.id <= 0) return
        val chPath = navStack.lastOrNull() ?: b.filePath
        val page = cur
        scope.launch(Dispatchers.Main) {
            val exists = withContext(Dispatchers.IO) { dao.countBookmark(b.id, chPath, page) > 0 }
            if (exists) {
                withContext(Dispatchers.IO) { dao.deleteBookmark(b.id, chPath, page) }
                bookmarkTick++
                android.widget.Toast.makeText(ctx, "已移除书签", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val noteEnabled = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getBoolean("bookmark_note_enabled", false)
                if (noteEnabled) {
                    pendingBookmarkNote = ""
                    showBookmarkNoteDialog = true
                } else {
                    withContext(Dispatchers.IO) { dao.insertBookmark(com.mangareader.data.Bookmark(bookId = b.id, chapterPath = chPath, page = page)) }
                    bookmarkTick++
                    android.widget.Toast.makeText(ctx, "已添加书签（第 ${page + 1} 页）", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 书签跳转：同章直接翻 cur（同进度条跳页通道，五模式天然生效）；跨章复用目录跳章模式带 targetPage */
    fun jumpToBookmark(bm: com.mangareader.data.Bookmark) {
        val chNow = navStack.lastOrNull()
        if (bm.chapterPath == chNow && pageList.isNotEmpty()) {
            cur = bm.page.coerceIn(0, (pageList.size - 1).coerceAtLeast(0))
            return
        }
        // 跨章：navStack 重建与目录跳章同款（书根 + 目标章完整身份）
        val bookRoot = navStack.firstOrNull { it.startsWith("z:") }
            ?: navStack.firstOrNull()?.takeIf { it.startsWith("d:") }
            ?: bm.chapterPath
        loading = false
        navStack = listOf(bookRoot, bm.chapterPath)
        scope.launch(Dispatchers.Main) {
            items = emptyList()
            val chItems = withContext(Dispatchers.IO) {
                try { engine.children(bm.chapterPath) } catch (_: Exception) { emptyList() }
            }
            if (chItems.isEmpty()) {
                enterReader(bm.chapterPath, targetPage = bm.page)
            } else {
                items = chItems
            }
        }
    }

    fun openFolder(path: String) {
        loading = true; viewing = false; pageList = emptyList(); errorMsg = null
        scope.launch {
            try {
                if (path.startsWith("smb:") || path.startsWith("webdav:")) {
                    val remoteItems = listRemoteFolderAsItems(path)
                    android.util.Log.d("ReaderToc", "path=$path remote items=${remoteItems?.size}")
                    effectiveRootPath = navStack.lastOrNull() ?: path
                    if (remoteItems != null) {
                        items = remoteItems
                    } else {
                        enterReader(path)
                    }
                    loading = false
                    return@launch
                }
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
                android.util.Log.d("ReaderToc", "path=$path children=${result.second.size} pages=${result.third.size}")
                val zCount = navStack.count { it.startsWith("z:") }
                val tocUpdated = zCount <= 1 && result.first.isNotEmpty()
                if (tocUpdated) tocItems = result.first
                android.util.Log.d("ReaderToc", "zCount=$zCount tocUpdated=$tocUpdated size=${if (tocUpdated) result.first.size else tocItems.size} reason=${if (zCount > 1) "zCount>1" else if (result.first.isEmpty()) "childrenEmpty" else "ok"}")
                val childItems = result.second
                val pages = result.third
                effectiveRootPath = navStack.lastOrNull() ?: path
                items = childItems
                if (items.isEmpty()) {
                    // ★ §23 统一走 enterReader 读 reading_progress 恢复 cur（pages 仅作空判，enterReader 内会复读 listPages）
                    val chapPath = effectiveRootPath
                    if (pages.isNotEmpty()) {
                        android.util.Log.d("ReaderToc", "children empty pages=${pages.size} -> enterReader")
                        enterReader(chapPath)
                    } else {
                        android.util.Log.d("ReaderToc", "children empty pages=${pages.size} -> showFileMissingDialog")
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
        if (item.auth == "smb" || item.auth == "webdav") {
            navStack = navStack + item.doc
            if (item.dir) {
                openFolder(navStack.last())
            } else {
                loading = true; viewing = false; pageList = emptyList(); errorMsg = null
                scope.launch {
                    try {
                        enterReader(navStack.last())
                    } catch (e: Exception) { errorMsg = "加载失败: ${e.message}" }
                    loading = false
                }
            }
            return
        }
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
            val currentPath = book?.filePath ?: run {
                android.util.Log.w("AdjBook", "adj(d=$direction): book 为空"); return null
            }
            // ★ F2.1：相邻书改"同父文件夹内文件名自然序"。原实现直接用 getAllBooksList 的
            //   lastReadAt DESC 序取相邻，"下一本"实际按最近阅读跳——末卷会跳进书库里的任意文件。
            //   同夹无邻居（首/末卷方向耗尽）→ null → "没有下一本/上一本"提示，不再全局乱跳。
            val parentOf = { p: String -> p.substringBeforeLast('/', missingDelimiterValue = "") }
            val curParent = parentOf(currentPath)
            val siblings = if (curParent.isEmpty()) emptyList() else allBooks
                .filter { parentOf(it.filePath) == curParent }
                // ★ 去重：书库存在同文件多条记录（重复入库）时，相邻切换会切到"自己的另一条记录"，
                //   表现为"说切换了实际没切"（日志实锤：同 cid 出现 book=35/book=7 两条）
                .distinctBy { it.filePath }
                .sortedWith(compareBy(com.mangareader.util.ComicSortUtil.fileNameComparator) { it.filePath })
            val idx = siblings.indexOfFirst { b -> b.filePath == currentPath }
            // ★ AdjBook 诊断：切书失败时日志直接给出原因（书库数量/父目录解析/同夹清单/方向边界）
            android.util.Log.i(
                "AdjBook",
                "adj(d=$direction): 库内=${allBooks.size} curParent=[$curParent] 同夹=${siblings.size} 当前序号=$idx " +
                    "清单=[${siblings.joinToString(" | ") { it.filePath }}] 当前=[$currentPath]",
            )
            if (idx == -1) {
                android.util.Log.w("AdjBook", "adj: 当前书不在同夹清单——父目录解析不一致 / 书被隐藏或已删")
                return null
            }
            val targetIdx = idx + direction
            if (targetIdx in siblings.indices) {
                val t = siblings[targetIdx]
                android.util.Log.i("AdjBook", "adj: 目标=[${t.title}] ${t.filePath}")
                t
            } else {
                android.util.Log.i("AdjBook", "adj: d=$direction 方向已到边界（首/末本）")
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("AdjBook", "adj 异常", e); null
        }
    }

    fun extractSubPathFromNav(): String {
        val last = navStack.lastOrNull() ?: return ""
        // ★ F2.2 章节身份（与 openItem 压栈格式一致，tocItems.subPath 同格式即可互查）：
        //   d: 书内的文件夹章 = 完整 d: 路径；文件章 = 完整 a: 路径；z: 卷 = 相对子路径
        if (last.startsWith("d:") || last.startsWith("a:")) return last
        if (!last.startsWith("z:")) return ""
        val inner = last.removePrefix("z:")
        val sep = inner.indexOf("//")
        return if (sep >= 0) inner.substring(sep + 2) else ""
    }

    fun stripExt(name: String): String {
        if (name.isEmpty() || name.startsWith(".")) return name
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    fun extractVolumeName(path: String): String? {
        return try {
            when {
                path.startsWith("d:") || path.startsWith("a:") -> {
                    val rest = path.removePrefix(if (path.startsWith("d:")) "d:" else "a:")
                    val parts = rest.split("|", limit = 4)
                    if (parts.size < 3) null
                    else stripExt(parts[2].substringAfterLast('/'))
                }
                path.startsWith("z:") -> {
                    val inner = path.removePrefix("z:")
                    val sep = inner.indexOf("//")
                    if (sep < 0) null
                    else {
                        var subPath = inner.substring(sep + 2)
                        if (subPath.endsWith('/')) subPath = subPath.dropLast(1)
                        stripExt(subPath.substringAfterLast('/'))
                    }
                }
                path.startsWith("smb:") || path.startsWith("webdav:") -> {
                    val rest = path.removePrefix(if (path.startsWith("smb:")) "smb:" else "webdav:")
                    val relPath = rest.substringAfter(':').trimEnd('/')
                    stripExt(relPath.substringAfterLast('/'))
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun computeReaderTitle(
        b: com.mangareader.data.Book?,
        stack: List<String>,
        isViewing: Boolean,
        hideTitles: Boolean
    ): String {
        if (hideTitles) return ""
        if (b == null) return ""
        if (!isViewing) return b.title
        val currentVolumePath = stack.lastOrNull() ?: b.filePath
        if (currentVolumePath == b.filePath) return b.title
        return extractVolumeName(currentVolumePath) ?: b.title
    }

    data class ContinueContext(val navStack: List<String>, val tocItems: List<ArchiveEngine.Item>, val fellBack: Boolean)

    fun isParentUnderBookRoot(parentPath: String, bookFilePath: String): Boolean {
        return try {
            if (parentPath.startsWith("a:") || parentPath.startsWith("d:")) {
                val pp = engine.parsePathForMobi(parentPath) ?: return false
                val bp = engine.parsePathForMobi(bookFilePath) ?: return false
                if (pp.first != bp.first || pp.second != bp.second) return false
                val bookDoc = bp.third
                val parentDoc = pp.third
                if (bookDoc.isEmpty()) return true
                parentDoc == bookDoc || parentDoc.startsWith("$bookDoc/")
            } else if (parentPath.startsWith("z:") && bookFilePath.startsWith("z:")) {
                val pInner = parentPath.removePrefix("z:")
                val bInner = bookFilePath.removePrefix("z:")
                val pSep = pInner.indexOf("//")
                val bSep = bInner.indexOf("//")
                if (pSep < 0 || bSep < 0) return false
                val pArchive = pInner.substring(0, pSep)
                val bArchive = bInner.substring(0, bSep)
                if (pArchive != bArchive) return false
                val bSub = bInner.substring(bSep + 2).trimEnd('/')
                val pSub = pInner.substring(pSep + 2).trimEnd('/')
                if (bSub.isEmpty()) return true
                pSub == bSub || pSub.startsWith("$bSub/")
            } else false
        } catch (_: Exception) { false }
    }

    fun buildNavStack(bookFilePath: String, parentPath: String, jumpPath: String): List<String> {
        val midPaths = mutableListOf<String>()
        if (parentPath.startsWith("a:") || parentPath.startsWith("d:")) {
            val pp = engine.parsePathForMobi(parentPath) ?: return listOf(bookFilePath, jumpPath)
            val bp = engine.parsePathForMobi(bookFilePath) ?: return listOf(bookFilePath, jumpPath)
            val bookDoc = bp.third
            val parentDoc = pp.third
            val auth = pp.first
            val tree = pp.second
            if (parentDoc.length > bookDoc.length) {
                val rel = if (bookDoc.isEmpty()) parentDoc else parentDoc.removePrefix("$bookDoc/")
                val segments = rel.split('/').filter { it.isNotEmpty() }
                var acc = bookDoc
                for (seg in segments) {
                    acc = if (acc.isEmpty()) seg else "$acc/$seg"
                    midPaths.add("d:$auth|$tree|$acc")
                }
            }
        } else if (parentPath.startsWith("z:")) {
            val pInner = parentPath.removePrefix("z:")
            val pSep = pInner.indexOf("//")
            if (pSep >= 0) {
                val archivePath = pInner.substring(0, pSep)
                val pSub = pInner.substring(pSep + 2).trimEnd('/')
                val bSub = if (bookFilePath.startsWith("z:")) {
                    val bInner = bookFilePath.removePrefix("z:")
                    val bSep = bInner.indexOf("//")
                    if (bSep >= 0) bInner.substring(bSep + 2).trimEnd('/') else ""
                } else ""
                if (pSub.length > bSub.length) {
                    val rel = if (bSub.isEmpty()) pSub else pSub.removePrefix("$bSub/")
                    val segments = rel.split('/').filter { it.isNotEmpty() }
                    var acc = ""
                    for (seg in segments) {
                        acc = if (acc.isEmpty()) seg else "$acc/$seg"
                        midPaths.add("z:$archivePath//$acc/")
                    }
                }
            }
        }
        return listOf(bookFilePath) + midPaths + listOf(jumpPath)
    }

    suspend fun buildContinueContext(bookFilePath: String, jumpPath: String): ContinueContext {
        val parentPath = engine.resolveParentPath(jumpPath)
        val fallbackNav = listOf(bookFilePath, jumpPath)
        val fallbackToc = withContext(Dispatchers.IO) { try { engine.children(bookFilePath) } catch (_: Exception) { emptyList() } }
        if (parentPath == null) return ContinueContext(fallbackNav, fallbackToc, true)
        if (!isParentUnderBookRoot(parentPath, bookFilePath)) return ContinueContext(fallbackNav, fallbackToc, true)
        val siblings = withContext(Dispatchers.IO) { try { engine.children(parentPath) } catch (_: Exception) { emptyList() } }
        if (siblings.isEmpty()) return ContinueContext(fallbackNav, fallbackToc, true)
        return ContinueContext(buildNavStack(bookFilePath, parentPath, jumpPath), siblings, false)
    }

    fun confirmSwitch() {

        if (switchTargetPath.isEmpty()) return
        isSwitching = true
        if (tocItems.size > 1 && tocItems.any { it.subPath == switchTargetPath }) {
            // 切章节：重置navStack到书根+新章节，不嵌套
            // ★ F2.2：d: 书的书根取 navStack 首位（d: 栈首即书根；原来 fallback 到 switchTargetPath
            //   会把目标卷当书根压栈）；z: 维持原取法
            val bookRoot = navStack.firstOrNull { it.startsWith("z:") }
                ?: navStack.firstOrNull()?.takeIf { it.startsWith("d:") }
                ?: switchTargetPath
            // ★ 修复白屏：先同步清 pageList+viewing，再更新 navStack，防止 NativeReaderScreen
            //   重建时拿到旧/空 pageList 白屏（navStack 更新触发 key 变→重建，此时 pageList 已空+viewing=false 不显示）
            val fullPath = if (bookRoot.startsWith("z:") && bookRoot.endsWith("//")) "$bookRoot$switchTargetPath" else switchTargetPath
            pageList = emptyList(); viewing = false; items = emptyList()
            navStack = listOf(bookRoot, fullPath)
            scope.launch {
                withContext(Dispatchers.IO) {
                    val chItems = try { engine.children(fullPath) } catch (_: Exception) { emptyList() }
                    if (chItems.isEmpty()) {
                        // ★ §23 改走 enterReader 恢复 lastPage
                        enterReader(fullPath)
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
                // ★ 切书落点分发：文件夹书(d:)走 openFolder 列目录；单文件书(a:/z:)直达正文
                //   （原实现一律 openFolder——对单文件路径 children 为空，表现为"说切换了实际没切"）
                if (switchTargetPath.startsWith("a:") || switchTargetPath.startsWith("z:")) {
                    // ★ §23 改走 enterReader 恢复 lastPage
                    enterReader(switchTargetPath)
                } else {
                    openFolder(switchTargetPath)
                }
            }
        }
    }

    fun switchToNext() {
        if (isSwitching) return
        val curSubPath = extractSubPathFromNav()
        if (tocItems.size > 1) {
            val curIdx = tocItems.indexOfFirst { it.subPath == curSubPath }
            val nextIdx = if (curIdx == -1) 0 else curIdx + 1
            if (nextIdx < tocItems.size) {
                switchTargetName = tocItems[nextIdx].name
                switchTargetPath = tocItems[nextIdx].subPath
                confirmSwitch()
            } else {
                scope.launch {
                    val nextBook = findAdjacentBook(1)
                    if (nextBook != null) {
                        switchTargetName = nextBook.title
                        switchTargetPath = nextBook.filePath
                        confirmSwitch()
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
                    confirmSwitch()
                } else {
                    android.widget.Toast.makeText(ctx, "没有下一本了", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun switchToPrevious() {
        if (isSwitching) return
        val curSubPath = extractSubPathFromNav()
        if (tocItems.size > 1) {
            val curIdx = tocItems.indexOfFirst { it.subPath == curSubPath }
            val prevIdx = if (curIdx <= 0) -1 else curIdx - 1
            if (prevIdx >= 0) {
                switchTargetName = tocItems[prevIdx].name
                switchTargetPath = tocItems[prevIdx].subPath
                confirmSwitch()
            } else {
                scope.launch {
                    val prevBook = findAdjacentBook(-1)
                    if (prevBook != null) {
                        switchTargetName = prevBook.title
                        switchTargetPath = prevBook.filePath
                        confirmSwitch()
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
                    confirmSwitch()
                } else {
                    android.widget.Toast.makeText(ctx, "没有上一本了", android.widget.Toast.LENGTH_SHORT).show()
                }
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
            if (navStack.size > 1) navStack = navStack.dropLast(1)
        } else if (navStack.size > 1) {
            // 有上一级 → 回到上一级目录
            viewing = false; pageList = emptyList()
            navStack = navStack.dropLast(1)
            openFolder(navStack.last())
        } else {
            // 退出阅读器
            try { back() } catch (_: Exception) {}
        }
    }
    BackHandler(enabled = !showSettings && !showSrPanel) { goBack() }

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
            uiJob = if (delayMs > 0L) scope.launch { delay(delayMs); if (ui) { ui = false; hideUi() } } else null
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
            if (delayMs > 0L) uiJob = scope.launch { delay(delayMs); if (ui) { ui = false; hideUi() } }
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
                    val ctx = buildContinueContext(filePath, jumpPath)
                    navStack = ctx.navStack
                    if (ctx.tocItems.isNotEmpty()) tocItems = ctx.tocItems
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
                    //   startPage >= 0：浏览页点击散图直达该页（优先于续读页，仅本次进书生效）
                    val pages = enterReader(filePath, targetPage = startPage)
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
        val globalBg = try { com.mangareader.data.ReaderConfig.ReadBackground.valueOf(globalBgName) } catch (_: Exception) { com.mangareader.data.ReaderConfig.ReadBackground.WHITE }
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
                isVerticalMode -> activeWebtoonRef?.isCurrentPageReady() == true
                else -> activePagerRef?.isCurrentPageReady() == true
            }
            if (ready) break
            if (waited >= 1500L && !kicked) {
                kicked = true
                try {
                    if (isVerticalMode) {
                        activeWebtoonRef?.let { it.goToPage(it.currentPageForKick) }
                    } else {
                        activePagerRef?.let { it.goToPage(it.currentPageForKick) }
                    }
                } catch (_: Throwable) {}
            }
        }
        run {
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
        activePagerRef?.setTileModeEnabled(config.tileModeEnabled)

        activePagerRef?.pageBgColor = bgColor.toArgb()
        activeWebtoonRef?.imageMarginH = config.imageMarginH
        activeWebtoonRef?.gapEnabled = (mode == Mode.WEBTOON_GAP)
        activeWebtoonRef?.trimWhiteBorder = config.isTrimWhiteBorder
        activeWebtoonRef?.trimThreshold = config.trimThreshold
        activeWebtoonRef?.trimWhiteRatio = config.trimWhiteRatio
        activeWebtoonRef?.setTileModeEnabled(config.tileModeEnabled)
        activeWebtoonRef?.pageBgColor = bgColor.toArgb()
    }

    LaunchedEffect(config.autoFlipInterval, viewing, isVerticalMode, activePagerRef, activeWebtoonRef) {
        val interval = config.autoFlipInterval
        if (interval > 0 && viewing) {
            while (true) {
                delay(interval.toLong())
                if (isVerticalMode) activeWebtoonRef?.nextPage() else activePagerRef?.nextPage()
            }
        }
    }

    var webtoonViewRef by remember { mutableStateOf<View?>(null) }
    var pagerViewRef by remember { mutableStateOf<View?>(null) }
    var autoScrollPaused by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (ui || !viewing) {
                val pageSubtitle = if (viewing && displayPages.isNotEmpty() && cur >= 0) {
                    val totalCount = if (expandedPageCount > 0) expandedPageCount else displayPages.size
                    val p = if (cur < displayPages.size) displayPages[cur] else displayPages.last()
                    val name = p.name.ifEmpty { p.ext.substringAfterLast("/").substringBeforeLast(".") }
                    "${cur + 1}/$totalCount  $name"
                } else ""
                val hideAllTitlesPrefs = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
                val hideAllTitles = hideAllTitlesPrefs.getBoolean("hide_all_titles", false)
                val inBookContinueOn = prefs.getBoolean("inbook_continue_read", true)
                val continueTarget = if (inBookContinueOn && !viewing && items.isNotEmpty()) {
                    remember(items, navStack, progressForToc) {
                        computeInBookContinueTarget(navStack.lastOrNull(), progressForToc)
                    }
                } else null
                ReaderTopBar(
                    title = computeReaderTitle(book, navStack, viewing, hideAllTitles),
                    subtitle = pageSubtitle,
                    viewing = viewing,
                    bgColor = if (viewing) bgColor else MaterialTheme.colorScheme.surface,
                    isFavorite = book?.isFavorite ?: false,
                    isHidden = book?.isHidden ?: false,
                    onBack = ::goBack,
                    onFavoriteClick = {
                        book?.let { b ->
                            val newFav = !(b.isFavorite)
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.updateBook(b.copy(isFavorite = newFav)) }
                                book = b.copy(isFavorite = newFav)
                            }
                        }
                    },
                    onRenameClick = {
                        renameInput = book?.title ?: ""
                        showRename = true
                    },
                    onAddToShelfClick = {
                        book?.let { b ->
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.setHidden(b.id, false) }
                                book = b.copy(isHidden = false)
                            }
                        }
                    },
                    onContinue = if (continueTarget != null) {
                        {
                            loading = true; viewing = false; pageList = emptyList(); errorMsg = null
                            scope.launch {
                                try {
                                    val bookRoot = navStack.firstOrNull() ?: book?.filePath
                                    if (bookRoot != null) {
                                        val contCtx = buildContinueContext(bookRoot, continueTarget.path)
                                        navStack = contCtx.navStack
                                        if (contCtx.tocItems.isNotEmpty()) tocItems = contCtx.tocItems
                                        enterReader(contCtx.navStack.last())
                                    }
                                } catch (e: Exception) { errorMsg = "加载失败: ${e.message}" }
                                loading = false
                            }
                        }
                    } else null,
                    continueTargetName = if (continueTarget != null) continueDisplayName(continueTarget.path) else ""
                )
            }
        },
        containerColor = bgColor,
        bottomBar = {
            if (viewing && ui) {
                Column(Modifier.fillMaxWidth()) {
                    ReaderBottomBar(actions = buildList {
                        add(BottomBarAction(Icons.Default.Menu, "目录") { showToc = !showToc })
                        add(BottomBarAction(
                            if (pageBookmarked) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                            if (pageBookmarked) "已书签" else "书签",
                            isActive = pageBookmarked
                        ) { toggleBookmark() })
                        add(BottomBarAction(Icons.Default.AutoFixHigh, "画质增强") { showSrPanel = true })
                        // 超分作用域已开启 或 离线批量产物存在 → 出现"对比"按钮
                        if (com.mangareader.sr.SrUiState.scope.intValue > 0 || com.mangareader.sr.SrPipeline.sessionHasBatch) {
                            add(BottomBarAction(Icons.Default.CompareArrows, if (com.mangareader.sr.SrUiState.compareActive.value) "退出对比" else "对比") {
                                com.mangareader.sr.SrUiState.compareActive.value = !com.mangareader.sr.SrUiState.compareActive.value
                            })
                        }
                        add(BottomBarAction(Icons.Default.Settings, "设置") { showSettings = true })
                    }, bgColor = if (viewing) bgColor else MaterialTheme.colorScheme.surface)
                    // 进度条放在底部（横竖都一样）
                    ChapterNavigator(
                        isVertical = false,
                        currentPage = cur,
                        totalPages = if (expandedPageCount > 0) expandedPageCount else displayPages.size,
                        onPageChange = { newCur ->
                            // ★ P0-B6：Slider 直通不设限（用户要求：任何时候都不能限制进度条操作）。
                            //   误触防护由两处承担：① ChapterNavigator 内部改为松手才提交（拖动过程不触发本回调）
                            //   ② settleWindow 承诺"用户滚过绝不拉回"
                            cur = newCur
                        },
                        onSliderFinished = { save(); restartAutoHide() },
                        onPreviousChapter = ::switchToPrevious,
                        onNextChapter = ::switchToNext,
                        isShowPageNum = config.isShowPageNum,
                        isRtl = isRtl,
                        bgColor = if (viewing) bgColor else MaterialTheme.colorScheme.surface,
                        onPreviewPage = { previewPage ->
                            // ★ 丝滑对齐：拖动时预热目标页（preloadAround 幂等，缓存命中近零开销）
                            val pv = activePagerRef ?: (pagerViewRef as? com.mangareader.native.PagerReaderView)
                            pv?.preloadPreview(previewPage)
                        }
                    )
                }
            }
        }
    ) { pad ->
        val shouldApplyPadding = !config.isImmersiveMode || ui || !viewing
        Box(Modifier.fillMaxSize().background(if (viewing) bgColor else MaterialTheme.colorScheme.surface)) {
            // 内容区域（带 padding）
            Box(Modifier.fillMaxSize().then(if (shouldApplyPadding) Modifier.padding(pad) else Modifier)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(color = Color(0xFF7C8CF8))
                            Text("正在打开…", color = Color(0xFF888888), fontSize = 13.sp)
                        }
                    }
                    errorMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(errorMsg!!) }
                    smbUnsupportedInfo != null -> {
                        Box(Modifier.fillMaxSize())
                    }
                    viewing && displayPages.isNotEmpty() -> {
                    val pageCount = if (expandedPageCount > 0) expandedPageCount else displayPages.size
                    val safeCur = cur.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    if (safeCur != cur) cur = safeCur
                    // ★ §34.3：isVertical 仅条漫（VERT/WEBTOON_GAP）走 webtoonView；
                    //   VERT_PAGER 不属于 isVertical，走 pagerView（传 verticalPager=true）。
                    val vertical = mode == Mode.VERT || mode == Mode.WEBTOON_GAP
                    val verticalPagerEffective = isVerticalPagerMode
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
                        key(navStack.lastOrNull(), vertical, isRtl, verticalPagerEffective, config.isFlipAnimation, config.isPageCurlAnimation, config.pageLayout, config.tileModeEnabled) {
                        // ★ N1 无缝续读：读到卷末静默进入同书下一卷（默认关；失败回退现状过渡页）。
                        //   返回 true = 已接管本次章末（不再弹过渡页）；false = 按现状走。
                        val trySeamlessNextVolume = handler@{
                            if (!config.seamlessNextVolume) return@handler false
                            if (tocItems.size < 2) return@handler false
                            // ★ 防抖：3 秒内已触发过就不再接（双页模式切换/末端重复回调的并发窗口）
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastSeamlessAt < 3000L) return@handler false
                            val curIdx = tocItems.indexOfFirst { it.subPath == extractSubPathFromNav() }
                            if (curIdx == -1) return@handler false
                            val nextIdx = curIdx + 1
                            if (nextIdx >= tocItems.size) return@handler false   // 已是最末卷 → 回退现状
                            val target = tocItems[nextIdx]
                            lastSeamlessAt = now
                            scope.launch {
                                val enterable = withContext(Dispatchers.IO) {
                                    try { engine.children(target.subPath).isEmpty() } catch (_: Exception) { false }
                                }
                                if (!enterable) {

                                    return@launch
                                }
                                // 作用域写回：beginSession 会把实时超分作用域重置为关闭，无缝跨卷要保留
                                com.mangareader.sr.SrPipeline.pendingScopeRestore = com.mangareader.sr.SrUiState.scope.intValue
                                switchTargetName = target.name
                                switchTargetPath = target.subPath
                                confirmSwitch()
                                android.widget.Toast.makeText(ctx, "已进入下一卷：${target.name}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        val handleChapterEnd = { isLast: Boolean ->
                            val forwardEnd = isLast
                            if (forwardEnd && trySeamlessNextVolume()) {
                                // 静默切换已启动

                            }
                        }
                        var lastSkipMarkAt by remember { mutableLongStateOf(0L) }
                        try { com.mangareader.DiagTracer.mark(ctx, "readerView mode=$mode vert=$vertical pages=${displayPages.size} start=$safeCur trim=${config.isTrimWhiteBorder} tile=${config.tileModeEnabled}") } catch (_: Exception) {}
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
                            fitScreen = config.fitScreen,
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
                            tileModeEnabled = config.tileModeEnabled,
                            verticalPager = verticalPagerEffective,
                            flipAnimationEnabled = config.isFlipAnimation,
                            pageCurlAnimationEnabled = config.isPageCurlAnimation,
                            curlContentAreaOnly = config.isCurlContentAreaOnly,
                            curlBackContentAlpha = config.curlBackContentAlpha,
                            curlShadowAlpha = config.curlShadowAlpha,
                            einkDither = config.isEinkDither,
                            einkDitherAlgorithm = config.einkDitherAlgorithm,
                            einkDitherLevels = config.einkDitherLevels,
                            einkEndpointPassthrough = config.einkEndpointPassthrough,
                            brightness = config.brightness,
                            screenBrightness = config.screenBrightness,
                            contrast = config.contrast,
                            gamma = config.gamma,
                            textureEnabled = config.textureEnabled,
                            texturePresetId = config.texturePresetId,
                            textureConcentration = config.textureConcentration,

                            remoteSupply = smbSession,
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
                                    android.util.Log.d("ReaderProgress", "onPageChanged SKIP newCur=$newCur isVert=$isVert pend=${wv?.isSetupScrollPending} win=${wv?.isSettleWindowActive}")
                                    // ★ 诊断取证：SKIP 状态翻转时记录（连续 SKIP 节流 5s）
                                    val nowMs = System.currentTimeMillis()
                                    if (nowMs - lastSkipMarkAt > 5000) {
                                        lastSkipMarkAt = nowMs
                                        try { com.mangareader.DiagTracer.mark(ctx, "pageCb SKIP newCur=$newCur pend=${wv?.isSetupScrollPending} win=${wv?.isSettleWindowActive}") } catch (_: Exception) {}
                                    }
                                } else {
                                    android.util.Log.d("ReaderProgress", "onPageChanged EXEC newCur=$newCur isVert=$isVert")
                                    try { com.mangareader.DiagTracer.mark(ctx, "pageCb EXEC cur=$newCur") } catch (_: Exception) {}
                                    cur = newCur; save()
                                }
                            },
                            onToggleUi = ::toggleUi,
                            onChapterEnd = handleChapterEnd,
                            onPageCountChanged = { count ->
                                expandedPageCount = count
                                try { com.mangareader.DiagTracer.mark(ctx, "pageCount=$count") } catch (_: Exception) {}
                            },

                            onViewReady = { w, p ->
                                webtoonViewRef = w; pagerViewRef = p
                                activePagerRef = p as? com.mangareader.native.PagerReaderView
                                activeWebtoonRef = w as? com.mangareader.native.WebtoonReaderView
                                // ★ §34.5：onAnimDetected 旧钩子废弃——动图检测由 enterReader 末尾批量 peek 取代。
                                //   本 hook 只留作 dump 日志（peek 流程的兜底信号），不再触发任何对话框。
                                (w as? com.mangareader.native.WebtoonReaderView)?.let { wv ->
                                    wv.onAnimDetected = { count ->
                                    }
                                    // ★ N1：条漫到底哨兵接入无缝续读
                                    wv.onChapterEnd = handleChapterEnd
                                }
                                // ★ §24 二代：PagerReaderView 接动图播放，hook 同 wait——但翻页模式下不需"切翻页"对话框（已在翻页中）
                                (p as? com.mangareader.native.PagerReaderView)?.let { pv ->
                                    pv.onAnimDetected = { count ->
                                    }
                                }
                                (p as? com.mangareader.native.PagerReaderView)?.let {
                                    it.flipAnimationEnabled = config.isFlipAnimation
                                    it.imageMarginH = config.imageMarginH
                                    // ★ N1：翻页/双页/RTL 末端判定统一走共享章末处理器
                                    it.onChapterEnd = handleChapterEnd
                                    it.onPageLongPress = { pageIdx ->
                                        // ★ 长按保存图片开关（阅读设置，默认关）：关闭时长按静默不响应，避免翻页误触
                                        if (prefs.getBoolean("long_press_save", false) &&
                                            pageIdx in displayPages.indices
                                        ) savePageAt(pageIdx)
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
                                        // ★ 读节拍器：调度开始/结束/出结果都会实时重组此指示器
                                        val srTick = com.mangareader.sr.SrUiState.srTick.intValue
                                        val computing = com.mangareader.sr.SrPipeline.computing(cur)
                                        val done = !computing && com.mangareader.sr.SrPipeline.peekSr(cur) != null
                                        when {
                                            computing -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(12.dp),
                                                    color = Color.White,
                                                    strokeWidth = 1.5.dp,
                                                )
                                                Text("画质优化中", color = Color.White, fontSize = 11.sp)
                                            }
                                            done -> {
                                                Icon(Icons.Default.Check, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(12.dp))
                                                Text("优化完成", color = Color.White, fontSize = 11.sp)
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
                items.isNotEmpty() -> {
                    ItemGrid(items, engine, MaterialTheme.colorScheme.surface, progressForToc, { openItem(it) }, Modifier.fillMaxSize(),
                        navStack = navStack,
                        subVolCovers = subVolCoverMap,
                        onItemLongPress = onSubItemLongPress,
                    )
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有内容") }
                }
                // 翻页闪烁效果
                com.mangareader.ui.FlipFlashOverlay(
                    currentPage = cur,
                    enabled = config.isFlipFlashEnabled,
                    durationMs = config.flipFlashDuration,
                    intervalPages = config.flipFlashInterval,
                    color = config.flipFlashColor,
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

    if (smbUnsupportedInfo != null) {
        val info = smbUnsupportedInfo!!
        AlertDialog(
            onDismissRequest = { if (!smbDownloadBusy) { smbUnsupportedInfo = null; viewing = false; loading = false } },
            title = { Text("需要全量下载") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("此格式（mobi / azw3 等）需要完整下载到本地后才能查看。")
                    if (smbDownloadBusy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在下载…", fontSize = 13.sp, color = Color(0xFF888888))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { downloadRemoteUnsupported(info.first, info.second) },
                    enabled = !smbDownloadBusy,
                ) { Text(if (smbDownloadBusy) "下载中…" else "下载") }
            },
            dismissButton = {
                TextButton(
                    onClick = { smbUnsupportedInfo = null; viewing = false; loading = false },
                    enabled = !smbDownloadBusy,
                ) { Text("取消") }
            },
        )
    }

    if (showRedownloadDialog) {
        val rb = redownloadBook
        AlertDialog(
            onDismissRequest = { showRedownloadDialog = false; redownloadBook = null },
            title = { Text("源文件可能丢失") },
            text = { Text("本地文件不存在。是否恢复为远程书籍？\n恢复后可重新下载或直接在线阅读。") },
            confirmButton = {
                TextButton(onClick = {
                    showRedownloadDialog = false
                    if (rb != null) {
                        scope.launch {
                            val origPath = rb.originalPath ?: return@launch
                            val source = if (origPath.startsWith("smb:")) "smb" else "webdav"
                            val ext = origPath.substringAfterLast('.', "").lowercase()
                            withContext(Dispatchers.IO) {
                                dao.updateBook(rb.copy(filePath = origPath, source = source, fileType = ext, originalPath = null, coverPath = null))
                            }
                            val refreshed = withContext(Dispatchers.IO) { dao.getBookById(rb.id) }
                            book = refreshed
                            redownloadBook = null
                            if (refreshed != null) enterReader(refreshed.filePath)
                        }
                    }
                }) { Text("恢复并重新打开") }
            },
            dismissButton = { TextButton(onClick = { showRedownloadDialog = false; redownloadBook = null }) { Text("取消") } },
        )
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                passwordDeferred?.complete("")
                showPasswordDialog = false
            },
            title = { Text(if (passwordError) "密码错误，请重新输入" else "请输入密码") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pwd = passwordInput
                    passwordInput = ""
                    passwordDeferred?.complete(pwd)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    passwordInput = ""
                    passwordDeferred?.complete("")
                    showPasswordDialog = false
                }) { Text("取消") }
            },
        )
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

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newTitle = renameInput.trim()
                    if (newTitle.isNotEmpty() && newTitle != book?.title) {
                        book?.let { b ->
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.updateBookTitle(b.id, newTitle) }
                                book = b.copy(title = newTitle)
                                showRename = false
                            }
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }



    if (showSrPanel) {
        BackHandler { showSrPanel = false }
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
        com.mangareader.ui.SrQueueScreen(onBack = { showSrQueue = false }, onOpenBook = onOpenBook)
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        com.mangareader.ui.ReaderSettingsSheet(
            config = config,
            mode = mode,
            bookId = bookId,

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

    // ★ 子卷自定义封面批量查询（IO 一次列 covers/ 目录算完，防每卷一次 listFiles 的 O(n²)）。
    //   不绑定 showToc：打开父书的首屏满屏网格（ItemGrid）同样要显示覆盖封面。
    LaunchedEffect(navStack, subCoverRev, items, tocItems) {
        val bk = book
        if (bk == null || bk.source != "local") {
            subVolCoverMap = emptyMap(); return@LaunchedEffect
        }
        val src = if (viewing && tocItems.isNotEmpty()) tocItems else items
        if (src.isEmpty()) {
            subVolCoverMap = emptyMap(); return@LaunchedEffect
        }
        subVolCoverMap = withContext(Dispatchers.IO) {
            engine.subVolCoverDataBatch(src.mapNotNull { tocItemChPath(navStack, it) })
        }
    }

    if (showToc) {
        val displayItems = if (viewing && tocItems.isNotEmpty()) tocItems else items
        ReaderTocSheet(
            displayItems = displayItems,
            currentNavStack = navStack,
            viewing = viewing,
            progress = progressForToc,
            bookId = book?.id ?: 0L,
            bookmarks = bookmarks,
            onDismiss = { showToc = false },
            onPageBookmarkClick = { bm -> showToc = false; jumpToBookmark(bm) },
            onPageBookmarkDelete = { bm ->
                scope.launch {
                    withContext(Dispatchers.IO) { dao.deleteBookmark(bm.bookId, bm.chapterPath, bm.page) }
                    bookmarkTick++
                    android.widget.Toast.makeText(ctx, "已删除书签", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onChapterNavigate = { chapterPath ->
                // 保留书根路径 + 新章节路径，不丢弃整个 navStack
                // ★ F2.2：d: 书书根取 navStack 首位（d: 栈首即书根）
                val bookRoot = navStack.firstOrNull { it.startsWith("z:") }
                    ?: navStack.firstOrNull()?.takeIf { it.startsWith("d:") }
                    ?: chapterPath
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
            onItemOpen = { openItem(it) },
            pageCount = if (expandedPageCount > 0) expandedPageCount else displayPages.size,
            currentPage = cur,
            onPageClick = { idx -> showToc = false; cur = idx },
            thumbLoader = if (smbSession != null) {
                { idx -> smbSession?.loadPageRaw(idx)?.let { decodeThumbBytes(it) } }
            } else if (book?.filePath?.startsWith("smb:") != true && book?.filePath?.startsWith("webdav:") != true && book?.filePath?.startsWith("komga:") != true && book?.filePath?.startsWith("opds:") != true) {
                val treeStr = navStack.lastOrNull() ?: book?.filePath ?: ""
                { idx ->
                    if (idx < pageList.size) {
                        engine.loadPageRaw(pageList[idx], treeStr)?.let { decodeThumbBytes(it) }
                    } else null
                }
            } else null,
            subVolCovers = subVolCoverMap,
            onItemLongPress = { itm ->
                if (book?.source == "local") {
                    tocItemChPath(navStack, itm)?.let { cp -> subVolMenu = itm to cp }
                }
            },
        )
    }

    // ★ 子卷封面长按菜单（更换 / 恢复默认）
    subVolMenu?.let { (itm, cp) ->
        val hasCustom = subVolCoverMap[cp] != null
        AlertDialog(
            onDismissRequest = { subVolMenu = null },
            title = { Text(itm.name) },
            text = { Text("为该章节设置自定义封面？封面会显示在目录列表中，原始文件不受影响。") },
            confirmButton = {
                TextButton(onClick = { subVolEdit = subVolMenu; subVolMenu = null }) { Text("更换封面") }
            },
            dismissButton = {
                Row {
                    if (hasCustom) {
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { subVolCoverMap[cp]?.let { engine.deleteCustomCoverByPath(it.first) } }
                                coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
                                subCoverRev++
                                subVolMenu = null
                                android.widget.Toast.makeText(ctx, "已恢复默认封面", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("恢复默认封面", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { subVolMenu = null }) { Text("取消") }
                }
            },
        )
    }

    // ★ 子卷封面编辑器：复用 CoverEditDialog，book 仅用 title/source/filePath——
    //   伪 Book 以 chPath 为 filePath，编辑器内 children(chPath) 下钻选图、
    //   saveCustomCoverBitmap(chPath) 落盘为 custom_<md5(chPath)>_<ts>.jpg，与 subVolCoverDataBatch 枚举一致
    subVolEdit?.let { (itm, cp) ->
        val bk = book
        if (bk != null) {
            CoverEditDialog(
                book = bk.copy(title = itm.name, filePath = cp),
                engine = engine, ctx = ctx,
                onResult = { newPath ->
                    scope.launch {
                        coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
                        android.widget.Toast.makeText(ctx, if (newPath != null) "封面已更新" else "封面保存失败", android.widget.Toast.LENGTH_SHORT).show()
                        subCoverRev++
                        subVolEdit = null
                    }
                },
                onDismiss = { subVolEdit = null },
            )
        }
    }

    if (showBookmarkNoteDialog) {
        val b = book
        AlertDialog(
            onDismissRequest = {
                showBookmarkNoteDialog = false
                if (b != null && viewing && pageList.isNotEmpty() && b.id > 0) {
                    val chPath = navStack.lastOrNull() ?: b.filePath
                    scope.launch(Dispatchers.IO) {
                        dao.insertBookmark(com.mangareader.data.Bookmark(bookId = b.id, chapterPath = chPath, page = cur))
                        bookmarkTick++
                    }
                    android.widget.Toast.makeText(ctx, "已添加书签（第 ${cur + 1} 页）", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            title = { Text("书签备注") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = pendingBookmarkNote,
                    onValueChange = { pendingBookmarkNote = it },
                    label = { Text("备注（可选）") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBookmarkNoteDialog = false
                    if (b != null && viewing && pageList.isNotEmpty() && b.id > 0) {
                        val chPath = navStack.lastOrNull() ?: b.filePath
                        val note = pendingBookmarkNote.ifEmpty { null }
                        scope.launch(Dispatchers.IO) {
                            dao.insertBookmark(com.mangareader.data.Bookmark(bookId = b.id, chapterPath = chPath, page = cur, note = note))
                            bookmarkTick++
                        }
                        android.widget.Toast.makeText(ctx, "已添加书签（第 ${cur + 1} 页）", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showBookmarkNoteDialog = false }) { Text("取消") }
            },
        )
    }
}

private fun skipTwoSegments(rest: String): String? {
    val first = rest.indexOf('|')
    if (first == -1) return null
    val second = rest.indexOf('|', first + 1)
    if (second == -1) return null
    return rest.substring(second + 1)
}

private fun continuePathTail(p: String): String = when {
    p.startsWith("z:") -> {
        val inner = p.removePrefix("z:").removePrefix("a:")
        val subIdx = inner.indexOf("//")
        if (subIdx == -1) {
            skipTwoSegments(inner) ?: p
        } else {
            val archivePart = inner.substring(0, subIdx)
            val subPart = inner.substring(subIdx)
            skipTwoSegments(archivePart)?.let { it + subPart } ?: p
        }
    }
    p.startsWith("a:") || p.startsWith("d:") -> {
        val rest = if (p.startsWith("a:")) p.removePrefix("a:") else p.removePrefix("d:")
        skipTwoSegments(rest) ?: p
    }
    else -> p
}

private fun continueDisplayName(p: String): String = when {
    p.startsWith("z:") -> {
        val inner = p.removePrefix("z:")
        val subIdx = inner.indexOf("//")
        if (subIdx == -1) {
            inner.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').substringAfterLast('%')
        } else {
            val subPath = inner.substring(subIdx + 2).trimEnd('/', '\\')
            if (subPath.isNotEmpty()) {
                subPath.substringAfterLast('/').substringAfterLast('\\')
            } else {
                inner.substring(0, subIdx).trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').substringAfterLast('%')
            }
        }
    }
    p.startsWith("a:") || p.startsWith("d:") -> {
        val doc = p.split('|').getOrNull(2)
        if (doc != null) {
            doc.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').substringAfterLast('%')
        } else {
            p.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        }
    }
    else -> p.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
}

private fun computeInBookContinueTarget(currentPath: String?, rows: List<com.mangareader.data.ReadingProgress>): com.mangareader.data.ReadingProgress? {
    if (currentPath == null) return null
    val curTail = continuePathTail(currentPath)
    val base = if (curTail.endsWith("/") || curTail.endsWith("\\")) curTail else "$curTail/"
    return rows.filter { r ->
        val t = continuePathTail(r.path)
        t != curTail && t.startsWith(base)
    }.maxByOrNull { it.updatedAt }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ItemGrid(
    items: List<ArchiveEngine.Item>,
    engine: ArchiveEngine,
    bgColor: Color,
    progress: List<ReadingProgress>,
    tap: (ArchiveEngine.Item) -> Unit,
    mod: Modifier,
    navStack: List<String> = emptyList(),
    subVolCovers: Map<String, Pair<String, Long>> = emptyMap(),
    onItemLongPress: (ArchiveEngine.Item) -> Unit = {},
) {
    val ctx = LocalContext.current
    val configuration = LocalConfiguration.current
    var sortAsc by remember { mutableStateOf(true) }
    val portraitCols by com.hippo.ehviewer.Settings.thumbColumnsPortrait.valueFlow().collectAsState(initial = com.hippo.ehviewer.Settings.thumbColumnsPortrait.value)
    val landscapeCols by com.hippo.ehviewer.Settings.thumbColumnsLandscape.valueFlow().collectAsState(initial = com.hippo.ehviewer.Settings.thumbColumnsLandscape.value)
    val resolvedCols = LayoutColumnsResolver.currentValue(configuration.orientation, portraitCols, landscapeCols)
    val gridCols = LayoutColumnsResolver.toGridCols(resolvedCols)
    val sorted = remember(items, sortAsc) {
        val base = items.sortedWith(compareByDescending<ArchiveEngine.Item> { it.dir }.thenComparing({ it.name }, NaturalComparator))
        if (sortAsc) base else base.reversed()
    }
    Column(mod) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${sorted.size} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { sortAsc = !sortAsc }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (sortAsc) "正序" else "倒序", fontSize = 12.sp)
            }
        }
        LazyVerticalGrid(columns = gridCols, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(sorted, key = { i, e -> "${e.auth}|${e.doc}_$i" }) { _, e ->
                ItemCard(e, engine, bgColor, progress, { tap(e) }, navStack, subVolCovers) { onItemLongPress(e) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(
    item: ArchiveEngine.Item,
    engine: ArchiveEngine,
    bgColor: Color,
    progress: List<ReadingProgress>,
    tap: () -> Unit,
    navStack: List<String>,
    subVolCovers: Map<String, Pair<String, Long>>,
    onLongPress: () -> Unit,
) {
    val ctx = LocalContext.current
    val status = remember(progress, item.name) { com.mangareader.ui.statusForItem(item, progress) }
    // ★ 子卷自定义封面覆盖（与 ReaderTocSheet 目录 Tab 同款）：有覆盖走 CoverFetcher custom: 分支，
    //   lastModified 注入缓存 key（同名覆盖写后旧缓存不命中）
    val chPath = tocItemChPath(navStack, item)
    val subCover = chPath?.let { subVolCovers[it] }
    val coverModel = remember(item.auth, item.tree, item.doc, item.subPath, subCover) {
        coil3.request.ImageRequest.Builder(ctx)
            .data(if (subCover != null) com.mangareader.viewer.CoverData(subCover.first, dynOn = false) else item)
            .apply {
                if (subCover != null) {
                    memoryCacheKey("${subCover.first}|${subCover.second}")
                    diskCacheKey("${subCover.first}|${subCover.second}")
                }
            }
            .build()
    }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = tap, onLongClick = onLongPress).shadow(4.dp, RoundedCornerShape(12.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(bgColor.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {

            coil3.compose.AsyncImage(
                model = coverModel,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

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


