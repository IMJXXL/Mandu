package com.mangareader.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    /** 选项块（value: String/Int/Long/Boolean，写入类型与默认值一致） */
    data class Chips(val options: List<Pair<String, Any>>) : PrefEditor
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
    val group: String,
    val editor: PrefEditor,
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
    "isFlipAnimation" to true, "isPageCurlAnimation" to false, "isCurlContentAreaOnly" to false,
    "curlBackContentAlpha" to 0.1f, "curlShadowAlpha" to 0.2f, "spineShadowAlpha" to 0.09f,
    "isShowPageNum" to true, "isKeepScreenOn" to true, "isImmersiveMode" to true,
    "isTrimWhiteBorder" to false, "trimFactor" to 0.02f, "trimThreshold" to 220, "trimWhiteRatio" to 0.90f,
    "imageMarginH" to 0, "readBackground" to "WHITE",
    "isEinkMode" to false, "isEinkDither" to false, "einkDitherAlgorithm" to 0, "einkDitherLevels" to 16,
    "einkEndpointPassthrough" to 0, "einkDecodeSize" to 1920, "einkUiHideDelay" to 6000L,
    "pdfQuality" to 3.0f, "orientation" to -1, "autoFlipInterval" to 0,
    "isGrayscale" to false, "isInvertColors" to false,
    "isFlipFlashEnabled" to false, "flipFlashDuration" to 100, "flipFlashInterval" to 1, "flipFlashColor" to 0,
    "pageLayout" to "SINGLE", "useSSIV" to true, "landscapeZoom" to false,
    "panNavigation" to true, "doublePageShift" to 0, "doublePageSplit" to false, "doublePageReverse" to false,
    "autoWebtoon" to true, "chapterTransition" to true, "centerGapType" to 0,
    "autoDoubleLandscape" to false, "clickFlipInvertH" to false, "forceFullDecode" to false,
    "brightness" to 0, "screenBrightness" to 0, "contrast" to 100, "gamma" to 100,
    "brightnessEnabled" to false, "screenBrightnessEnabled" to false, "contrastEnabled" to false, "gammaEnabled" to false,
    "textureEnabled" to false, "texturePresetId" to 0, "textureAlpha" to 15, "textureConcentration" to 100, "textureScope" to 0,
    "ambienceSound" to "", "ambienceVolume" to 60,
    "fitScreen" to false, "seamlessNextVolume" to true, "tileModeEnabled" to false,
    "tallSplitEnabled" to true, "downsampleTier" to "STANDARD", "clickFlipType" to "LEFT_RIGHT",
    "scrollMode" to "HORIZONTAL_LEFT",
)

// ── 行注册表（顺序 = 页面展示顺序）─────────────────────────────────────────

private val pct: (Float) -> String = { "${(it * 100).toInt()}%" }

internal val PREF_ROWS: List<PrefRow> = listOf(
    // 翻页与模式
    PrefRow(ReaderScope.READ_MODE_KEY, "阅读模式", "翻页与模式", PrefEditor.ReadMode),
    PrefRow("pageLayout", "单双页布局", "翻页与模式", PrefEditor.Chips(
        listOf("单页" to "SINGLE", "双页" to "DOUBLE", "双页跳过首页" to "DOUBLE_SHIFTED"))),
    PrefRow("orientation", "屏幕方向", "翻页与模式", PrefEditor.Chips(
        listOf("默认" to -1, "跟随系统" to -2, "竖屏" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            "横屏" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            "锁定竖屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            "锁定横屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))),
    PrefRow("autoFlipInterval", "自动翻页间隔", "翻页与模式", PrefEditor.Slider(0f, 30000f, 29,
        { if (it <= 0f) "关闭" else if (it % 1000f == 0f) "${(it / 1000f).toInt()}秒" else String.format(java.util.Locale.US, "%.1f秒", it / 1000f) })),
    PrefRow("isFlipAnimation", "翻页动画", "翻页与模式", PrefEditor.Group(listOf(
        PrefEditor.Part("isFlipAnimation", "翻页动画（点击翻页过渡）", PrefEditor.Toggle()),
        PrefEditor.Part("isPageCurlAnimation", "仿真翻页Beta（仅静图左右翻页生效）", PrefEditor.Toggle()),
        PrefEditor.Part("isCurlContentAreaOnly", "翻页仅限画面区域", PrefEditor.Toggle()),
        PrefEditor.Part("curlBackContentAlpha", "背面透出程度（默认10%）", PrefEditor.Slider(0f, 1f, 19, pct)),
        PrefEditor.Part("curlShadowAlpha", "翻页阴影浓度（0=关闭，默认20%）", PrefEditor.Slider(0f, 0.5f, 9, pct)),
        PrefEditor.Part("spineShadowAlpha", "书脊阴影浓度（双页书脊沟，默认9%）", PrefEditor.Slider(0f, 0.3f, 29, pct)),
    )), subKeys = listOf("isPageCurlAnimation", "isCurlContentAreaOnly", "curlBackContentAlpha", "curlShadowAlpha", "spineShadowAlpha")),
    // 画面
    PrefRow("fitScreen", "画面缩放", "画面与缩放", PrefEditor.Chips(
        listOf("完整显示" to true, "铺满屏幕" to false)),
        subKeys = listOf()),
    PrefRow("landscapeZoom", "自动放大横向图片", "画面与缩放", PrefEditor.Toggle(info = "平板上建议关闭，关闭时宽图完整显示")),
    PrefRow("useSSIV", "高清缩放", "画面与缩放", PrefEditor.Toggle()),
    PrefRow("doublePageSplit", "拆分双页", "画面与缩放", PrefEditor.Group(listOf(
        PrefEditor.Part("doublePageSplit", "拆分双页（跨页图拆成左右两页）", PrefEditor.Toggle()),
        PrefEditor.Part("doublePageReverse", "反转双页", PrefEditor.Toggle()),
    )), subKeys = listOf("doublePageReverse")),
    PrefRow("centerGapType", "双页中缝", "画面与缩放", PrefEditor.Chips(
        listOf("贴合" to 0, "窄缝" to 1, "中缝" to 2, "宽缝" to 3))),
    // 滤镜
    PrefRow("isGrayscale", "灰度模式", "画面滤镜", PrefEditor.Toggle()),
    PrefRow("isInvertColors", "反色", "画面滤镜", PrefEditor.Toggle()),
    PrefRow("brightness", "图片亮度", "画面滤镜", PrefEditor.Group(listOf(
        PrefEditor.Part("brightnessEnabled", "启用图片亮度", PrefEditor.Toggle()),
        PrefEditor.Part("brightness", "亮度（默认 0）", PrefEditor.Slider(-100f, 100f, 199, { "${it.toInt()}" })),
    )), subKeys = listOf("brightnessEnabled", "brightness")),
    PrefRow("screenBrightness", "屏幕亮度", "画面滤镜", PrefEditor.Group(listOf(
        PrefEditor.Part("screenBrightnessEnabled", "启用屏幕亮度", PrefEditor.Toggle()),
        PrefEditor.Part("screenBrightness", "屏幕亮度（默认 0）", PrefEditor.Slider(-100f, 100f, 199, { "${it.toInt()}" })),
    )), subKeys = listOf("screenBrightnessEnabled", "screenBrightness")),
    PrefRow("contrast", "对比度", "画面滤镜", PrefEditor.Group(listOf(
        PrefEditor.Part("contrastEnabled", "启用对比度", PrefEditor.Toggle()),
        PrefEditor.Part("contrast", "对比度（默认 100）", PrefEditor.Slider(0f, 200f, 199, { "${it.toInt()}" })),
    )), subKeys = listOf("contrastEnabled", "contrast")),
    PrefRow("gamma", "伽马", "画面滤镜", PrefEditor.Group(listOf(
        PrefEditor.Part("gammaEnabled", "启用伽马", PrefEditor.Toggle()),
        PrefEditor.Part("gamma", "伽马（默认 100）", PrefEditor.Slider(50f, 150f, 99, { "${it.toInt()}" })),
    )), subKeys = listOf("gammaEnabled", "gamma")),
    PrefRow("textureEnabled", "纸张纹理", "画面滤镜", PrefEditor.Group(listOf(
        PrefEditor.Part("textureEnabled", "纸张纹理", PrefEditor.Toggle()),
        PrefEditor.Part("texturePresetId", "纸张类型", PrefEditor.Chips(PaperTexture.PRESETS.mapIndexed { i, (_, label) -> label to i })),
        PrefEditor.Part("textureConcentration", "纸感浓度（默认100%=真实纸感）", PrefEditor.Slider(0f, 100f, 99, { "${it.toInt()}%" })),
        PrefEditor.Part("textureScope", "生效范围", PrefEditor.Chips(listOf("仅图片" to 0, "全屏幕" to 1))),
    )), subKeys = listOf("texturePresetId", "textureConcentration", "textureScope", "textureAlpha")),
    // 裁白边与边距
    PrefRow("isTrimWhiteBorder", "裁白边", "裁白边与边距", PrefEditor.Group(listOf(
        PrefEditor.Part("isTrimWhiteBorder", "裁白边", PrefEditor.Toggle()),
        PrefEditor.Part("trimThreshold", "检测阈值（默认 220）", PrefEditor.Slider(180f, 250f, 69, { "阈值 ${it.toInt()}" })),
        PrefEditor.Part("trimWhiteRatio", "白色比例（默认90%）", PrefEditor.Slider(0.5f, 0.95f, 45, pct)),
    )), subKeys = listOf("trimThreshold", "trimWhiteRatio", "trimFactor")),
    PrefRow("imageMarginH", "侧边留白", "裁白边与边距", PrefEditor.Slider(-20f, 50f, 70, { "${it.toInt()}dp" })),
    // 墨水屏
    PrefRow("isEinkMode", "墨水屏模式", "墨水屏", PrefEditor.Group(listOf(
        PrefEditor.Part("isEinkMode", "墨水屏模式", PrefEditor.Toggle()),
        PrefEditor.Part("isEinkDither", "灰阶抖动", PrefEditor.Toggle()),
        PrefEditor.Part("einkDitherAlgorithm", "抖动算法", PrefEditor.Chips(listOf("细腻" to 0, "胶片颗粒" to 1, "柔和" to 2, "规则纹理" to 3))),
        PrefEditor.Part("einkEndpointPassthrough", "白底保护（档位越高白底越干净）", PrefEditor.Slider(0f, 8f, 7, { "档位 ${it.toInt()}" })),
        PrefEditor.Part("einkDitherLevels", "灰阶档位（越大过渡越平滑）", PrefEditor.Chips(listOf("16级" to 16, "32级" to 32, "64级" to 64))),
    )), subKeys = listOf("isEinkDither", "einkDitherAlgorithm", "einkEndpointPassthrough", "einkDitherLevels")),
    PrefRow("einkDecodeSize", "解码分辨率", "墨水屏", PrefEditor.Chips(
        listOf("1072·6寸" to 1072, "1440·8寸" to 1440, "1920·默认" to 1920, "原图" to 4096))),
    PrefRow("einkUiHideDelay", "界面隐藏时间", "墨水屏", PrefEditor.Chips(
        listOf("3秒" to 3000L, "6秒" to 6000L, "10秒" to 10000L, "不隐藏" to 0L))),
    PrefRow("isFlipFlashEnabled", "翻页闪烁", "墨水屏", PrefEditor.Group(listOf(
        PrefEditor.Part("isFlipFlashEnabled", "翻页时闪烁", PrefEditor.Toggle()),
        PrefEditor.Part("flipFlashDuration", "持续时间", PrefEditor.Slider(50f, 1500f, 14, { "${it.toInt()}ms" })),
        PrefEditor.Part("flipFlashInterval", "间隔页数", PrefEditor.Slider(1f, 10f, 9, { "每${it.toInt()}页" })),
        PrefEditor.Part("flipFlashColor", "闪烁颜色", PrefEditor.Chips(listOf("白色" to 0, "黑色" to 1, "黑白两段" to 2))),
    )), subKeys = listOf("flipFlashDuration", "flipFlashInterval", "flipFlashColor")),
    // 性能与解码
    PrefRow("pdfQuality", "PDF 清晰度", "性能与解码", PrefEditor.Slider(1f, 5f, 15, { String.format(java.util.Locale.US, "%.1fx", it) })),
    PrefRow("tileModeEnabled", "按需解码", "性能与解码", PrefEditor.Toggle(info = "只解码可见区域，内存占用更低；裁白边开启时自动停用")),
    PrefRow("tallSplitEnabled", "长图分割", "性能与解码", PrefEditor.Toggle(info = "超长 PDF 页与长图切割分段渲染，长图不再发糊")),
    PrefRow("downsampleTier", "降采样档位", "性能与解码", PrefEditor.Chips(
        listOf("标准" to "STANDARD", "高清" to "HD", "超清" to "ULTRA_HD", "原图" to "ORIGINAL"))),
    PrefRow("forceFullDecode", "强制完整解码", "性能与解码", PrefEditor.Toggle()),
    // 氛围音
    PrefRow("ambienceSound", "氛围音", "氛围音", PrefEditor.AmbienceSound),
    PrefRow("ambienceVolume", "氛围音音量", "氛围音", PrefEditor.Slider(0f, 100f, 19, { "${it.toInt()}%" })),
    // 阅读行为
    PrefRow("readBackground", "阅读背景", "阅读行为", PrefEditor.Chips(
        listOf("白色" to "WHITE", "黑色" to "DARK", "跟随主题" to "DEFAULT"))),
    PrefRow("isShowPageNum", "显示页码", "阅读行为", PrefEditor.Toggle()),
    PrefRow("isKeepScreenOn", "保持亮屏", "阅读行为", PrefEditor.Toggle()),
    PrefRow("isImmersiveMode", "沉浸模式", "阅读行为", PrefEditor.Toggle(info = "重进阅读后生效")),
    PrefRow("seamlessNextVolume", "无缝续读下一卷", "阅读行为", PrefEditor.Toggle(info = "读到卷末自动进入同书下一卷，无过渡页")),
    PrefRow("autoWebtoon", "自动进入条漫模式", "阅读行为", PrefEditor.Toggle(info = "新书首开按图片宽高比智能判定")),
    PrefRow("autoDoubleLandscape", "横屏自动双页", "阅读行为", PrefEditor.Toggle(info = "仅页面布局为单页时生效")),
    PrefRow("clickFlipType", "点击翻页区域", "阅读行为", PrefEditor.Chips(
        listOf("关闭" to "NO", "左右分区" to "LEFT_RIGHT", "L型" to "L_SHAPE"))),
    PrefRow("clickFlipInvertH", "点击翻页左手镜像", "阅读行为", PrefEditor.Toggle(info = "开启后：点右侧=上一页、点左侧=下一页")),
    PrefRow("panNavigation", "画面拖动导航", "阅读行为", PrefEditor.Toggle()),
)

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

/** 行尾摘要：当前全局默认值的短文案 */
internal fun prefSummary(prefs: SharedPreferences, row: PrefRow): String {
    val primary = when (val ed = row.editor) {
        is PrefEditor.Group -> ed.parts.first().key
        else -> row.key
    }
    return when (val ed = row.editor) {
        is PrefEditor.ReadMode -> {
            val v = prefs.getInt(ReaderScope.READ_MODE_GLOBAL_KEY, -1)
            if (v in Mode.entries.indices) Mode.entries[v].label else "按书籍自动"
        }
        is PrefEditor.AmbienceSound -> when (val v = prefGlobalValue(prefs, primary) as? String ?: "") {
            "" -> "关"; "rain" -> "下雨"; else -> v.substringBeforeLast('.').take(8)
        }
        is PrefEditor.Chips -> {
            val cur = prefGlobalValue(prefs, primary)
            ed.options.firstOrNull { it.second == cur }?.first ?: cur?.toString() ?: "默认"
        }
        is PrefEditor.Slider -> ed.fmt((prefGlobalValue(prefs, primary) as? Number)?.toFloat() ?: ed.min)
        is PrefEditor.Toggle -> if (prefGlobalValue(prefs, primary) == true) "开" else "关"
        is PrefEditor.Group -> when (val cur = prefGlobalValue(prefs, primary)) {
            is Boolean -> if (!cur) "关"
            else {
                // 开：带出首个非默认子项的摘要（纹理=图案名，亮度=数值，卷页=浓度）
                val sub = ed.parts.drop(1).firstOrNull { p ->
                    prefGlobalValue(prefs, p.key) != PREF_DEFAULTS[p.key]
                }
                when {
                    sub != null && sub.editor is PrefEditor.Chips ->
                        sub.editor.options.firstOrNull { it.second == prefGlobalValue(prefs, sub.key) }?.first ?: "开"
                    sub != null && sub.editor is PrefEditor.Slider ->
                        sub.editor.fmt((prefGlobalValue(prefs, sub.key) as Number).toFloat())
                    else -> "开"
                }
            }
            else -> "默认"
        }
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
    var filter by remember { mutableStateOf(0) } // 0=全部 1=本书生效 2=全局生效 3=已单独设置
    var sheetRow by remember { mutableStateOf<PrefRow?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun notify(t: String) { toast = t }
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
                val base = k.substringAfter("book_").substringAfter('_')
                map[base] = (map[base] ?: 0) + 1
                books.add(k.substringBefore('_'))
            } else if (k.startsWith("read_mode_")) {
                map[ReaderScope.READ_MODE_KEY] = (map[ReaderScope.READ_MODE_KEY] ?: 0) + 1
                books.add(k.removePrefix("read_mode_"))
            }
        }
        map to books.size
    }
    fun coveredOf(row: PrefRow) = row.allKeys.maxOf { countByItem[it] ?: 0 }
    val totalCoveredRows = PREF_ROWS.count { coveredOf(it) > 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读偏好") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
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
                                "开启后，在书里调设置只记在那本书上；关闭后书里的调整退出阅读不保留",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = masterOn,
                            onCheckedChange = { on ->
                                masterOn = on
                                prefs.edit().putBoolean(ReaderScope.MASTER_KEY, on).apply()
                                notify(
                                    if (on) "已恢复：书里的调整会记住（共 $bookCount 本书有单独设置）"
                                    else "已暂停：书里的调整退出阅读不保留",
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "全局预设 ${PREF_ROWS.count { ReaderScope.presetScope(it.key) == ReaderScope.SCOPE_GLOBAL }} 项 · " +
                            "本书预设 ${PREF_ROWS.count { ReaderScope.presetScope(it.key) == ReaderScope.SCOPE_BOOK }} 项 · " +
                            "已单独设置 $totalCoveredRows 项（$bookCount 本书）",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ── 过滤钮 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val presets = listOf("全部 ${PREF_ROWS.size}" to 0, "本书生效" to 1, "全局生效" to 2, "已单独设置 $totalCoveredRows" to 3)
                presets.forEach { (label, idx) ->
                    val count = when (idx) {
                        1 -> PREF_ROWS.count { ReaderScope.isBookScope(prefs, it.key) }
                        2 -> PREF_ROWS.count { !ReaderScope.isBookScope(prefs, it.key) }
                        3 -> totalCoveredRows
                        else -> PREF_ROWS.size
                    }
                    FilterChip(
                        selected = filter == idx,
                        onClick = { filter = idx },
                        enabled = count > 0 || idx == 0,
                        label = { Text(if (idx == 1 || idx == 2) "$label $count" else label, fontSize = 12.sp) },
                    )
                }
            }

            // ── 分组列表 ──
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                PREF_ROWS.groupBy { it.group }.forEach { (group, rows) ->
                    val visible = rows.filter {
                        when (filter) {
                            1 -> ReaderScope.isBookScope(prefs, it.key)
                            2 -> !ReaderScope.isBookScope(prefs, it.key)
                            3 -> coveredOf(it) > 0
                            else -> true
                        }
                    }
                    if (visible.isEmpty()) return@forEach
                    item(key = "g_$group") {
                        Text(
                            group, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(visible.size, key = { visible[it].key }) { idx ->
                        val row = visible[idx]
                        val covered = coveredOf(row)
                        val isBook = ReaderScope.isBookScope(prefs, row.key)
                        val locked = row.key in ReaderScope.CONDITIONAL_LOCK_KEYS && ReaderScope.conditionalLockActive(prefs)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { sheetRow = row }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.label, fontSize = 15.sp)
                                Text(
                                    prefSummary(prefs, row),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (locked) {
                                Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text("全局接管", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    if (isBook) "本书" else "全局",
                                    fontSize = 11.sp,
                                    color = if (isBook) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.background(
                                        if (isBook) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(6.dp),
                                    ).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (covered > 0) {
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.MenuBook, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Spacer(Modifier.width(3.dp))
                                    Text("单独·${covered}本", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }

    // ── 单项底部面板：作用域切换 + 值编辑 + 覆盖清理 ──
    sheetRow?.let { row ->
        val locked = row.key in ReaderScope.CONDITIONAL_LOCK_KEYS && ReaderScope.conditionalLockActive(prefs)
        val isBook = ReaderScope.isBookScope(prefs, row.key)
        val covered = coveredOf(row)
        var writeToastShown by remember { mutableStateOf(false) }
        val onWrite: (String, Any) -> Unit = { k, v ->
            ReaderScope.writeGlobal(prefs, k, v)
            version++
            if (!writeToastShown) {
                writeToastShown = true
                notify("已保存，对所有书生效（单独设置过的书不受影响）")
            }
        }
        ModalBottomSheet(onDismissRequest = { sheetRow = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
                Text(row.label, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Spacer(Modifier.height(12.dp))

                // ── 作用域 ──
                Text("生效范围", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isBook,
                        onClick = {
                            row.allKeys.forEach { ReaderScope.setScope(prefs, it, ReaderScope.SCOPE_GLOBAL) }
                            version++
                            notify("在书里调这项会对所有书生效")
                        },
                        enabled = !locked,
                        label = { Text("全局生效") },
                    )
                    FilterChip(
                        selected = isBook,
                        onClick = {
                            row.allKeys.forEach { ReaderScope.setScope(prefs, it, ReaderScope.SCOPE_BOOK) }
                            version++
                            notify("在书里调这项，只记在那一本书上")
                        },
                        enabled = !locked,
                        label = { Text("本书单独") },
                    )
                }
                Text(
                    when {
                        locked -> "全局翻页闪烁开着，整组接管中；可在 阅读设置 › 墨水屏 关闭后再单独设置"
                        isBook -> "这项预设按书记忆：书里调过只影响那本书，没调过的书跟全局默认"
                        else -> "这项预设全局：书里调它对所有书生效"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                // ── 值编辑（全局默认值，控件与阅读界面设置同款）──
                Text("全局默认值", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                PrefValueEditor(row, prefs, onWrite)
                Spacer(Modifier.height(10.dp))

                // ── 覆盖清理 ──
                if (covered > 0) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("有 $covered 本书单独调过这项，不受全局影响", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            notify("已清除 $covered 本书的此项设置")
                        }
                    }) { Text("清除这些书的单独设置", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── 恢复默认（分层：仅清所有书的单独设置）──
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认") },
            text = {
                Text(
                    "将清除所有书里单独调过的设置（共 $bookCount 本书），之后进任何一本书都按全局默认显示。\n" +
                        "全局默认值不动，可以在阅读设置里重置。\n" +
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
                        }
                        version++
                        showResetDialog = false
                        notify("已清除所有书的单独设置")
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

// ── 值编辑控件（与阅读界面设置同款：开关行 / 滑条 / 选项块）────────────────

@Composable
private fun PrefValueEditor(row: PrefRow, prefs: SharedPreferences, onWrite: (String, Any) -> Unit) {
    when (val ed = row.editor) {
        is PrefEditor.ReadMode -> ReadModeEditor(prefs, onWrite)
        is PrefEditor.AmbienceSound -> AmbienceEditor(prefs, onWrite)
        is PrefEditor.Group -> {
            val primary = ed.parts.first()
            val primaryOn = (prefGlobalValue(prefs, primary.key) as? Boolean) ?: false
            EditorPart(primary.label, primary.key, primary.editor, prefs, onWrite)
            if (primaryOn) {
                Column(Modifier.padding(start = 8.dp)) {
                    ed.parts.drop(1).forEach { p -> EditorPart(p.label, p.key, p.editor, prefs, onWrite) }
                }
            }
        }
        else -> EditorPart(row.label, row.key, ed, prefs, onWrite)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorPart(label: String, key: String, ed: PrefEditor, prefs: SharedPreferences, onWrite: (String, Any) -> Unit) {
    when (ed) {
        is PrefEditor.Toggle -> {
            var v by remember(key) { mutableStateOf((prefGlobalValue(prefs, key) as? Boolean) ?: false) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 15.sp)
                    ed.info?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = v, onCheckedChange = { on -> v = on; onWrite(key, on) })
            }
        }
        is PrefEditor.Slider -> {
            var v by remember(key) { mutableStateOf((prefGlobalValue(prefs, key) as? Number)?.toFloat() ?: ed.min) }
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(ed.fmt(v), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = v,
                    onValueChange = { v = it },
                    onValueChangeFinished = {
                        onWrite(key, if (ed.isFloat) v else Math.round(v))
                    },
                    valueRange = ed.min..ed.max, steps = ed.steps, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is PrefEditor.Chips -> {
            var v by remember(key) { mutableStateOf(prefGlobalValue(prefs, key)) }
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(label, fontSize = 14.sp)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ed.options.forEach { (optLabel, value) ->
                        FilterChip(
                            selected = v == value,
                            onClick = { v = value; onWrite(key, value) },
                            label = { Text(optLabel, fontSize = 13.sp) },
                        )
                    }
                }
            }
        }
        else -> {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadModeEditor(prefs: SharedPreferences, onWrite: (String, Any) -> Unit) {
    var v by remember { mutableStateOf(prefs.getInt(ReaderScope.READ_MODE_GLOBAL_KEY, -1)) }
    Column {
        Text(
            "新书第一次打开按这个模式进入；在书里切换模式按「生效范围」记忆",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf("按书籍自动" to -1) + Mode.entries.map { it.label to it.ordinal }
            options.forEach { (label, ord) ->
                FilterChip(
                    selected = v == ord,
                    onClick = { v = ord; onWrite(ReaderScope.READ_MODE_KEY, ord) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmbienceEditor(prefs: SharedPreferences, onWrite: (String, Any) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var imported by remember { mutableStateOf(listImportedAmbience(ctx)) }
    var v by remember { mutableStateOf(prefGlobalValue(prefs, "ambienceSound") as? String ?: "") }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val name = withContext(Dispatchers.IO) { importAmbienceFile(ctx, uri) }
            if (name != null) {
                imported = listImportedAmbience(ctx)
                v = name
                onWrite("ambienceSound", name)
            }
        }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("关闭" to "", "下雨" to "rain").forEach { (label, value) ->
            FilterChip(
                selected = v == value,
                onClick = { v = value; onWrite("ambienceSound", value) },
                label = { Text(label, fontSize = 13.sp) },
            )
        }
        imported.forEach { name ->
            FilterChip(
                selected = v == name,
                onClick = { v = name; onWrite("ambienceSound", name) },
                label = { Text(name.substringBeforeLast('.').take(10), fontSize = 13.sp) },
            )
        }
        FilterChip(
            selected = false,
            onClick = { importLauncher.launch(arrayOf("audio/*")) },
            label = { Text("+ 导入", fontSize = 13.sp) },
        )
    }
}
