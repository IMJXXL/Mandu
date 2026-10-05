package com.mangareader.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.ReaderScope
import com.mangareader.filter.PaperTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读偏好（2026-10-04 用户定稿重构）：
 * - 每一项都能就地改「全局默认值」——控件与阅读界面/阅读设置同款（开关/滑条/选项块）；
 * - 每一项都能切「全局生效 / 本书单独」：预设作用域 = 本功能引入前的真实行为
 *   （只由阅读设置页改的项预设全局，书内面板能改的项预设本书），显式切换压过预设；
 * - 本书单独 = 在书里调这项只记在那本书上，没调过的书永远跟全局默认；
 * - 总开关「本书改动单独记」：关=书内调整退出阅读不保留（预设全局的项不受影响）；
 * - 恢复默认（分层）：本页=仅清所有书的单独设置（全局值不动），执行前自动快照可回滚。
 */

// ── 编辑器描述 ──────────────────────────────────────────────────────────────

internal sealed interface PrefEditor {
    /** 选项块（value: String/Int/Long/Boolean，写入类型与默认值一致）；info=选项下的说明小字 */
    data class Chips(val options: List<Pair<String, Any>>, val info: String? = null) : PrefEditor
    data class Toggle(val info: String? = null) : PrefEditor
    /** 滑条；isFloat=false 时写 Int */
    data class Slider(
        val min: Float, val max: Float, val steps: Int,
        val fmt: (Float) -> String, val isFloat: Boolean = false,
    ) : PrefEditor
    /** 复合项子件：key + 界面标签 + 控件 */
    data class Part(val key: String, val label: String, val editor: PrefEditor)
    /** 复合项：首个 = 总开关（关时其余子项隐藏，与阅读界面设置一致） */
    data class Group(val parts: List<Part>) : PrefEditor
    /** 阅读模式：特殊双键项（书层 read_mode_<id>，全局层 default_read_mode） */
    data object ReadMode : PrefEditor
    /** 氛围音：预设+用户导入音频选择 */
    data object AmbienceSound : PrefEditor
}

internal data class PrefRow(
    val key: String,
    val label: String,
    val tab: Int, // 0=阅读模式 1=常规 2=滤镜——与阅读界面设置三个页签同名同序，用户按原位置找
    val editor: PrefEditor,
    val section: String? = null, // Tab 内小节标题（对应书内面板的 GroupTitle）
    val subKeys: List<String> = emptyList(),
) {
    val allKeys: List<String> get() = listOf(key) + subKeys
}

/** 全局默认值读取（带出厂默认，类型安全） */
internal fun prefGlobalValue(prefs: SharedPreferences, key: String): Any? {
    val def = PREF_DEFAULTS[key] ?: return null
    return try {
        when (def) {
            is Boolean -> prefs.getBoolean(key, def)
            is Int -> prefs.getInt(key, def)
            is Long -> prefs.getLong(key, def)
            is Float -> prefs.getFloat(key, def)
            is String -> prefs.getString(key, def) ?: def
            else -> null
        }
    } catch (_: ClassCastException) { def }
}

internal val PREF_DEFAULTS: Map<String, Any> = mapOf(
    "isFlipAnimation" to true, "isPageCurlAnimation" to false, "isCurlContentAreaOnly" to true,
    "curlBackContentAlpha" to 0.1f, "curlShadowAlpha" to 0.2f, "spineShadowAlpha" to 0.09f,
    "isShowPageNum" to true, "isKeepScreenOn" to true, "isImmersiveMode" to true,
    "isTrimWhiteBorder" to false, "trimFactor" to 0.02f, "trimThreshold" to 220, "trimWhiteRatio" to 0.90f,
    "imageMarginH" to 0, "readBackground" to "WHITE",
    "isEinkMode" to false, "isEinkDither" to false, "einkDitherAlgorithm" to 0, "einkDitherLevels" to 16,
    "einkEndpointPassthrough" to 0, "einkDecodeSize" to 1920, "einkUiHideDelay" to 6000L,
    "pdfQuality" to 3.0f, "orientation" to -1, "autoFlipInterval" to 0,
    "isGrayscale" to false, "isInvertColors" to false,
    "isFlipFlashEnabled" to false, "flipFlashDuration" to 100, "flipFlashInterval" to 1, "flipFlashColor" to 0,
    "pageLayout" to "SINGLE", "useSSIV" to true, "landscapeZoom" to false, "isDoubleTapZoom" to true,
    "panNavigation" to true, "doublePageShift" to 0, "doublePageSplit" to false, "doublePageReverse" to false,
    "autoWebtoon" to true, "chapterTransition" to true, "centerGapType" to 0,
    "autoDoubleLandscape" to false, "clickFlipInvertH" to false, "forceFullDecode" to false,
    "brightness" to 0, "screenBrightness" to 0, "contrast" to 100, "gamma" to 100,
    "brightnessEnabled" to false, "screenBrightnessEnabled" to false, "contrastEnabled" to false, "gammaEnabled" to false,
    "textureEnabled" to false, "texturePresetId" to 0, "textureAlpha" to 15, "textureConcentration" to 100, "textureScope" to 0,
    "ambienceSound" to "", "ambienceVolume" to 60,
    "imageScaleMode" to 1, "zoomStart" to 0, "seamlessNextVolume" to true, "tileModeEnabled" to false,
    "tallSplitEnabled" to false, "tallSplitSensitivity" to 0, "downsampleTier" to "STANDARD", "clickFlipType" to "LEFT_RIGHT",
    "scrollMode" to "HORIZONTAL_LEFT",
)

// ── 行注册表（顺序 = 页面展示顺序）─────────────────────────────────────────

private val pct: (Float) -> String = { "${(it * 100).toInt()}%" }

internal val PREF_ROWS: List<PrefRow> = listOf(
    // ── Tab 0 阅读模式（顺序=书内面板第一页）──
    PrefRow(ReaderScope.READ_MODE_KEY, "阅读模式", 0, PrefEditor.ReadMode),
    PrefRow("pageLayout", "页面布局", 0, PrefEditor.Chips(
        listOf("单页" to "SINGLE", "双页" to "DOUBLE", "双页跳过首页" to "DOUBLE_SHIFTED"))),
    PrefRow("isTrimWhiteBorder", "裁白边", 0, PrefEditor.Group(listOf(
        PrefEditor.Part("isTrimWhiteBorder", "裁白边", PrefEditor.Toggle()),
        PrefEditor.Part("trimThreshold", "检测阈值（默认 220）", PrefEditor.Slider(180f, 250f, 69, { "阈值 ${it.toInt()}" })),
        PrefEditor.Part("trimWhiteRatio", "白色比例（默认90%）", PrefEditor.Slider(0.5f, 0.95f, 45, pct)),
    )), subKeys = listOf("trimThreshold", "trimWhiteRatio", "trimFactor")),
    PrefRow("landscapeZoom", "自动放大横向图片", 0, PrefEditor.Toggle(info = "宽图自动贴满高度；仅“完整显示/铺满屏幕”档生效；条漫模式不适用")),
    PrefRow("isDoubleTapZoom", "双击放大", 0, PrefEditor.Toggle(info = "关闭后双击不再缩放，快速连点按两次点击翻页计")),
    PrefRow("isFlipAnimation", "翻页动画", 0, PrefEditor.Group(listOf(
        PrefEditor.Part("isFlipAnimation", "翻页动画（点击翻页过渡）", PrefEditor.Toggle()),
        PrefEditor.Part("isPageCurlAnimation", "仿真翻页Beta（仅静图左右翻页生效）", PrefEditor.Toggle()),
        PrefEditor.Part("isCurlContentAreaOnly", "翻页仅限画面区域", PrefEditor.Toggle()),
        PrefEditor.Part("curlBackContentAlpha", "背面透出程度（默认10%）", PrefEditor.Slider(0f, 1f, 19, pct)),
        PrefEditor.Part("curlShadowAlpha", "翻页阴影浓度（0=关闭，默认20%）", PrefEditor.Slider(0f, 0.5f, 9, pct)),
        PrefEditor.Part("spineShadowAlpha", "书脊阴影浓度（双页书脊沟，默认9%）", PrefEditor.Slider(0f, 0.3f, 29, pct)),
    )), section = "翻页",
        subKeys = listOf("isPageCurlAnimation", "isCurlContentAreaOnly", "curlBackContentAlpha", "curlShadowAlpha", "spineShadowAlpha")),
    PrefRow("imageScaleMode", "画面缩放", 0, PrefEditor.Chips(
        listOf("完整显示" to 0, "铺满屏幕" to 1, "贴满高度" to 2, "原始尺寸" to 3, "智能适应" to 4),
        info = "条漫模式恒贴宽；仿真/无平移视图自动收敛：贴满高度→铺满（防宽图裁切找不回）、智能→铺满、双页→铺满"
    ), section = "画面"),
    PrefRow("zoomStart", "缩放起始位置", 0, PrefEditor.Chips(
        listOf("自动" to 0, "左" to 1, "中" to 2, "右" to 3),
        info = "横向有留白/宽图放大后对齐哪一侧；“自动”跟随阅读方向（日漫右开→右缘）"
    ), section = "画面"),
    PrefRow("useSSIV", "高清缩放", 0, PrefEditor.Toggle(), section = "画面"),
    PrefRow("doublePageSplit", "拆分双页", 0, PrefEditor.Group(listOf(
        PrefEditor.Part("doublePageSplit", "拆分双页（跨页图拆成左右两页）", PrefEditor.Toggle()),
        PrefEditor.Part("doublePageReverse", "反转双页", PrefEditor.Toggle()),
    )), section = "画面", subKeys = listOf("doublePageReverse")),
    PrefRow("imageMarginH", "侧边留白", 0, PrefEditor.Slider(-20f, 50f, 70, { "${it.toInt()}dp" }), section = "画面"),
    PrefRow("centerGapType", "双页中缝", 0, PrefEditor.Chips(
        listOf("贴合" to 0, "窄缝" to 1, "中缝" to 2, "宽缝" to 3)), section = "画面"),
    PrefRow("orientation", "屏幕方向", 0, PrefEditor.Chips(
        listOf("默认" to -1, "跟随系统" to -2, "竖屏" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            "横屏" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            "锁定竖屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            "锁定横屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))),
    // ── Tab 1 常规（顺序=书内面板第二页）──
    PrefRow("isShowPageNum", "显示页码", 1, PrefEditor.Toggle()),
    PrefRow("isImmersiveMode", "沉浸模式", 1, PrefEditor.Toggle(info = "重进阅读后生效")),
    PrefRow("isKeepScreenOn", "保持亮屏", 1, PrefEditor.Toggle()),
    PrefRow("seamlessNextVolume", "无缝续读下一卷", 1, PrefEditor.Toggle(info = "读到卷末自动进入同书下一卷，无过渡页")),
    PrefRow("autoWebtoon", "自动进入条漫模式", 1, PrefEditor.Toggle(info = "新书首开按图片宽高比智能判定")),
    PrefRow("autoDoubleLandscape", "横屏自动双页", 1, PrefEditor.Toggle(info = "仅页面布局为单页时生效")),
    PrefRow("ambienceSound", "氛围音", 1, PrefEditor.AmbienceSound),
    PrefRow("ambienceVolume", "氛围音音量", 1, PrefEditor.Slider(0f, 100f, 19, { "${it.toInt()}%" })),
    // ── Tab 2 滤镜（顺序=书内面板第三页）──
    PrefRow("isGrayscale", "灰度模式", 2, PrefEditor.Toggle()),
    PrefRow("isInvertColors", "反色", 2, PrefEditor.Toggle()),
    PrefRow("brightness", "图片亮度", 2, PrefEditor.Group(listOf(
        PrefEditor.Part("brightnessEnabled", "启用图片亮度", PrefEditor.Toggle()),
        PrefEditor.Part("brightness", "亮度（默认 0）", PrefEditor.Slider(-100f, 100f, 199, { "${it.toInt()}" })),
    )), subKeys = listOf("brightnessEnabled", "brightness")),
    PrefRow("screenBrightness", "屏幕亮度", 2, PrefEditor.Group(listOf(
        PrefEditor.Part("screenBrightnessEnabled", "启用屏幕亮度", PrefEditor.Toggle()),
        PrefEditor.Part("screenBrightness", "屏幕亮度（默认 0）", PrefEditor.Slider(-100f, 100f, 199, { "${it.toInt()}" })),
    )), subKeys = listOf("screenBrightnessEnabled", "screenBrightness")),
    PrefRow("contrast", "对比度", 2, PrefEditor.Group(listOf(
        PrefEditor.Part("contrastEnabled", "启用对比度", PrefEditor.Toggle()),
        PrefEditor.Part("contrast", "对比度（默认 100）", PrefEditor.Slider(0f, 200f, 199, { "${it.toInt()}" })),
    )), subKeys = listOf("contrastEnabled", "contrast")),
    PrefRow("gamma", "伽马", 2, PrefEditor.Group(listOf(
        PrefEditor.Part("gammaEnabled", "启用伽马", PrefEditor.Toggle()),
        PrefEditor.Part("gamma", "伽马（默认 100）", PrefEditor.Slider(50f, 150f, 99, { "${it.toInt()}" })),
    )), subKeys = listOf("gammaEnabled", "gamma")),
    PrefRow("textureEnabled", "纸张纹理", 2, PrefEditor.Group(listOf(
        PrefEditor.Part("textureEnabled", "纸张纹理", PrefEditor.Toggle()),
        PrefEditor.Part("texturePresetId", "纸张类型", PrefEditor.Chips(PaperTexture.PRESETS.mapIndexed { i, (_, label) -> label to i })),
        PrefEditor.Part("textureConcentration", "纸感浓度（默认100%=真实纸感）", PrefEditor.Slider(0f, 100f, 99, { "${it.toInt()}%" })),
        PrefEditor.Part("textureScope", "生效范围", PrefEditor.Chips(listOf("仅图片" to 0, "全屏幕" to 1))),
    )), subKeys = listOf("texturePresetId", "textureConcentration", "textureScope", "textureAlpha")),
)

/** 快照取值：缺省回出厂默认（写后由 version 重建快照驱动刷新） */
internal fun valueOf(values: Map<String, Any?>, key: String): Any? =
    values[key] ?: PREF_DEFAULTS[key]

/** 单键原始值（书内明细对话框用）：本书键 > 全局键 > null(出厂默认) */
internal fun prefRawValue(prefs: SharedPreferences, key: String, bookId: Long? = null): String? {
    val bk = bookId?.let { if (key == ReaderScope.READ_MODE_KEY) "read_mode_$it" else "book_${it}_$key" }
    val raw: Any? = when {
        bk != null && prefs.contains(bk) -> prefs.all[bk]
        key == ReaderScope.READ_MODE_KEY -> prefs.all[ReaderScope.READ_MODE_GLOBAL_KEY]
        prefs.contains(key) -> prefs.all[key]
        else -> null
    }
    return when (raw) {
        null -> null
        is Boolean -> if (raw) "开" else "关"
        is Float -> String.format(java.util.Locale.US, "%.2f", raw)
        is Int -> if (key == ReaderScope.READ_MODE_KEY && raw in Mode.entries.indices) Mode.entries[raw].label else raw.toString()
        else -> raw.toString()
    }
}

// ── 屏幕 ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadingPrefsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var masterOn by remember { mutableStateOf(ReaderScope.masterOn(prefs)) }
    var version by remember { mutableIntStateOf(0) } // 作用域/覆盖/全局值变更时刷新
    val pagerState = rememberPagerState(pageCount = { 3 }) // 三个页签：阅读模式/常规/滤镜
    var clearTarget by remember { mutableStateOf<Pair<PrefRow, Int>?>(null) } // 行内"单独·N本"徽章 → 清除确认
    var masterConfirmStep by remember { mutableIntStateOf(0) } // 关总开关两次确认：1=第一步 2=最终确认
    var showHelp by remember { mutableStateOf(false) } // 「这一页怎么用」弹窗（规则+例子）
    var writeToastShown by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun notify(t: String) { toast = t }
    // 行内写值（全部写全局默认值；固定全局项与可切项一致）
    val onWrite: (String, Any) -> Unit = { k, v ->
        ReaderScope.writeGlobal(prefs, k, v)
        version++
        if (!writeToastShown) {
            writeToastShown = true
            notify("已保存为默认值（书里单独调过的书不受影响）")
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
            toast = null
        }
    }

    // 覆盖统计：key → 覆盖它的书数；books = 有任意单独设置的书数
    val (countByItem, bookCount) = remember(version) {
        val map = mutableMapOf<String, Int>()
        val books = mutableSetOf<String>()
        prefs.all.keys.forEach { k ->
            if (k.startsWith("book_")) {
                val rest = k.substringAfter("book_")
                val base = rest.substringAfter('_')
                // ★ 固定裁剪键（cropL/T/R/B，2026-10-04）是书的数据不是阅读偏好，不计入覆盖统计
                if (base == "cropL" || base == "cropT" || base == "cropR" || base == "cropB") return@forEach
                map[base] = (map[base] ?: 0) + 1
                // ★ 修 bookId 提取：原来 substringBefore('_') 取到字面 "book"，本数恒显示 1
                books.add(rest.substringBefore('_'))
            } else if (k.startsWith("read_mode_")) {
                map[ReaderScope.READ_MODE_KEY] = (map[ReaderScope.READ_MODE_KEY] ?: 0) + 1
                books.add(k.removePrefix("read_mode_"))
            }
        }
        map to books.size
    }
    fun coveredOf(row: PrefRow) = row.allKeys.maxOf { countByItem[it] ?: 0 }
    val totalCoveredRows = PREF_ROWS.count { coveredOf(it) > 0 }
    // ★ 全局值/作用域快照（Compose 状态）：写入后 version++ 重建快照，
    //   新实例作为参数下传——行内勾选/徽章即时刷新，不依赖 prefs 重读
    val globalValues = remember(version) { prefs.all.toMap() }
    val scopeReg = remember(version) { ReaderScope.parseScopeReg(prefs) }

    // 行尾"全局/本书"小徽章：点一下切换作用域。
    // 本书=这页参数锁定，进书里调成啥算啥；全局=可在这页设默认值（已单独调过的书仍优先于全局）
    val onScopeSwitch: (PrefRow) -> Unit = { row ->
        val target = if (ReaderScope.isBookScope(prefs, row.key)) ReaderScope.SCOPE_GLOBAL else ReaderScope.SCOPE_BOOK
        row.allKeys.forEach { ReaderScope.setScope(prefs, it, target) }
        version++
        val covered = coveredOf(row)
        notify(
            when {
                target == ReaderScope.SCOPE_BOOK -> "已切为「本书」：这页参数锁定，进书里调，只记那本书"
                covered > 0 -> "已切为「全局」：书里调它＝默认值变了；有 $covered 本书单独调过仍用自己的值，点「单独·N本」可清除"
                else -> "已切为「全局」：在书里调它，没单独调过的书都跟着变"
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读偏好") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "这一页怎么用") }
                    IconButton(onClick = { showResetDialog = true }) { Icon(Icons.Default.RestartAlt, "恢复默认") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ── 总开关 + 计数卡 ──
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("本书改动单独记", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text(
                                "开启后，在书里调「本书」项会记住；关闭后不再记住新的调整（已记住的仍生效）",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = masterOn,
                            onCheckedChange = { on ->
                                if (on) {
                                    masterOn = true
                                    prefs.edit().putBoolean(ReaderScope.MASTER_KEY, true).apply()
                                    notify("已开启：在书里调「本书」项会记住")
                                } else {
                                    masterConfirmStep = 1 // 关闭需两次确认（防误关后不记忆）
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "共 ${PREF_ROWS.size} 项 · 已单独设置 $totalCoveredRows 项（$bookCount 本书）",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ── 三个页签：与阅读界面设置同名同序（阅读模式 / 常规 / 滤镜），一找就找到 ──
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {},
            ) {
                listOf("阅读模式", "常规", "滤镜").forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title, fontSize = 14.sp) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.weight(1f),
            ) { page ->
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val rows = PREF_ROWS.filter { it.tab == page }
                    rows.forEachIndexed { idx, row ->
                        row.section?.takeIf { idx == 0 || rows[idx - 1].section != row.section }?.let { section ->
                            Text(
                                section, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                            )
                        }
                        PrefRowBlock(
                            row, globalValues,
                            (scopeReg[row.key] ?: ReaderScope.presetScope(row.key)) == ReaderScope.SCOPE_BOOK,
                            coveredOf(row), onWrite, onScopeSwitch,
                        ) { r, c -> clearTarget = r to c }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    // ── 「这一页怎么用」弹窗：四条规则 + 亮度例子 ──
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("这一页怎么用") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("四条规则", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("· 行尾状态「全局」的项：在书里调它＝所有没单独调过的书都跟着变；这页只是预设默认值", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("· 行尾状态「本书」的项：这页不可设；没单独调过的书用出厂初始值，进书里调成啥算啥", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("· 书里调过的项永远优先（行尾挂「单独·N本」），默认值和开关都动不了它", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("· 顶部开关只管「书里的新调整记不记」，关闭要确认两次", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("举个例子：亮度", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "亮度出厂默认 0，状态「全局」：",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "① 你在看《漫画A》，把亮度调到 70。\n→ 「全局」：70 成为新默认值，所有没单独调过的书打开都是 70。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "② 把亮度的状态切成「本书」，再在《漫画B》里调到 30。\n→ 只有《漫画B》是 30；其他没单独调过的书回到出厂的 0（「本书」状态不看全局默认值）。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "③ 这时《漫画B》行尾会挂出「单独·1本」小标签，意思是：有 1 本书的亮度跟默认值不一样。\n→ 只要这个标签在，《漫画B》就一直用自己的 30，默认值改成多少都影响不到它。\n　 哪天想让《漫画B》回到默认值：点「单独·1本」→ 清除，它就重新跟着默认值走。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "④ 把顶部「本书改动单独记」开关关掉以后，你再在书里调亮度：\n→ 当次阅读里照样生效，但退出这本书就还原、不被记住；\n　 之前已经记住的（《漫画B》的 30）原样保留——开关只管「以后调的记不记」，动不了已经记下的。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "偏好页里的参数只是预设初始默认值，平时在书里调最顺手。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("知道了") }
            },
        )
    }

    // ── 关闭总开关：两次确认（用户定稿：防误关后书内调整不记忆）──
    if (masterConfirmStep == 1) {
        AlertDialog(
            onDismissRequest = { masterConfirmStep = 0 },
            title = { Text("关闭「本书改动单独记」？") },
            text = {
                Text(
                    "关闭后，在书里调「本书」项的设置不再记住，退出阅读就还原。\n" +
                        "已经记住的书本设置仍然生效，关闭只对之后生效，不会清除任何东西。",
                )
            },
            confirmButton = {
                TextButton(onClick = { masterConfirmStep = 2 }) { Text("下一步") }
            },
            dismissButton = { TextButton(onClick = { masterConfirmStep = 0 }) { Text("再想想") } },
        )
    }
    if (masterConfirmStep == 2) {
        AlertDialog(
            onDismissRequest = { masterConfirmStep = 0 },
            title = { Text("再次确认") },
            text = { Text("确定关闭吗？\n这不会清除已记住的书本设置，只是之后在书里的调整不再被记住。") },
            confirmButton = {
                TextButton(onClick = {
                    masterOn = false
                    prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
                    masterConfirmStep = 0
                    notify("已关闭：书里的调整不再记住，已记住的仍生效")
                }) { Text("确定关闭", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { masterConfirmStep = 0 }) { Text("取消") } },
        )
    }

    // ── 清除单项覆盖（点行内"单独·N本"徽章触发）──
    clearTarget?.let { (row, covered) ->
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text("清除「${row.label}」的单独设置") },
            text = { Text("有 $covered 本书单独调过这项，不受全局影响。\n清除后这些书恢复跟随全局默认。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (row.key == ReaderScope.READ_MODE_KEY) {
                                // 阅读模式的单本记录是独立键族 read_mode_<id>
                                prefs.all.keys.filter { it.startsWith("read_mode_") }
                                    .forEach { prefs.edit().remove(it).apply() }
                            } else {
                                prefs.all.keys.filter { k ->
                                    k.startsWith("book_") && row.allKeys.any { k.endsWith("_$it") }
                                }.forEach { prefs.edit().remove(it).apply() }
                            }
                        }
                        version++
                        clearTarget = null
                        notify("已清除 $covered 本书的此项设置")
                    }
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearTarget = null }) { Text("取消") } },
        )
    }

    // ── 恢复默认（分层：仅清所有书的单独设置）──
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认") },
            text = {
                Text(
                    "将回到没有「阅读偏好」这个功能时的状态：本页参数恢复出厂预设、清除所有书里单独调过的设置（共 $bookCount 本书）、作用域回默认、总开关重新打开。\n" +
                        "设置 › 阅读设置 里的全局项（墨水屏、性能与解码等）不受影响。\n" +
                        "改错了可以找回：会保留最近 3 次重置前的设置。\n" +
                        "阅读进度、书签、缓存不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            com.mangareader.data.ReaderPrefMigration.snapshotNow(ctx)
                            prefs.all.keys.filter { it.startsWith("book_") || it.startsWith("read_mode_") }
                                .forEach { prefs.edit().remove(it).apply() }
                            // 恢复到"功能上线前"：删本页全局键 + 作用域回预设 + 总开关回开出厂态
                            val ed = prefs.edit()
                            PREF_ROWS.flatMap { it.allKeys }.plus(ReaderScope.READ_MODE_GLOBAL_KEY)
                                .forEach { ed.remove(it) }
                            ed.remove("pref_center_scope_reg").remove(ReaderScope.MASTER_KEY).apply()
                        }
                        version++
                        masterOn = true
                        showResetDialog = false
                        notify("已恢复到功能上线前的默认状态")
                    }
                }) { Text("清空并重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { com.mangareader.data.ReaderPrefMigration.restoreLatestSnapshot(ctx) }
                        version++
                        showResetDialog = false
                        notify(if (ok) "已恢复到上次重置前" else "没有可用的备份")
                    }
                }) { Text("撤销上次重置") }
            },
        )
    }
}

/** 行内编辑块：勾选/滑条/选项块直接操作（与阅读界面设置同款）。
 *  行尾徽章：「本书/全局」点一下切作用域；「单独·N本」点按清除这些书的覆盖。 */
@Composable
private fun PrefRowBlock(
    row: PrefRow,
    values: Map<String, Any?>,
    isBook: Boolean,
    covered: Int,
    onWrite: (String, Any) -> Unit,
    onScopeSwitch: (PrefRow) -> Unit,
    onClear: (PrefRow, Int) -> Unit,
) {
    // ★ 本书状态：不渲染任何控件（用户定稿 2026-10-04）——本书项没有"这页的默认值"，
    //   没调过的书用出厂初始值，调过的书用自己的值；灰勾选会让人误以为"默认带着勾"
    if (isBook) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.label, fontSize = 15.sp)
                Text(
                    "参数在书里调，改成啥算啥；没调过的书用出厂初始值",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ScopeBadges(row, isBook, covered, onScopeSwitch, onClear)
        }
        return
    }
    when (val ed = row.editor) {
        is PrefEditor.Group -> {
            val primary = ed.parts.first()
            val on = valueOf(values, primary.key) as? Boolean ?: false
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { onWrite(primary.key, it) })
                Text(primary.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                ScopeBadges(row, isBook, covered, onScopeSwitch, onClear)
            }
            // 勾选后子设置就地展开（与阅读界面设置一致）
            if (on) {
                Column(Modifier.padding(start = 16.dp)) {
                    ed.parts.drop(1).forEach { InlinePart(it, values, !isBook, onWrite) }
                }
            }
        }
        is PrefEditor.Toggle -> {
            val on = valueOf(values, row.key) as? Boolean ?: false
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { onWrite(row.key, it) }, enabled = !isBook)
                Column(Modifier.weight(1f)) {
                    Text(row.label, fontSize = 15.sp)
                    ed.info?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                ScopeBadges(row, isBook, covered, onScopeSwitch, onClear)
            }
        }
        is PrefEditor.Slider -> SliderBlock(row.label, row.key, ed, values, !isBook, onWrite) {
            ScopeBadges(row, isBook, covered, onScopeSwitch, onClear)
        }
        is PrefEditor.Chips -> {
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.label, fontSize = 15.sp)
                        ed.info?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    ScopeBadges(row, isBook, covered, onScopeSwitch, onClear)
                }
                InlineChips(null, valueOf(values, row.key), row.key, ed.options, !isBook, onWrite)
            }
        }
        is PrefEditor.ReadMode -> {
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("阅读模式", fontSize = 15.sp)
                        Text("新书第一次打开按这个模式进入", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ScopeBadges(row, isBook, covered, onScopeSwitch, onClear)
                }
                InlineChips(
                    null, (values[ReaderScope.READ_MODE_GLOBAL_KEY] as? Int) ?: -1, ReaderScope.READ_MODE_KEY,
                    listOf("按书籍自动" to -1) + Mode.entries.map { it.label to it.ordinal }, !isBook, onWrite,
                )
            }
        }
        is PrefEditor.AmbienceSound -> {
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("氛围音", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    ScopeBadges(row, isBook, covered, onScopeSwitch, onClear)
                }
                AmbienceChips(values, !isBook, onWrite)
            }
        }
    }
}

@Composable
private fun ScopeBadges(
    row: PrefRow, isBook: Boolean, covered: Int,
    onScopeSwitch: (PrefRow) -> Unit, onClear: (PrefRow, Int) -> Unit,
) {
    if (covered > 0) {
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable { onClear(row, covered) }
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text("单独·${covered}本", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(6.dp))
    }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (isBook) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onScopeSwitch(row) }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            if (isBook) "本书" else "全局", fontSize = 11.sp,
            color = if (isBook) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InlinePart(part: PrefEditor.Part, values: Map<String, Any?>, enabled: Boolean, onWrite: (String, Any) -> Unit) {
    when (val ed = part.editor) {
        is PrefEditor.Toggle -> {
            val on = valueOf(values, part.key) as? Boolean ?: false
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { onWrite(part.key, it) }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(part.label, fontSize = 14.sp)
                    ed.info?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        is PrefEditor.Slider -> SliderBlock(part.label, part.key, ed, values, enabled, onWrite)
        is PrefEditor.Chips -> InlineChips(part.label, valueOf(values, part.key), part.key, ed.options, enabled, onWrite)
        else -> {}
    }
}

@Composable
private fun SliderBlock(
    label: String, key: String, ed: PrefEditor.Slider,
    values: Map<String, Any?>, enabled: Boolean, onWrite: (String, Any) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    var v by remember(key) { mutableStateOf((valueOf(values, key) as? Number)?.toFloat() ?: ed.min) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                ed.fmt(v), fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onWrite(key, if (ed.isFloat) v else Math.round(v)) },
            valueRange = ed.min..ed.max, steps = ed.steps, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineChips(
    label: String?, current: Any?, key: String,
    options: List<Pair<String, Any>>, enabled: Boolean, onWrite: (String, Any) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        label?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (optLabel, value) ->
                FilterChip(
                    selected = current == value,
                    onClick = { onWrite(key, value) },
                    enabled = enabled,
                    label = { Text(optLabel, fontSize = 13.sp) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmbienceChips(values: Map<String, Any?>, enabled: Boolean, onWrite: (String, Any) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var imported by remember { mutableStateOf(listImportedAmbience(ctx)) }
    val current = valueOf(values, "ambienceSound") as? String ?: ""
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val name = withContext(Dispatchers.IO) { importAmbienceFile(ctx, uri) }
            if (name != null) {
                imported = listImportedAmbience(ctx)
                onWrite("ambienceSound", name)
            }
        }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // ★ 预置与 AmbiencePlayer.PRESETS 同源（下雨/海浪/溪流/篝火/夏夜）
        (listOf("关闭" to "") + com.mangareader.sound.AmbiencePlayer.PRESETS).forEach { (label, value) ->
            FilterChip(
                selected = current == value,
                onClick = { onWrite("ambienceSound", value) },
                enabled = enabled,
                label = { Text(label, fontSize = 13.sp) },
            )
        }
        imported.forEach { name ->
            FilterChip(
                selected = current == name,
                onClick = { onWrite("ambienceSound", name) },
                enabled = enabled,
                label = { Text(name.substringBeforeLast('.').take(10), fontSize = 13.sp) },
            )
        }
        FilterChip(
            selected = false,
            onClick = { importLauncher.launch(arrayOf("audio/*")) },
            enabled = enabled,
            label = { Text("+ 导入", fontSize = 13.sp) },
        )
    }
}
