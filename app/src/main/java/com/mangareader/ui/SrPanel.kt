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
import kotlinx.coroutines.launch

private const val ANIM_DURATION = 300
private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val SIDE_PANEL_SHAPE = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)

/**
 * 阅读器底栏【超分】面板。
 * 交互约定（按用户要求）：
 *  - 面板里所有功能任何时候都可见、都能设置（不因作用域关闭而隐藏）；只是"作用域=关闭"时它们不生效；
 *  - 作用域（关闭/仅当前页/当前整本）控制"处理哪些页"，每本书每次进阅读默认关闭；
 *  - 「启用超分放大」控制处理时放不放大；关掉也能单独用「黑白漫画优化」（原尺寸、毫秒级）；
 *  - 已批量离线超分的内容无需任何开关，阅读时自动生效。
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
                            (LocalConfiguration.current.screenHeightDp * 0.60f).dp
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
    // （原"自动选最快模型"功能 2026-08-28 已下线：只剩两个模型，手动选即可）
    var cacheMbInput by remember { mutableStateOf(settings.realtimeCacheLimitMb.toString()) }

    val scopeState = SrUiState.scope.intValue

    val engineAvailable = NativeLibraryStatus.isRealESRGANAvailable
    val vulkanOk = !SrCore.isVulkanMissing() || !model.requiresVulkan
    val usable = engineAvailable && vulkanOk
    val blockReason = when {
        !engineAvailable -> "画质增强组件加载失败，功能不可用"
        !vulkanOk -> "此设备不支持 Vulkan 图形加速，画质增强不可用"
        else -> null
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

        // ── 作用域 ──
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
            "作用域关闭时下面功能都不会生效，但都能提前设置好（“重算本页”同样需要先选作用域）；" +
                "已批量离线增强过的内容无需开关，阅读时自动用增强图。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // ── 超分放大 ──
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
        // ★ 处理档位：始终可见可预选（作用域关闭时也能提前设置好），主开关仍控制是否生效
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

        // ── 黑白漫画优化（可单独开启）──
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

        // ── 处理引擎（CPU / GPU / NPU 三选一，2026-10 NPU 引入）──
        // NPU：骁龙 AI 芯片跑同款模型，最快最省电；需要装对应代的模型包插件。
        // 设备探测（QNN 代号 + CDSP 节点）在 IO 线程做一次；非骁龙设备整行不出现 NPU 项。
        var engine by remember { mutableStateOf(settings.engine) }
        var npuProbed by remember { mutableStateOf(false) }
        var npuArch by remember { mutableStateOf(0) }
        var npuModelsReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!QnnEngine.nativeLoaded) {
                npuProbed = true
                return@LaunchedEffect
            }
            val (arch, hasPkg) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                NpuModelRegistry.ensureScanned(ctx)
                val a = QnnEngine.probe(QnnEngine.libraryDir(ctx))
                a to NpuModelRegistry.hasPackageFor(a)
            }
            npuArch = arch
            npuModelsReady = hasPkg
            npuProbed = true
        }
        val npuTierOk = npuArch > 0 && NpuModelRegistry.supports(model, denoise, npuArch)
        val npuDeviceOk = npuProbed && npuArch > 0

        Text("处理引擎", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(SrEngine.CPU, "CPU 稳妥", true),
                Triple(SrEngine.GPU, "GPU 加速", !SrCore.isVulkanMissing()),
                Triple(SrEngine.NPU, "NPU 极速", npuDeviceOk && npuModelsReady && npuTierOk),
            ).forEach { (e, label, enabled) ->
                if (e == SrEngine.NPU && !npuDeviceOk) return@forEach // 非骁龙/无 CDSP：不显示
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
                SrEngine.CPU -> "默认。所有手机可用，速度最慢但最稳；上次崩溃会自动回到这里"
                SrEngine.GPU -> "交给显卡（Vulkan）处理，比 CPU 快数倍；发热明显时切回 CPU"
                SrEngine.NPU ->
                    if (!npuDeviceOk) "本机未检测到可用 NPU，将自动用 GPU/CPU 处理"
                    else "交给骁龙 AI 芯片（v$npuArch）：最快最省电；个别页可能有轻微偏色"
            },
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (npuDeviceOk) {
            Text(
                when {
                    npuModelsReady && npuTierOk ->
                        "NPU 模型包已就绪（v$npuArch）：当前档位走 NPU 处理"
                    npuModelsReady ->
                        "已装 NPU 模型包，但当前档位/降噪无 NPU 版（支持：CUGAN 2x 保守、动漫V3 2x 极速）——自动用 GPU/CPU 处理"
                    else ->
                        "检测到 NPU（v$npuArch，${QnnEngine.archDisplayName(npuArch)}）。" +
                            "把「漫读-NPU模型-v$npuArch.apk」拷到手机点安装，回到本页立即生效"
                },
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // （2026-08-28 去掉"自动选最快模型"：只剩两个模型，手动选即可，实测反而费电耗时）

        HorizontalDivider()

        // ── 批量离线 ──
        if (bookId >= 0) {
            OutlinedButton(
                onClick = {
                    SrBatchRunner.enqueueBook(ctx, bookId, bookTitle) { n ->
                        (ctx as? android.app.Activity)?.runOnUiThread {
                            android.widget.Toast.makeText(
                                ctx,
                                if (n > 0) "已加入批量增强队列（$n 章）" else "本书已在队列中",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("批量增强本书（离线慢慢算，之后永久生效）") }
        }
        TextButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth()) {
            Text("查看增强队列与缓存管理")
        }


        // ── 测速 / 缓存 ──
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
            "实时看图时临时算的增强图缓存，超上限自动清最旧（16–4096 MB，默认 100）；批量产物不受此限",
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
        ) { Text("清空实时缓存（不动批量产物）", color = MaterialTheme.colorScheme.error) }

        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ── 档位说明（帮助用户直白理解选哪个）──
        Text("档位说明", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "⚡ 快速优化 1x：不放大。毫秒级去掉噪点脏斑、拉锐线条。图够大、只想要干净时选它。\n" +
                "✨ 无损放大 2x：放大 2 倍。Lanczos3 纯 CPU 重采样，线条锐利不吃配置，没有 Vulkan 也能用。\n" +
                "🛡 CUGAN 2x 保守：放大 2 倍。漫画特化，线条最稳。画质优先选它。装 NPU 模型包后可走 NPU。\n" +
                "⚡ 动漫V3 2x 极速：放大 2 倍。比保守档快约一倍，线条略软。省电/批量选它。装 NPU 模型包后可走 NPU。\n" +
                "🔍 动漫V3 4x 大屏：放大 4 倍。平板大屏细看用，手机上过剩。\n" +
                "🎨 waifu2x 1x 清理：不放大。神经网络级去噪，快速优化不够干净时选它。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "实际速度与设备、处理引擎（CPU/GPU/NPU）有关：NPU 最快最省电（需装模型包，见处理引擎一行）、" +
                "GPU 其次、CPU 最稳；放大倍数越高图越大越清晰，但更慢、更占缓存。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun cacheUsageText(): String {
    val (rc, rb) = SrCore.diskCache.getRealtimeUsage()
    val (bc, bb) = SrCore.diskCache.getBatchUsage()
    return "实时 $rc 张/${rb / 1024 / 1024}MB · 批量 $bc 张/${bb / 1024 / 1024}MB"
}
