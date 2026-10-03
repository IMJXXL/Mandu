package com.mangareader.ui

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.sr.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ANIM_DURATION = 300
private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val SIDE_PANEL_SHAPE = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)

/**
 * 阅读器底栏【画质增强】面板。
 *
 * 布局（2026-10-03 双 Tab 定稿，用户拍板）：
 *  【本次阅读】实时增强全套：作用域+重算本页（诚实文案）、启用开关、档位（选中显说明，唯一事实源
 *             SRModel.description）、降噪、黑白增强（原"黑白漫画优化"并入为子项，不再独立区块）、
 *             处理引擎、高级与缓存（测速/实时缓存上限/清实时缓存）。
 *  【离线增强】本书状态卡（已增强 N 章 M 页/处理中 X/Y 页）、加入增强队列（含未读勾选）、
 *             不支持格式的显式说明（MOBI/AZW3/远程书）、增强产物管理（用量/上限/单书清理）、队列入口。
 *
 * 关键机制（2026-10-03 页级持久产物）：
 *  - 实时增强成功即永久落盘（SRDiskCache.artifacts，键=完整指纹），任何作用域下最高优先显示；
 *  - 产物总量受 SrSettings.artifactCacheLimitMb（默认 1000MB）控制，超限自动清最旧；
 *  - "重算本页"点击时刻同步判定所有可判定分支，按真实情况返回文案（不再空报"已开始"）。
 * 交互约定（不变）：
 *  - 面板里所有功能任何时候都可见、都能设置；只是"作用域=关闭"时实时增强不生效；
 *  - 已离线增强/已有页级产物的内容无需任何开关，阅读时自动生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SrPanel(
    bookId: Long,
    bookTitle: String,
    onOpenQueue: () -> Unit,
    onDismiss: () -> Unit,
    bookPath: String? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (!SrCore.initialized) SrCore.init(ctx) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // ★ 1001 UI 审查 Phase3：横屏改右侧滑出全高抽屉；竖屏保持底部面板原样
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (visible) 0.3f else 0f))
            .clickable(interactionSource = null, indication = null, onClick = { visible = false; onDismiss() }),
        contentAlignment = if (landscape) Alignment.CenterEnd else Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = if (landscape) slideInHorizontally(tween(ANIM_DURATION)) { it } else slideInVertically(tween(ANIM_DURATION)) { it },
            exit = if (landscape) slideOutHorizontally(tween(ANIM_DURATION)) { it } else slideOutVertically(tween(ANIM_DURATION)) { it },
        ) {
            Surface(
                modifier = if (landscape) {
                    Modifier
                        .width(440.dp)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null, onClick = {})
                } else {
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .heightIn(max = with(LocalDensity.current) {
                            (LocalConfiguration.current.screenHeightDp * 0.70f).dp
                        })
                        .clickable(interactionSource = null, indication = null, onClick = {})
                },
                shape = if (landscape) SIDE_PANEL_SHAPE else SHEET_SHAPE,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                if (!SrCore.initialized) {
                    Text("初始化中…", Modifier.padding(24.dp))
                } else {
                    SrPanelContent(
                        bookId, bookTitle, ctx, scope, onOpenQueue, onDismiss, bookPath,
                    )
                }
            }
        }
    }
}

/** 本书离线增强状态快照（队列条目=进行中；产物 metadata=已完成） */
private data class BookOfflineStatus(
    val queuedChapters: Int,
    val queuedProcessed: Int,
    val queuedTotal: Int,
    val doneChapters: Int,
    val donePages: Int,
)

private suspend fun loadBookOfflineStatus(bookId: Long): BookOfflineStatus? =
    withContext(Dispatchers.IO) {
        if (bookId < 0) return@withContext null
        try {
            val queued = SrCore.queueStore.load().filter { it.bookId == bookId }
            val done = SrCore.diskCache.getCompletedBatchChapters().filter { it.bookId == bookId }
            BookOfflineStatus(
                queuedChapters = queued.size,
                queuedProcessed = queued.sumOf { it.processedPages },
                queuedTotal = queued.sumOf { it.totalPages },
                doneChapters = done.size,
                donePages = done.sumOf { it.pageCount },
            )
        } catch (_: Exception) {
            null
        }
    }

/** 离线增强能力判定（2026-10-03 v2）：ArchiveEngine 实际支持的全部格式都放开。
 *  ★ 扩展名双口径：书架 SAF 书是竖线格式（a:auth|tree|docId|zip），普通本地书是点号路径
 *    （/x/y.zip）——此前只按点号取扩展名，SAF 书全部误判为不支持 → 「加入增强队列」按钮
 *    集体消失（用户实测"基本都不行/没有加入队列"的根因）。
 *  ★ mobi/azw/azw3/pdb/prc 走 ArchiveEngine 的 libmobi 分支（listPages/loadPage 均支持），
 *    epub 即 zip 容器——此前把它们当"不支持"是错杀。 */
private fun offlineEnhanceSupported(bookPath: String?): Pair<Boolean, String?> {
    if (bookPath == null) return false to "本书信息缺失"
    if (bookPath.startsWith("smb:") || bookPath.startsWith("webdav:")) {
        return false to "网络来源的书暂不支持一键离线增强（可先下载到本地后使用）"
    }
    // ★ 格式白名单整体放开（用户拍板 2026-10-03）：库里能出现的书（文件夹/压缩包/PDF/
    //   mobi 系/epub…）一律允许入队；真读不了的格式在入队时枚举 0 页并给诚实提示。
    return true to null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SrPanelContent(
    bookId: Long,
    bookTitle: String,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenQueue: () -> Unit,
    onDismiss: () -> Unit,
    bookPath: String?,
) {
    val settings = SrCore.settings

    // ── 全部状态集中声明 ──
    var tab by remember { mutableStateOf(0) }
    var upscaleOn by remember { mutableStateOf(settings.upscaleEnabled) }
    var model by remember { mutableStateOf(settings.effectiveModel(bookId)) }
    var denoise by remember { mutableStateOf(settings.denoise) }
    var bwOn by remember { mutableStateOf(settings.bwEnabled) }
    var bwLevels by remember { mutableStateOf(settings.bwGrayLevels) }
    var bwDensity by remember { mutableStateOf(settings.bwDensityCorrection) }
    var preloadOv by remember { mutableStateOf(settings.preloadOverride) }
    var benchText by remember { mutableStateOf(SrCore.profileManager.describe()) }
    var benchRunning by remember { mutableStateOf(false) }
    var cacheText by remember { mutableStateOf(cacheUsageText()) }
    var cacheMbInput by remember { mutableStateOf(settings.realtimeCacheLimitMb.toString()) }
    var includeUnread by remember { mutableStateOf(false) }
    var bookStatus by remember { mutableStateOf<BookOfflineStatus?>(null) }
    var artifactText by remember { mutableStateOf("") }
    var artifactMbInput by remember { mutableStateOf(settings.artifactCacheLimitMb.toString()) }

    var engine by remember { mutableStateOf(settings.engine) }
    var npuProbed by remember { mutableStateOf(false) }
    var npuArch by remember { mutableStateOf(0) }
    var npuModelsReady by remember { mutableStateOf(false) }
    var npuTestOk by remember { mutableStateOf(settings.npuTestOk) }
    var npuBlocked by remember { mutableStateOf(settings.npuDeviceBlock) }
    var npuTesting by remember { mutableStateOf(false) }
    var npuTestNote by remember { mutableStateOf<String?>(null) }

    val scopeState = SrUiState.scope.intValue
    val engineAvailable = NativeLibraryStatus.isRealESRGANAvailable
    val vulkanOk = !SrCore.isVulkanMissing() || !model.requiresVulkan
    val usable = engineAvailable && vulkanOk
    val blockReason = when {
        !engineAvailable -> "画质增强组件加载失败，功能不可用"
        !vulkanOk -> "此设备不支持 Vulkan 图形加速，画质增强不可用"
        else -> null
    }
    val (offlineOk, offlineReason) = offlineEnhanceSupported(bookPath)

    // ★ NPU 派生值（2026-10-03 交互重做）：封锁=不可选；未实测=点选时自动实测；模型灰显用
    val npuTierOk = npuArch > 0 && NpuModelRegistry.supports(model, denoise, npuArch)
    val npuDeviceOk = npuProbed && npuArch > 0
    val npuChipOk = npuDeviceOk && npuModelsReady && npuTierOk && !npuBlocked
    var npuDownloading by remember { mutableStateOf(false) }
    var npuDlProgress by remember { mutableStateOf(0) }
    var npuInstallLaunched by remember { mutableStateOf(0) }

    // 本书离线状态：打开面板查一次；队列事件驱动刷新（3 秒节流）
    LaunchedEffect(bookId) { bookStatus = loadBookOfflineStatus(bookId) }
    LaunchedEffect(bookId) {
        var lastRefresh = 0L
        SrCore.queueProgress.collect {
            val now = System.currentTimeMillis()
            if (now - lastRefresh > 3000) {
                lastRefresh = now
                bookStatus = loadBookOfflineStatus(bookId)
            }
        }
    }
    // 产物用量：切到离线 Tab 时查一次（IO 走树统计，不在组合期做）
    LaunchedEffect(tab, bookId) {
        if (tab == 1 && bookId >= 0) {
            val (n, bytes) = withContext(Dispatchers.IO) { SrCore.diskCache.artifactUsage() }
            artifactText = "$n 张 / ${bytes / 1024 / 1024} MB（上限 ${settings.artifactCacheLimitMb} MB）"
        }
    }
    LaunchedEffect(Unit) {
        if (!QnnEngine.nativeLoaded) {
            npuProbed = true
            return@LaunchedEffect
        }
        val (arch, hasPkg) = withContext(Dispatchers.IO) {
            NpuModelRegistry.ensureScanned(ctx)
            val a = QnnEngine.probe(QnnEngine.libraryDir(ctx))
            a to NpuModelRegistry.hasPackageFor(a)
        }
        npuArch = arch
        npuModelsReady = hasPkg
        npuProbed = true
        // 诊断短码：a=HTP 代（0=未探到） m=模型包是否在位——下份日志可直接判断面板处于哪个状态
        runCatching { com.mangareader.DiagTracer.markStatic("sr: npu ui a=$arch m=$hasPkg") }
    }
    // 装好模型包回来后自动重扫（2 秒一查，最多 60 秒）
    LaunchedEffect(npuInstallLaunched) {
        if (npuInstallLaunched > 0) {
            repeat(30) {
                kotlinx.coroutines.delay(2000)
                withContext(Dispatchers.IO) { NpuModelRegistry.refresh(ctx) }
                if (NpuModelRegistry.hasPackageFor(npuArch)) {
                    npuModelsReady = true
                    return@LaunchedEffect
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 16.dp),
    ) {
        // drag handle 指示条
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(2.dp),
                ),
        )
        Text("画质增强", fontSize = 18.sp, fontWeight = FontWeight.Bold)

        // containerColor 透明：默认 surface 与面板容器色不一致，两个标签呈现突兀白底
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("本次阅读") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("离线增强") })
        }

        Box(Modifier.weight(1f)) {
            val scroll0 = rememberScrollState()
            val scroll1 = rememberScrollState()
            when (tab) {
                // ══════════════ Tab 1：本次阅读（实时增强） ══════════════
                0 -> Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll0)
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // native 崩溃保护提示（上次被超分核心带走时显示）
                    if (SrCore.hadNativeCrash || SrCore.manager.forceCpu) {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "上次增强处理时出过错，已自动换成更稳的方式（慢一点但稳定）；" +
                                        "点「恢复加速」可试回快速模式，再出问题会自动退回来",
                                    Modifier.weight(1f),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                TextButton(onClick = {
                                    SrCore.clearForceCpu()
                                    SrCore.manager.resetSessionFuses()
                                    onDismiss()
                                    android.widget.Toast.makeText(ctx, "已恢复加速（GPU/NPU），若再崩溃会再次自动切 CPU", android.widget.Toast.LENGTH_LONG).show()
                                }) { Text("恢复加速", fontSize = 12.sp) }
                            }
                        }
                    }

                    Text("本次阅读处理哪些页", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (blockReason != null) {
                        Text(blockReason, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        listOf(
                            SrUiState.SCOPE_OFF to "关闭",
                            SrUiState.SCOPE_PAGE to "仅当前页",
                            SrUiState.SCOPE_BOOK to "当前整本",
                        ).forEach { (v, label) ->
                            FilterChip(
                                selected = scopeState == v,
                                onClick = {
                                    SrUiState.scope.intValue = v
                                    if (v == SrUiState.SCOPE_OFF) SrUiState.compareActive.value = false
                                    SrPipeline.onScopeChanged(v)
                                },
                                label = { Text(label, fontSize = 12.sp) },
                                enabled = usable || v == SrUiState.SCOPE_OFF,
                            )
                        }
                        // 重算本页：点击时刻同步判定所有可判定分支，按真实情况返回文案（SrPipeline.forceRetryCurrentPage）
                        OutlinedButton(
                            onClick = {
                                val msg = SrPipeline.forceRetryCurrentPage()
                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                            },
                            enabled = usable && scopeState != SrUiState.SCOPE_OFF,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("重算本页", fontSize = 12.sp)
                        }
                    }
                    Text(
                        "关闭时不处理页面，下面的设置可以先选好。已增强过的页（离线或自动保存的）会直接优先显示，不受开关影响。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("启用画质增强", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("AI 放大图片变清晰；关闭时仍可单跑黑白增强（原尺寸、毫秒级）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = upscaleOn,
                            onCheckedChange = {
                                upscaleOn = it; settings.upscaleEnabled = it
                                SrPipeline.onSettingsChanged()
                            },
                        )
                    }

                    Text("处理档位", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Column {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                            SRModel.entries.chunked(2).forEach { rowModels ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowModels.forEach { m ->
                                        FilterChip(
                                            selected = model == m,
                                            onClick = {
                                                model = m; settings.model = m
                                                scope.launch { SrCore.applySettings(bookId) }
                                            },
                                            modifier = Modifier.height(32.dp),
                                            label = { Text(m.displayName, fontSize = 12.sp) },
                                            enabled = NativeLibraryStatus.isModelAvailable(m) &&
                                                // ★ NPU 引擎下灰掉无 NPU 模型的档位（用户拍板 2026-10-03）
                                                (engine != SrEngine.NPU || !npuDeviceOk ||
                                                    NpuModelRegistry.supports(m, denoise, npuArch)),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    Text(
                        model.description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val denoiseCapable = model.denoiseCapable
                    Text(
                        if (denoiseCapable) "降噪强度（针对扫描噪点）"
                        else if (model.modelType == "lanczos") "降噪强度（无损放大档不支持降噪调节）"
                        else "降噪强度（动漫V3 档不支持降噪调节）",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = if (denoiseCapable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DenoiseLevel.entries.forEach { d ->
                            FilterChip(
                                selected = denoise == d,
                                onClick = {
                                    denoise = d; settings.denoise = d
                                    scope.launch { SrCore.applySettings(bookId) }
                                },
                                label = { Text(d.displayName, fontSize = 12.sp) },
                                enabled = denoiseCapable,
                            )
                        }
                    }

                    // ★ 黑白增强（原"黑白漫画优化"并入为子项，用户拍板 2026-10-03）
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("黑白增强（可单独开启）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("灰阶量化+网点密度校正；彩色页自动跳过；不开画质增强也能用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = bwOn,
                            onCheckedChange = {
                                bwOn = it; settings.bwEnabled = it
                                scope.launch { SrCore.applySettings(bookId) }
                                SrPipeline.onSettingsChanged()
                            },
                        )
                    }
                    if (bwOn) {
                        Text("灰阶级数", fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(8, 16, 32, 64).forEach { lv ->
                                FilterChip(
                                    selected = bwLevels == lv,
                                    onClick = {
                                        bwLevels = lv; settings.bwGrayLevels = lv
                                        scope.launch { SrCore.applySettings(bookId) }
                                        SrPipeline.onSettingsChanged()
                                    },
                                    label = { Text("$lv 级", fontSize = 12.sp) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("网点密度校正", Modifier.weight(1f), fontSize = 13.sp)
                            Switch(
                                checked = bwDensity,
                                onCheckedChange = {
                                    bwDensity = it; settings.bwDensityCorrection = it
                                    scope.launch { SrCore.applySettings(bookId) }
                                    SrPipeline.onSettingsChanged()
                                },
                            )
                        }
                    }

                    if (scopeState == SrUiState.SCOPE_BOOK) {
                        Text("提前加载页数（整本模式翻页更顺）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(-1 to "跟随设备", 1 to "1 页", 2 to "2 页", 3 to "3 页", 5 to "5 页").forEach { (v, label) ->
                                FilterChip(
                                    selected = preloadOv == v,
                                    onClick = {
                                        preloadOv = v; settings.preloadOverride = v
                                        SrCore.dispatcher.preloadWindow = if (v >= 0) v else SrCore.profileManager.getPreloadWindow()
                                    },
                                    label = { Text(label, fontSize = 12.sp) },
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 处理引擎（NPU 派生值已上移到状态区；2026-10-03 交互重做：
                    //  封锁灰掉 / 未实测点选即自动实测 / 无 NPU 模型的档位灰显 / 一键下载模型包）
                    Text("处理引擎", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(SrEngine.CPU, "CPU 稳妥", true),
                            Triple(SrEngine.GPU, "GPU 加速", !SrCore.isVulkanMissing()),
                            Triple(SrEngine.NPU, "NPU 极速", npuChipOk),
                        ).forEach { (e, label, enabled) ->
                            if (e == SrEngine.NPU && !npuDeviceOk) return@forEach // 非骁龙：不显示
                            FilterChip(
                                selected = engine == e,
                                onClick = {
                                    if (e == SrEngine.NPU && !npuTestOk && npuModelsReady) {
                                        // ★ 未实测：点选即自动实测——通过才真正选中（实测中 chip 灰掉）
                                        npuTesting = true
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                NpuTransportProbe.run(ctx)
                                            }
                                            npuTestOk = r == NpuTransportProbe.Result.PASS
                                            npuBlocked = settings.npuDeviceBlock
                                            npuTestNote = when (r) {
                                                NpuTransportProbe.Result.BUSY -> "正在处理页面，请稍后再试"
                                                NpuTransportProbe.Result.BLOCKED -> "本机被系统封锁：已保持 GPU/CPU"
                                                NpuTransportProbe.Result.NO_MODEL -> "未装模型包"
                                                NpuTransportProbe.Result.FAILED -> "实测未通过：可重试，不影响 GPU/CPU"
                                                else -> null
                                            }
                                            npuTesting = false
                                            if (r == NpuTransportProbe.Result.BLOCKED && settings.engine == SrEngine.NPU) {
                                                settings.engine = SrEngine.GPU
                                            }
                                            if (r == NpuTransportProbe.Result.PASS) {
                                                engine = SrEngine.NPU
                                                settings.engine = SrEngine.NPU
                                                scope.launch { SrCore.applySettings(bookId) }
                                            }
                                        }
                                    } else {
                                        engine = e; settings.engine = e
                                        scope.launch { SrCore.applySettings(bookId) }
                                    }
                                },
                                label = {
                                    Text(
                                        when {
                                            // ★ 未实测/实测中给可见标记（技术宅走查：一点即跑基准的
                                            //   chip 与普通 chip 无视觉差，用户以为"点了没反应"）
                                            e == SrEngine.NPU && npuTesting -> "实测中…"
                                            e == SrEngine.NPU && npuModelsReady && npuTierOk && !npuBlocked && !npuTestOk -> "NPU 极速·未实测"
                                            else -> label
                                        },
                                        fontSize = 12.sp,
                                    )
                                },
                                enabled = enabled && (e != SrEngine.NPU || !npuTesting),
                            )
                        }
                    }
                    Text(
                        when (engine) {
                            SrEngine.CPU -> "所有手机可用，最稳但最慢；上次崩溃会自动回到这里"
                            SrEngine.GPU -> "交给显卡（Vulkan）处理，比 CPU 快数倍；发热明显时切回 CPU"
                            SrEngine.NPU ->
                                when {
                                    npuBlocked -> "本机被系统拦截（厂商封锁）：实际自动用 GPU/CPU；系统更新后可重测"
                                    npuTestOk -> "实测通过（v$npuArch）：最快最省电；个别页可能有轻微偏色"
                                    npuModelsReady && npuTierOk -> "模型包就绪（v$npuArch），尚未实测——不支持时会自动回 GPU/CPU"
                                    npuModelsReady -> "当前档位无 NPU 版（支持 CUGAN 2x 保守、动漫V3 2x 极速），自动用 GPU/CPU"
                                    else -> "NPU 模型包未就绪，装好后重新选择 NPU"
                                }
                        },
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ★ NPU 状态行常驻（用户画像走查一致最疼点：灰掉零解释）——探测完成后
                    //   无论当前选中什么引擎都显示一行，覆盖：未探到/封锁/未装包/档位不匹配/就绪
                    if (npuProbed) {
                        Text(
                            when {
                                !npuDeviceOk ->
                                    "NPU：本机未检测到可用的 NPU（非骁龙 8 系或探测失败），GPU/CPU 增强不受影响"
                                npuBlocked ->
                                    "NPU：本机被系统封锁（实测失败），实际自动用 GPU/CPU；系统更新后可重测"
                                !npuModelsReady ->
                                    "NPU：检测到 ${QnnEngine.archDisplayName(npuArch)}（v$npuArch），" +
                                        "下载模型包后 CUGAN 2x / 动漫V3 2x 档位可切 NPU 极速"
                                !npuTierOk ->
                                    "NPU：已装模型包（v$npuArch），但「${model.displayName}」没有 NPU 版——" +
                                        "切到 CUGAN 2x 或 动漫V3 2x 即可选 NPU 极速"
                                engine != SrEngine.NPU ->
                                    "NPU：就绪（v$npuArch），点上方「NPU 极速」即自动实测并启用"
                                else ->
                                    "NPU：当前档位（${model.displayName}）走 NPU（v$npuArch，${QnnEngine.archDisplayName(npuArch)}）"
                            },
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ★ 模型包管理行常驻（2026-10-03 反馈"下载 UI 看不到"：已装包后入口全消失）——
                    //   未装=应用内下载（官方→加速线路自动回退）+浏览器直下；已装=状态+浏览器入口
                    if (npuDeviceOk) {
                        if (!npuModelsReady) {
                            OutlinedButton(
                                onClick = {
                                    npuDownloading = true
                                    scope.launch {
                                        val f = NpuModelDownload.download(ctx, npuArch) { pr -> npuDlProgress = pr }
                                        npuDownloading = false
                                        if (f == null) {
                                            android.widget.Toast.makeText(
                                                ctx,
                                                "应用内下载失败：可用下方浏览器下载，或稍后重试",
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        } else {
                                            when (NpuModelDownload.install(ctx, f)) {
                                                NpuModelDownload.InstallResult.NEED_PERMISSION ->
                                                    android.widget.Toast.makeText(ctx, "请在打开的设置里允许「安装未知应用」，再回来点一次下载", android.widget.Toast.LENGTH_LONG).show()
                                                NpuModelDownload.InstallResult.LAUNCHED -> {
                                                    android.widget.Toast.makeText(ctx, "请在安装界面确认安装模型包", android.widget.Toast.LENGTH_LONG).show()
                                                    npuInstallLaunched++
                                                }
                                                else -> android.widget.Toast.makeText(ctx, "安装界面拉起失败", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                enabled = !npuDownloading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (npuDownloading) "下载中 $npuDlProgress%"
                                    else "下载 NPU 模型包（v$npuArch，约 11MB · 自动换加速线路）",
                                    fontSize = 12.sp,
                                )
                            }
                            Text(
                                "应用内下载失败？用浏览器下载后，在通知栏点开 APK 手动安装：",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                NpuModelDownload.browserUrls(npuArch).forEach { (label, url) ->
                                    TextButton(onClick = { NpuModelDownload.openInBrowser(ctx, url) }) {
                                        Text(label, fontSize = 12.sp)
                                    }
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "NPU 模型包：已安装（v$npuArch）",
                                    Modifier.weight(1f),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { NpuModelDownload.openInBrowser(ctx, NpuModelDownload.RELEASE_PAGE_URL) }) {
                                    Text("重下/换包 · 对照表", fontSize = 12.sp)
                                }
                            }
                        }
                        if (engine == SrEngine.NPU && npuModelsReady) {
                            OutlinedButton(
                                onClick = {
                                    npuTesting = true
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            NpuTransportProbe.run(ctx)
                                        }
                                        npuTestOk = r == NpuTransportProbe.Result.PASS
                                        npuBlocked = settings.npuDeviceBlock
                                        npuTestNote = if (r == NpuTransportProbe.Result.BUSY) "正在处理页面，请稍后再测" else null
                                        npuTesting = false
                                    }
                                },
                                enabled = !npuTesting,
                            ) {
                                Text(
                                    when {
                                        npuTesting -> "正在实测…"
                                        npuTestOk -> "重新测试 NPU"
                                        else -> "测试 NPU"
                                    },
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        // ★ 实测结果落字不随选中态消失（技术宅走查：失败原因必须写在面板里，
                        //   不能只回退——封锁/失败时引擎已切回 GPU，重测按钮块根本不渲染）
                        npuTestNote?.let {
                            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    HorizontalDivider()

                    // 高级与缓存
                    Text("高级与缓存", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("设备档位：$benchText", fontSize = 13.sp)
                            Text("缓存：$cacheText", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            enabled = !benchRunning && usable,
                            onClick = {
                                benchRunning = true
                                scope.launch {
                                    try {
                                        val r = SrBenchmark(SrCore.manager).run(ctx)
                                        SrCore.profileManager.saveResult(r)
                                        benchText = SrCore.profileManager.describe()
                                        SrCore.dispatcher.preloadWindow = SrCore.effectivePreloadWindow()
                                    } catch (_: Exception) {
                                        benchText = "测试失败"
                                    }
                                    benchRunning = false
                                }
                            },
                        ) { Text(if (benchRunning) "测试中…" else "测速") }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("实时缓存上限", Modifier.weight(1f), fontSize = 14.sp)
                        OutlinedTextField(
                            value = cacheMbInput,
                            onValueChange = { s ->
                                val digits = s.filter { it.isDigit() }.take(4)
                                val v = digits.toIntOrNull()
                                if (v != null && v > 4096) {
                                    cacheMbInput = "4096"
                                    settings.realtimeCacheLimitMb = 4096
                                } else {
                                    cacheMbInput = digits
                                    if (v != null && v >= 16) settings.realtimeCacheLimitMb = v
                                }
                            },
                            modifier = Modifier.width(120.dp),
                            suffix = { Text("MB") },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        )
                    }
                    Text(
                        "实时看图时临时算的增强图缓存，超上限自动清最旧（16–4096 MB，默认 1000）；自动保存的产物不受此限",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            SrCore.dispatcher.clearCache()
                            cacheText = cacheUsageText()
                            android.widget.Toast.makeText(ctx, "实时缓存已清空（自动保存产物保留）", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("清空实时缓存（不动自动保存产物）", color = MaterialTheme.colorScheme.error) }
                }

                // ══════════════ Tab 2：离线增强 ══════════════
                1 -> Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll1)
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (bookId < 0) {
                        // book 为 null（bookId=-1）：书未关联书架条目。此前这里整体空白，
                        // 用户看到"离线 Tab 什么都没有"（mobi 实测踩中）。留诊断短码定位路径。
                        runCatching {
                            com.mangareader.DiagTracer.markStatic("sr: panel nobook")
                        }
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Text(
                                "本书未关联到书架条目。离线增强与产物管理不可用；" +
                                    "本次阅读（实时增强）不受影响，可到上一页使用。",
                                Modifier.padding(12.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (bookId >= 0) {
                        val st = bookStatus
                        Card(
                            onClick = onOpenQueue,
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "本书离线增强",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        when {
                                            st == null -> "查看本书已增强的章节与页数"
                                            st.queuedChapters > 0 && st.doneChapters > 0 ->
                                                "已增强 ${st.doneChapters} 章 · ${st.donePages} 页；处理中 ${st.queuedProcessed}/${st.queuedTotal} 页"
                                            st.queuedChapters > 0 ->
                                                "处理中：${st.queuedProcessed}/${st.queuedTotal} 页（空闲时自动继续）"
                                            st.doneChapters > 0 ->
                                                "已增强 ${st.doneChapters} 章 · ${st.donePages} 页，阅读时自动生效"
                                            else -> "本书还没有离线增强产物"
                                        },
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("队列 ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        if (offlineOk) {
                            Text("离线增强本书", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "提前把整本书增强存进手机：阅读零等待、结果永久生效，空闲时后台自动跑（通知栏有进度）。" +
                                    "离线的档位/降噪/黑白/引擎就是「本次阅读」页里选的那套——改好后重新加入队列即可按新配置重算。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = includeUnread, onCheckedChange = { includeUnread = it })
                                Text(
                                    "包含未读章节（整本跑完，占用更多存储）",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    SrBatchRunner.enqueueBook(ctx, bookId, bookTitle, includeUnread) { r ->
                                        (ctx as? android.app.Activity)?.runOnUiThread {
                                            android.widget.Toast.makeText(
                                                ctx,
                                                when {
                                                    r.chapters > 0 -> "已加入队列：${r.chapters} 章 ${r.pages} 页，空闲时自动逐章处理（通知栏可看进度）"
                                                    else -> "没有找到可处理的页面——这本书可能无法离线枚举；若阅读正常请导出日志反馈"
                                                },
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                        scope.launch { bookStatus = loadBookOfflineStatus(bookId) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("加入增强队列") }
                        } else {
                            // ★ 显式能力边界（用户拍板 2026-10-03）：不支持的格式不再"神秘消失"
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            ) {
                                Text(
                                    "离线增强本书\n$offlineReason",
                                    Modifier.padding(12.dp),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        TextButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth()) {
                            Text("查看增强队列与缓存管理")
                        }

                        HorizontalDivider()

                        // 增强产物管理（三件套：上限 + 自动清最旧 + 单书清理）
                        Text("增强产物（自动保存）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "实时增强过的页会自动永久保存，之后任何作用域下都优先显示增强图；" +
                                "改档位/降噪/引擎后，重新加入队列会按新配置重算并覆盖。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("本书及全书库已存产物：$artifactText", fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("产物总量上限", Modifier.weight(1f), fontSize = 14.sp)
                            OutlinedTextField(
                                value = artifactMbInput,
                                onValueChange = { s ->
                                    val digits = s.filter { it.isDigit() }.take(4)
                                    val v = digits.toIntOrNull()
                                    if (v != null && v > 4096) {
                                        artifactMbInput = "4096"
                                        settings.artifactCacheLimitMb = 4096
                                    } else {
                                        artifactMbInput = digits
                                        if (v != null && v >= 16) settings.artifactCacheLimitMb = v
                                    }
                                },
                                modifier = Modifier.width(120.dp),
                                suffix = { Text("MB") },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            )
                        }
                        Text(
                            "超上限自动清最旧的产物（16–4096 MB，默认 1000）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                SrCore.diskCache.removeArtifacts(bookId)
                                scope.launch {
                                    val (n, bytes) = withContext(Dispatchers.IO) { SrCore.diskCache.artifactUsage() }
                                    artifactText = "$n 张 / ${bytes / 1024 / 1024} MB（上限 ${settings.artifactCacheLimitMb} MB）"
                                }
                                android.widget.Toast.makeText(ctx, "已清除本书自动保存产物", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("清除本书自动保存产物", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

private fun cacheUsageText(): String {
    val (rc, rb) = SrCore.diskCache.getRealtimeUsage()
    val (bc, bb) = SrCore.diskCache.getBatchUsage()
    return "实时 $rc 张/${rb / 1024 / 1024}MB · 离线 $bc 张/${bb / 1024 / 1024}MB"
}
