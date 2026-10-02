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
 * 布局（2026-10-03 四视角重构，自上而下）：
 *  ① 本书离线增强——状态卡（已增强 N 章 M 页 / 处理中 X/Y 页，点击进队列页）+「加入增强队列」；
 *     原先该入口埋在面板 60% 深处（用户以为被删了），现提为第一区块。
 *  ② 本次阅读（实时增强）——作用域、启用开关、档位（选中即显示一句话说明）、降噪、整本预载。
 *  ③ 黑白漫画优化——可单独开启。
 *  ④ 处理引擎——CPU/GPU/NPU 三选一，NPU 状态收敛为最多两行。
 *  ⑤ 高级与缓存——测速、实时缓存上限、清空实时缓存。
 * 交互约定（不变）：
 *  - 面板里所有功能任何时候都可见、都能设置（不因作用域关闭而隐藏）；只是"作用域=关闭"时它们不生效；
 *  - 已离线增强的内容无需任何开关，阅读时自动生效；
 *  - 档位说明的唯一事实源在 SRModel.description，面板底部不再维护大段说明文字。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SrPanel(bookId: Long, bookTitle: String, onOpenQueue: () -> Unit, onDismiss: () -> Unit) {
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
                        bookId, bookTitle, ctx, scope, onOpenQueue, onDismiss,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SrPanelContent(
    bookId: Long,
    bookTitle: String,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = SrCore.settings

    // ── 全部状态集中声明（原先散落在 UI 中段，难维护）──
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
    // （原"自动选最快模型"功能 2026-08-28 已下线：手动选即可）
    var cacheMbInput by remember { mutableStateOf(settings.realtimeCacheLimitMb.toString()) }
    var includeUnread by remember { mutableStateOf(false) }
    var bookStatus by remember { mutableStateOf<BookOfflineStatus?>(null) }

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

    // 本书离线状态：打开面板查一次；队列页事件驱动刷新（3 秒节流，避免每页都全量扫产物目录）
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
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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

        // native 崩溃保护提示（上次被超分核心带走时显示）
        if (SrCore.hadNativeCrash || SrCore.manager.forceCpu) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "上次画质增强在此设备上崩溃过，已自动切换 CPU 安全模式（慢一点但稳定）",
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

        // ════ ① 离线增强本书（第一区块：状态一眼可见，加入队列一键直达）════
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
            Text("离线增强本书", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "提前把整本书增强存进手机：阅读零等待、结果永久生效，空闲时后台自动跑（通知栏有进度）。" +
                    "改档位/降噪后重进队列即可按新档重算。",
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
                                    else -> "本书已在队列中"
                                },
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                        scope.launch { bookStatus = loadBookOfflineStatus(bookId) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("加入增强队列") }
        }

        HorizontalDivider()

        // ════ ② 本次阅读（实时增强）════
        Text("本次阅读（实时增强）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
            // 重算本页：检测当前页是否已有超分结果，没有强制重算（已批量过但缺页的书也补得动）
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
            "作用域=关闭时下面的设置可提前选好但不生效；已离线增强过的页无需开关，阅读时自动用增强图。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("启用画质增强", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("AI 放大图片变清晰；关闭时仍可单跑黑白优化（原尺寸、毫秒级）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        // ★ 内层 Column 包成一个整体子项：外层 Column 的 spacedBy(10dp) 不再对每行重复计费；
        //   关闭 chip 的 48dp 最小触摸目标强制 + 固定 32dp 高，行距精确 2dp
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
                                enabled = NativeLibraryStatus.isModelAvailable(m),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        // ★ 选中档位下方即显示一句话说明（唯一事实源 SRModel.description，替代原底部大段"档位说明"）
        Text(
            model.description,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ★ 降噪强度仅对支持的档位生效（V3 无降噪输入→灰掉+说明；CUGAN/waifu2x 按强度换变体模型）
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

        // ════ ③ 黑白漫画优化（可单独开启）════
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("黑白漫画优化（可单独开启）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
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

        HorizontalDivider()

        // ════ ④ 处理引擎 ════
        // NPU：骁龙 AI 芯片跑同款模型，最快最省电；需要装对应代的模型包插件。
        // 设备探测（QNN 代号 + CDSP 节点）在 IO 线程做一次；非骁龙设备整行不出现 NPU 项。
        // 用户主动制（2026-10-02）：实测为参考信息——未实测/实测失败也允许选 NPU，
        // 真不支持时装载失败就地自动回 GPU/CPU。
        val npuTierOk = npuArch > 0 && NpuModelRegistry.supports(model, denoise, npuArch)
        val npuDeviceOk = npuProbed && npuArch > 0
        val npuChipOk = npuDeviceOk && npuModelsReady && npuTierOk

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
                        engine = e; settings.engine = e
                        scope.launch { SrCore.applySettings(bookId) }
                    },
                    label = { Text(label, fontSize = 12.sp) },
                    enabled = enabled,
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
                        else -> "先安装「漫读-NPU模型-v$npuArch.apk」再选 NPU"
                    }
            },
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (npuDeviceOk) {
            // 状态/引导补充行：只在有信息量时出现（选了 NPU，或还没装模型包）
            if (engine == SrEngine.NPU || !npuModelsReady) {
                Text(
                    when {
                        engine == SrEngine.NPU && npuTesting ->
                            "正在实测：真实加载一次 NPU 模型试水，约 1-3 秒…"
                        engine == SrEngine.NPU && npuModelsReady && npuTierOk && npuTestOk ->
                            "当前档位（${model.displayName}）走 NPU 处理；${QnnEngine.archDisplayName(npuArch)}"
                        !npuModelsReady ->
                            "检测到 NPU（v$npuArch，${QnnEngine.archDisplayName(npuArch)}）。" +
                                "安装「漫读-NPU模型-v$npuArch.apk」后，支持的档位可切换 NPU 极速"
                        else -> "${QnnEngine.archDisplayName(npuArch)}（v$npuArch）"
                    },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 测试按钮：选了 NPU 且模型包就绪时出现（探到芯片代+有 context 才能试水；封锁后仍可重测）
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
                npuTestNote?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        HorizontalDivider()

        // ════ ⑤ 高级与缓存 ════
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

        // 实时缓存上限（用户自定 MB）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("实时缓存上限", Modifier.weight(1f), fontSize = 14.sp)
            OutlinedTextField(
                value = cacheMbInput,
                onValueChange = { s ->
                    val digits = s.filter { it.isDigit() }.take(4)
                    val v = digits.toIntOrNull()
                    if (v != null && v > 4096) {
                        // 超上限直接夹到 4096，不要留下半截
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
            "实时看图时临时算的增强图缓存，超上限自动清最旧（16–4096 MB，默认 100）；离线增强的产物不受此限",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                SrCore.dispatcher.clearCache()
                cacheText = cacheUsageText()
                android.widget.Toast.makeText(ctx, "实时缓存已清空（批量产物保留）", android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("清空实时缓存（不动离线增强产物）", color = MaterialTheme.colorScheme.error) }
    }
}

private fun cacheUsageText(): String {
    val (rc, rb) = SrCore.diskCache.getRealtimeUsage()
    val (bc, bb) = SrCore.diskCache.getBatchUsage()
    return "实时 $rc 张/${rb / 1024 / 1024}MB · 批量 $bc 张/${bb / 1024 / 1024}MB"
}
