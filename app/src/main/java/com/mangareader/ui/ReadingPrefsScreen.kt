package com.mangareader.ui

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.ReaderScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读偏好（2026-10-03 九方评审定稿）：
 * - 总开关「本书改动单独记」：开=书内改动只记这本书；关=退出阅读不保留（临时值）。
 * - 全部阅读设置项按分组罗列；每项可切"单本生效/全局生效"（作用域注册表，显式指定豁免总开关）。
 * - 仅全局项不渲染切换器；条件锁定（翻页闪烁）显示接管说明。
 * - 值编辑单源：改值仍在阅读设置/书内面板；本页只管作用域与覆盖清理。
 * - 恢复默认（分层）：本页=仅清所有书的单独设置（全局值不动），执行前自动快照可回滚。
 */

/** 行注册表：key 必须与 ReaderScope 的键名一致 */
internal data class PrefRow(val key: String, val group: String, val label: String)

internal val PREF_ROWS: List<PrefRow> = listOf(
    // 翻页与方向
    PrefRow("scrollMode", "翻页与方向", "滚动方向"),
    PrefRow("pageLayout", "翻页与方向", "单双页 · 跳过首页"),
    PrefRow("isFlipAnimation", "翻页与方向", "翻页动画"),
    PrefRow("isPageCurlAnimation", "翻页与方向", "卷页动画"),
    PrefRow("isCurlContentAreaOnly", "翻页与方向", "卷页仅画面区域"),
    PrefRow("curlBackContentAlpha", "翻页与方向", "卷页背面透出"),
    PrefRow("curlShadowAlpha", "翻页与方向", "卷页阴影"),
    PrefRow("spineShadowAlpha", "翻页与方向", "书脊阴影"),
    PrefRow("autoFlipInterval", "翻页与方向", "自动翻页间隔"),
    // 单双页与画面缩放
    PrefRow("doublePageSplit", "双页与缩放", "双页裁半"),
    PrefRow("doublePageReverse", "双页与缩放", "双页左右互换"),
    PrefRow("centerGapType", "双页与缩放", "中缝宽度"),
    PrefRow("autoDoubleLandscape", "双页与缩放", "横屏自动双页"),
    PrefRow("useSSIV", "双页与缩放", "高清缩放"),
    PrefRow("landscapeZoom", "双页与缩放", "横屏缩放"),
    PrefRow("fitScreen", "双页与缩放", "适应屏幕"),
    // 画面与滤镜
    PrefRow("brightness", "画面与滤镜", "亮度"),
    PrefRow("screenBrightness", "画面与滤镜", "屏幕亮度"),
    PrefRow("contrast", "画面与滤镜", "对比度"),
    PrefRow("gamma", "画面与滤镜", "伽马"),
    PrefRow("isGrayscale", "画面与滤镜", "黑白"),
    PrefRow("isInvertColors", "画面与滤镜", "反色"),
    // 纸张纹理
    PrefRow("textureEnabled", "纸张纹理", "纸张纹理"),
    PrefRow("texturePresetId", "纸张纹理", "纹理图案"),
    PrefRow("textureConcentration", "纸张纹理", "纹理浓度"),
    PrefRow("textureScope", "纸张纹理", "纹理作用范围"),
    // 裁白边与边距
    PrefRow("isTrimWhiteBorder", "裁白边与边距", "裁白边"),
    PrefRow("imageMarginH", "裁白边与边距", "页边距"),
    // 墨水屏
    PrefRow("isEinkMode", "墨水屏", "墨水屏模式"),
    PrefRow("isEinkDither", "墨水屏", "灰阶抖动"),
    PrefRow("einkDitherAlgorithm", "墨水屏", "抖动算法"),
    PrefRow("einkDitherLevels", "墨水屏", "抖动级数"),
    PrefRow("einkEndpointPassthrough", "墨水屏", "端点直通"),
    PrefRow("einkDecodeSize", "墨水屏", "解码尺寸"),
    PrefRow("einkUiHideDelay", "墨水屏", "界面隐藏延时"),
    PrefRow("isFlipFlashEnabled", "墨水屏", "翻页闪烁"),
    // 性能与解码
    PrefRow("pdfQuality", "性能与解码", "PDF 清晰度"),
    PrefRow("tileModeEnabled", "性能与解码", "局部解码"),
    PrefRow("forceFullDecode", "性能与解码", "强制完整解码"),
    PrefRow("downsampleTier", "性能与解码", "降采样档位"),
    PrefRow("tallSplitEnabled", "性能与解码", "长图分割"),
    // 氛围音
    PrefRow("ambienceSound", "氛围音", "音效"),
    PrefRow("ambienceVolume", "氛围音", "音量"),
    // 阅读行为
    PrefRow("readBackground", "阅读行为", "阅读背景"),
    PrefRow("isShowPageNum", "阅读行为", "显示页码"),
    PrefRow("isKeepScreenOn", "阅读行为", "保持亮屏"),
    PrefRow("isImmersiveMode", "阅读行为", "沉浸模式"),
    PrefRow("orientation", "阅读行为", "屏幕方向"),
    PrefRow("clickFlipType", "阅读行为", "点击翻页区域"),
    PrefRow("clickFlipInvertH", "阅读行为", "点击翻页左手镜像"),
    PrefRow("panNavigation", "阅读行为", "画面拖动导航"),
    PrefRow("autoWebtoon", "阅读行为", "自动条漫"),
    PrefRow("chapterTransition", "阅读行为", "章节无缝续读"),
)

internal fun prefRawValue(prefs: SharedPreferences, key: String, bookId: Long? = null): String? {
    val bk = bookId?.let { "book_${it}_$key" }
    val raw: Any? = when {
        bk != null && prefs.contains(bk) -> prefs.all[bk]
        prefs.contains(key) -> prefs.all[key]
        else -> null
    }
    return when (raw) {
        null -> null
        is Boolean -> if (raw) "开" else "关"
        is Float -> String.format(java.util.Locale.US, "%.2f", raw)
        else -> raw.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingPrefsScreen(onBack: () -> Unit, onOpenReadingSettings: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var masterOn by remember { mutableStateOf(ReaderScope.masterOn(prefs)) }
    var regVersion by remember { mutableIntStateOf(0) } // 作用域/覆盖变更时重算
    var filter by remember { mutableStateOf(0) } // 0=全部 1=单独设置(有覆盖) 2=跟随全局
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

    // 覆盖统计：key → 覆盖它的书数
    val overrideCounts = remember(regVersion) {
        val map = mutableMapOf<String, Int>()
        var books = mutableSetOf<String>()
        prefs.all.keys.forEach { k ->
            if (k.startsWith("book_")) {
                val base = k.substringAfter("book_").substringAfter('_')
                map[base] = (map[base] ?: 0) + 1
                books.add(k.substringBefore('_'))
            }
        }
        map to books.size
    }
    val (countByItem, bookCount) = overrideCounts
    val totalCovered = countByItem.count { it.value > 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读偏好") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.RestartAlt, "恢复默认")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ── 总开关卡 ──
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("本书改动单独记", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "开启后，在书里调过的设置只记在这本书上，下次打开还是这样；关闭后所有书都用全局默认，书里的调整退出阅读就不保留（翻页动画等所有书共用的项除外）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = masterOn,
                        onCheckedChange = { on ->
                            masterOn = on
                            prefs.edit().putBoolean(ReaderScope.MASTER_KEY, on).apply()
                            notify(
                                if (on) "所有书的单独设置已恢复生效（共 $bookCount 本书有单独设置）"
                                else "已暂停所有书的单独设置（共 $totalCovered 项），进书按全局默认",
                            )
                        },
                    )
                }
            }

            // ── 过滤钮 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("全部 ${PREF_ROWS.size}" to 0, "单独设置 $totalCovered" to 1, "跟随全局 ${PREF_ROWS.size - totalCovered}" to 2)
                    .forEach { (label, idx) ->
                        val selected = filter == idx
                        val count = when (idx) { 1 -> totalCovered; 2 -> PREF_ROWS.size - totalCovered; else -> PREF_ROWS.size }
                        FilterChip(
                            selected = selected,
                            onClick = { filter = idx },
                            enabled = count > 0,
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
            }

            // ── 分组列表 ──
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                val groups = PREF_ROWS.groupBy { it.group }
                groups.forEach { (group, rows) ->
                    val groupCovered = rows.count { (countByItem[it.key] ?: 0) > 0 }
                    val visible = rows.filter {
                        when (filter) {
                            1 -> (countByItem[it.key] ?: 0) > 0
                            2 -> (countByItem[it.key] ?: 0) == 0
                            else -> true
                        }
                    }
                    if (visible.isEmpty()) return@forEach
                    item(key = "g_$group") {
                        Text(
                            (if (groupCovered > 0) "$group（$groupCovered 项单独）" else group),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(visible.size, key = { visible[it].key }) { idx ->
                        val row = visible[idx]
                        val covered = countByItem[row.key] ?: 0
                        val isLock = row.key in ReaderScope.CONDITIONAL_LOCK_KEYS
                        val isGlobal = row.key in ReaderScope.GLOBAL_ONLY_KEYS
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { sheetRow = row }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(row.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            when {
                                isLock -> {
                                    Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(4.dp))
                                    Text("全局锁定", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                isGlobal -> Text(
                                    "全局",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(6.dp),
                                    ).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                                covered > 0 -> Row(
                                    Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.MenuBook, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(Modifier.width(3.dp))
                                    Text("单独设置·${covered}本", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }

    // ── 单项底部面板 ──
    sheetRow?.let { row ->
        val isLock = row.key in ReaderScope.CONDITIONAL_LOCK_KEYS
        val isBookScopeNow = ReaderScope.isBookScope(prefs, row.key)
        val covered = countByItem[row.key] ?: 0
        ModalBottomSheet(onDismissRequest = { sheetRow = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
                Text(row.label, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                when {
                    isLock -> {
                        Text(
                            "全局已接管：这项跟随「设置 › 阅读设置 › 墨水屏」的翻页闪烁开关，关闭后可单独设置。",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text("生效范围", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !isBookScopeNow,
                                onClick = {
                                    ReaderScope.setScope(prefs, row.key, ReaderScope.SCOPE_GLOBAL)
                                    regVersion++
                                    notify("已恢复跟随全局")
                                },
                                label = { Text("跟随全局") },
                            )
                            FilterChip(
                                selected = isBookScopeNow,
                                onClick = {
                                    ReaderScope.setScope(prefs, row.key, ReaderScope.SCOPE_BOOK)
                                    regVersion++
                                    notify("此项已只对本书生效（在书里调整即记住）")
                                },
                                label = { Text("本书单独") },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (isBookScopeNow) "在书里调这项，只记在那本书上；没调过的书按全局默认显示。"
                            else "在书里调这项，所有书一起变。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (covered > 0) {
                            Spacer(Modifier.height(10.dp))
                            Text("有 $covered 本书单独调过这项，不受全局影响", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        // 清所有书的此项覆盖
                                        prefs.all.keys.filter { it.startsWith("book_") && it.endsWith("_${row.key}") }
                                            .forEach { prefs.edit().remove(it).apply() }
                                        regVersion++
                                    }
                                    notify("已清除 ${covered} 本书的此项设置")
                                }
                            }) { Text("清除 ${covered} 本书的此项设置", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                TextButton(onClick = { sheetRow = null; onOpenReadingSettings() }) {
                    Text("全局默认在 阅读设置 里修改 ›", fontSize = 13.sp)
                }
                Spacer(Modifier.height(18.dp))
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
                        "全局设置不动，可以在阅读设置里重置。\n" +
                        "改错了可以找回：会保留最近 3 次重置前的设置。\n" +
                        "阅读进度、书签、缓存不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            // 快照先行（可回滚）
                            val snap = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
                            val dir = java.io.File(ctx.filesDir, "pref_snapshots").apply { mkdirs() }
                            runCatching {
                                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                                    .format(java.util.Date()) + "_" + (0..999).random()
                                val TAB = 9.toChar(); val NL = 10.toChar()
                                val lines = mutableListOf("#pref_reset")
                                snap.all.forEach { (k, v) ->
                                    when (v) {
                                        is Boolean -> lines.add("b$TAB${k}$TAB$v")
                                        is Int -> lines.add("i$TAB${k}$TAB$v")
                                        is Long -> lines.add("l$TAB${k}$TAB$v")
                                        is Float -> lines.add("f$TAB${k}$TAB$v")
                                        is String -> lines.add("s$TAB${k}$TAB${v.replace("\\", "\\\\").replace(NL.toString(), "\\n").replace(TAB.toString(), "\\t")}")
                                    }
                                }
                                dir.listFiles()?.filter { it.name.startsWith("pref_reset_") }
                                    ?.sortedByDescending { it.name }?.drop(2)?.forEach { it.delete() }
                                java.io.File(dir, "pref_reset_$stamp.txt").writeText(lines.joinToString(NL.toString()))
                                // 清除所有 book_* 覆盖
                                snap.all.keys.filter { it.startsWith("book_") }.forEach { snap.edit().remove(it).apply() }
                            }
                        }
                        regVersion++
                        showResetDialog = false
                        notify("已清除所有书的单独设置")
                    }
                }) { Text("清空并重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { com.mangareader.data.ReaderPrefMigration.restoreLatestSnapshot(ctx) }
                        regVersion++
                        showResetDialog = false
                        notify(if (ok) "已恢复到上次重置前" else "没有可用的备份")
                    }
                }) { Text("撤销上次重置") }
            },
        )
    }
}
