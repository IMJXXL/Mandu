package com.mangareader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mangareader.data.Bookmark
import com.mangareader.data.ReadingProgress
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.viewer.NaturalComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// ★ 缩略图并发闸：每个格子各自 LaunchedEffect 触发全页字节读（loadPageRaw 阻塞不可取消），
//   长章快速滑动几十个并发 5-30MB 字节数组 = 内存/IO 尖峰。上限 3 足够填满滚动预取
private val thumbLoadSemaphore = kotlinx.coroutines.sync.Semaphore(3)

/**
 * §36 目录子项阅读状态徽章。
 *   复刻 Library.BookCard 的三态语义，但作用在"书内目录子项"（文件夹 / zip / cbz / cbr / cb7 /
 *   7z / epub / pdf / 单图 / ...）上，让用户在打开目录时一眼看出哪些章节读没读。
 *
 *   ★ §37 复用：Reader.ItemGrid（书内满屏子项 grid）也用这套 TocItemStatus + statusForItem，
 *     把原来右上角"文件夹/压缩包"类型徽章替换为阅读状态徽章，与书架/目录语义统一。
 *
 *   匹配策略（反向匹配，避开 chPath 形态脆弱的复杂度）：
 *     - dir=false 文件子项：在该 book 的 reading_progress 列表里，找 path 末段（最后一个 `/` 或 `|` 之后，
 *       去掉扩展名）与 item.name（去扩展名）相等的记录；多条命中取 updatedAt 最新；
 *       completed=true → 已读完；lastPage>0 → 阅读中；无 → 未读。
 *     - dir=true 子夹（§37(15)）：原本只走与文件子项相同的末段名称匹配 → 中间层文件夹如
 *       "美丽新世界（全）/" 永远命中不到（用户从没直接"打开"它阅读，progress 全在它下面的 zip 上）→ 返回 UNREAD 错判。
 *       §37(15) 修法：dir=true 分发到 statusForDirItem，按 SAF docId 前缀聚合其下所有归属叶子 progress 三态。
 *
 *   色板与 Library 一致：已读完 #81C784、阅读中 #FFB74D、未读 #E57373。
 */
// ★ §36 目录子项阅读状态徽章——已提升为 public 供 Reader.ItemGrid 复用（子项满屏 grid
//   也用同一套徽章替换原"文件夹/压缩包"类型标签）。
enum class TocItemStatus(val label: String, val color: Color) {
    FINISHED("已读完", Color(0xFF81C784)),
    READING("阅读中", Color(0xFFFFB74D)),
    UNREAD("未读", Color(0xFFE57373));
}

fun statusForItem(item: ArchiveEngine.Item, progresses: List<ReadingProgress>): TocItemStatus {
    if (item.name.isEmpty()) return TocItemStatus.UNREAD

    // ★ §37(15) dir=true 子文件夹：递归聚合其下所有叶子文件的 progress 状态。
    //   子文件夹如 "美丽新世界（全）/" 本身在 reading_progress 表里可能没有直接 progress 记录
    //   （用户从没直接"打开"这个文件夹阅读），但其下的 zip 文件有 progress。
    //   归属判断：item.doc（SAF docId，如 "primary:浩克下载/local/美丽新世界（全）"）是
    //   progress.path 中文件 docId 段的上级目录前缀。
    //   三态聚合规则（与 syncBookStatusFromProgress 一致）：
    //     - 所有归属叶子 completed=true → FINISHED
    //     - 有 completed=true 但非全部完成，或有 lastPage>0 的进度 → READING
    //     - 全无 progress → UNREAD
    if (item.dir) {
        return statusForDirItem(item, progresses)
    }

    // 去掉扩展名（zip/cbz/cbr/cb7/7z/epub/pdf/...），与 reading_progress.path 形态对齐
    val baseName = item.name.substringBeforeLast('.').ifEmpty { item.name }
    var bestMatch: ReadingProgress? = null
    for (p in progresses) {
        // p.path 形态多样（a: 压缩包路径含 SAF 嵌套目录 / d: 子夹 / z: 子卷）：
        //   a:auth|tree|doc|ext              （旧式 zip 单文件，doc 中可能含 /local/xxx.zip，末尾 |ext）
        //   a:auth|tree|/nested/书名.zip|ext （嵌套 zip）
        //   d:auth|tree|doc                  （d: 子夹）
        //   z:archivePath//subPath/          （z: 子卷）
        // 取 logicTail：优先拿 path 最后一个 "/" 后面的 segment（避开 auth|tree 前缀里的 |）：如 "美丽新世界1-54.zip|zip"
        // 再剥掉末尾再次出现的 "|ext" 后缀（如 strip |zip），得到原始尾 "美丽新世界1-54.zip"。
        val afterSlash = p.path.substringAfterLast('/')
        val tailRaw = if (afterSlash.isNotEmpty() && afterSlash.contains('|')) afterSlash.substringBeforeLast('|')
                      else if (afterSlash.isNotEmpty()) afterSlash
                      else p.path.substringAfterLast('|')  // path 无 "/" → 直接取最后一个 | 后的段
        // tailRaw 形如 "美丽新世界1-54.zip" 或 "Volume01/" 或 "subDir"
        val tailBase = tailRaw.substringBeforeLast('.').ifEmpty { tailRaw }
        // 去扩展名后 base 名相同（大小写不敏感）即命中；多条命中取 updatedAt 最新作权威进度
        if (tailBase.equals(baseName, ignoreCase = true)) {
            if (bestMatch == null || p.updatedAt > bestMatch.updatedAt) bestMatch = p
        }
    }
    return when {
        bestMatch == null -> TocItemStatus.UNREAD
        bestMatch.completed -> TocItemStatus.FINISHED
        bestMatch.lastPage > 0 -> TocItemStatus.READING
        else -> TocItemStatus.UNREAD
    }
}

/**
 * ★ §37(15) dir=true 子文件夹的三态聚合：遍历 progresses 找归属在该子文件夹下的叶子 progress 记录，
 *   按聚合规则判定 FINISHED / READING / UNREAD。
 *
 *   归属判断：item.doc（如 "primary:浩克下载/local/美丽新世界（全）"）是 progress.path 中
 *   文件 docId 段的上级目录前缀。progress.path 的 a: 格式为 "a:auth|treeDocId|fileDocId|ext"，
 *   fileDocId 形如 "primary:浩克下载/local/美丽新世界（全）/美丽新世界1-54.zip"，
 *   以 item.doc + "/" 为前缀匹配即可命中该子文件夹下的所有叶子。
 *
 *   d: 子夹的 progress.path 格式为 "d:auth|tree|doc"，若该 doc 恰好等于 item.doc
 *   （顶层散落图片直接阅读过该子夹），也聚合进来。
 *
 *   递归：子文件夹下可能还有更深的子文件夹（其 progress.path 的 docId 同样含 item.doc 前缀），
 *   一并按 leaf progress 聚合即可获得递归等效结果。
 */
private fun statusForDirItem(item: ArchiveEngine.Item, progresses: List<ReadingProgress>): TocItemStatus {
    val folderPrefix = item.doc + "/"
    var totalLeaves = 0
    var completedLeaves = 0
    var anyProgress = false
    for (p in progresses) {
        // a: 格式：a:auth|tree|fileDocId|ext → 取 fileDocId（第 3 段）
        // d: 格式：d:auth|tree|doc → 取 doc（第 3 段）
        val belongs = when {
            p.path.startsWith("a:") -> {
                val parts = p.path.removePrefix("a:").split("|", limit = 4)
                if (parts.size >= 3) {
                    val fileDocId = parts[2]
                    fileDocId.startsWith(folderPrefix) || fileDocId == item.doc
                } else false
            }
            p.path.startsWith("d:") -> {
                val parts = p.path.removePrefix("d:").split("|", limit = 3)
                if (parts.size >= 3) parts[2] == item.doc else false
            }
            p.path.startsWith("z:") -> {
                // z:archivePath//subPath z: 内子卷的 archivePath 含原始 a: 路径；z: 子卷通常不嵌在 d: 子夹下
                val inner = p.path.removePrefix("z:")
                val sepIdx = inner.indexOf("//")
                if (sepIdx > 0) {
                    val archivePath = inner.substring(0, sepIdx)
                    archivePath.contains(folderPrefix) || archivePath.contains(item.doc)
                } else false
            }
            else -> false
        }
        if (!belongs) continue
        totalLeaves++
        if (p.completed) completedLeaves++
        else if (p.lastPage > 0) anyProgress = true
    }
    return when {
        totalLeaves == 0 -> TocItemStatus.UNREAD
        completedLeaves == totalLeaves -> TocItemStatus.FINISHED
        completedLeaves > 0 || anyProgress -> TocItemStatus.READING
        else -> TocItemStatus.UNREAD
    }
}

/** 书签章节名提取（复刻 HistoryScreen.chapterLabel 的尾段解析，z:/d:/a: 全路径→显示名） */
private fun bookmarkChapterLabel(path: String): String {
    return try {
        if (path.startsWith("z:")) {
            val inner = path.removePrefix("z:")
            val subPath = inner.substringAfter("//", "").trimEnd('/')
            if (subPath.isNotEmpty()) subPath.substringAfterLast('/').substringBeforeLast('.')
            else inner.substringBefore("//").substringAfterLast('/').substringBeforeLast('.')
        } else {
            val parts = path.removePrefix("a:").removePrefix("d:").split("|")
            val doc = parts.getOrNull(2) ?: path
            doc.substringAfterLast('/').substringAfterLast('%').substringBeforeLast('.')
                .ifEmpty { doc.substringAfterLast('/').substringBeforeLast('.') }
        }
    } catch (_: Exception) { path.takeLast(24) }
}

fun tocItemChPath(currentNavStack: List<String>, item: ArchiveEngine.Item): String? {
    val currentPath = currentNavStack.firstOrNull() ?: ""
    return if (currentPath.startsWith("z:")) {
        if (!item.dir) return null
        val inner = currentPath.removePrefix("z:")
        val sepIdx = inner.indexOf("//")
        if (sepIdx < 0) return null
        var archivePath = inner.substring(0, sepIdx)
        if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
        "z:${archivePath}//${item.subPath}"
    } else if (currentPath.startsWith("d:")) {
        if (item.dir) {
            "d:${item.auth}|${item.tree}|${item.doc}"
        } else {
            val ext = item.name.substringAfterLast(".").lowercase()
            if (ext.isEmpty()) null else "a:${item.auth}|${item.tree}|${item.doc}|$ext"
        }
    } else null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderTocSheet(
    displayItems: List<ArchiveEngine.Item>,
    currentNavStack: List<String>,
    viewing: Boolean,
    progress: List<ReadingProgress>,
    bookId: Long,
    onDismiss: () -> Unit,
    onChapterNavigate: (chapterPath: String) -> Unit,
    onItemOpen: (ArchiveEngine.Item) -> Unit,
    bookmarks: List<Bookmark> = emptyList(),
    onPageBookmarkClick: (Bookmark) -> Unit = {},
    onPageBookmarkDelete: (Bookmark) -> Unit = {},
    pageCount: Int = 0,
    currentPage: Int = 0,
    onPageClick: (Int) -> Unit = {},
    thumbLoader: (suspend (Int) -> Bitmap?)? = null,
    // ★ 单本单页豁免（2026-10-06）：soloCapable=双页相关布局开启（单页模式长按不触发）；
    //   soloPages=当前章已豁免页集合（键按章隔离，切换/重置回调由 Reader.kt 落库+实时生效）
    soloPages: Set<Int> = emptySet(),
    soloCapable: Boolean = false,
    onToggleSoloPage: (Int) -> Unit = {},
    onClearSoloPages: () -> Unit = {},
    subVolCovers: Map<String, Pair<String, Long>> = emptyMap(),
    onItemLongPress: (ArchiveEngine.Item) -> Unit = {},
) {
    var tocSortAsc by remember { mutableStateOf(true) }
    var tocTab by remember { mutableIntStateOf(if (displayItems.size > 1) 0 else 1) }
    val dateFormat = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    val sortedDisplayItems = remember(displayItems, tocSortAsc) {
        val base = displayItems.sortedWith(compareByDescending<ArchiveEngine.Item> { it.dir }.thenComparing({ it.name }, NaturalComparator))
        if (tocSortAsc) base else base.reversed()
    }
    val ctx = LocalContext.current
    val configuration = LocalConfiguration.current
    val portraitCols by com.hippo.ehviewer.Settings.thumbColumnsPortrait.valueFlow().collectAsState(initial = com.hippo.ehviewer.Settings.thumbColumnsPortrait.value)
    val landscapeCols by com.hippo.ehviewer.Settings.thumbColumnsLandscape.valueFlow().collectAsState(initial = com.hippo.ehviewer.Settings.thumbColumnsLandscape.value)
    val resolvedCols = LayoutColumnsResolver.currentValue(configuration.orientation, portraitCols, landscapeCols)
    val gridCols = LayoutColumnsResolver.toGridCols(resolvedCols)
    val thumbCache = remember { LruCache<Int, Bitmap>(20) }
    // ★ 章名显示跟随书架同款设置：智能解析渗透到目录/子项 + 超长滚动
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    val dispPrefs = remember { ctx0.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE) }
    val tocSmartOn = remember { dispPrefs.getBoolean("smart_title", false) }
    val tocT2s = remember { dispPrefs.getBoolean("t2s_enabled", false) }
    val tocMarquee = remember { dispPrefs.getBoolean("shelf_title_marquee", false) }
    val dispName: (String) -> String = remember(tocSmartOn, tocT2s) {
        { n -> if (tocSmartOn) com.mangareader.util.BookTitleParser.displayTitle(n, tocT2s) else n }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // ★ 1001 UI 审查 Phase3：横屏高度稀缺 → TOC 改右侧滑出全高抽屉；竖屏保持底部抽屉原样。
    //   内容只有一份（tocPanel），两种容器各自给高度约束
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val tocPanel: @Composable () -> Unit = {
        Column(
            if (isLandscape) Modifier.fillMaxHeight().padding(16.dp)
            else Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.8f).dp).padding(16.dp)
        ) {
            // ★ 三 Tab：目录 / 浏览 / 书签
            TabRow(
                selectedTabIndex = tocTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(selected = tocTab == 0, onClick = { tocTab = 0 }) {
                    Text("目录", modifier = Modifier.padding(vertical = 10.dp), fontSize = 14.sp)
                }
                Tab(selected = tocTab == 1, onClick = { tocTab = 1 }) {
                    Text("浏览", modifier = Modifier.padding(vertical = 10.dp), fontSize = 14.sp)
                }
                Tab(selected = tocTab == 2, onClick = { tocTab = 2 }) {
                    Text("书签 (${bookmarks.size})", modifier = Modifier.padding(vertical = 10.dp), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (tocTab == 2) {
                // ─── 书签页签 ───
                if (bookmarks.isEmpty()) {
                    Text("还没有书签（阅读中点底部「书签」按钮添加当前页）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(bookmarks.size) { i ->
                            val bm = bookmarks[i]
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onPageBookmarkClick(bm) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.BookmarkBorder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(bookmarkChapterLabel(bm.chapterPath), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("第 ${bm.page + 1} 页 · ${dateFormat.format(java.util.Date(bm.createdAt))}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    if (!bm.note.isNullOrEmpty()) {
                                        Text(bm.note!!, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                IconButton(onClick = { onPageBookmarkDelete(bm) }) {
                                    Icon(Icons.Default.DeleteOutline, "删除书签", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            } else if (tocTab == 0) {
                // ─── 目录页签 ───
                if (sortedDisplayItems.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("目录 (${sortedDisplayItems.size}章)", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { tocSortAsc = !tocSortAsc }) {
                            Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(4.dp))
                            Text(if (tocSortAsc) "正序" else "倒序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = gridCols,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sortedDisplayItems.size) { idx ->
                            val item = sortedDisplayItems[idx]
                            val cur = currentNavStack.lastOrNull() ?: ""
                            val isCurrent = viewing && item.subPath.isNotEmpty() && (cur.endsWith(item.subPath) && (cur.length == item.subPath.length || cur[cur.length - item.subPath.length - 1] == '/' || cur[cur.length - item.subPath.length - 1] == '\\'))
                            val status = remember(progress, item.name) { statusForItem(item, progress) }
                            val chPath = tocItemChPath(currentNavStack, item)
                            val subCover = chPath?.let { subVolCovers[it] }
                            val coverModel = remember(item.auth, item.tree, item.doc, item.subPath, subCover) {
                                ImageRequest.Builder(ctx)
                                    .data(if (subCover != null) com.mangareader.viewer.CoverData(subCover.first, dynOn = false) else item)
                                    .decoderFactory(com.mangareader.coil.CoverAnimGateDecoder)
                                    .apply {
                                        if (subCover != null) {
                                            memoryCacheKey("${subCover.first}|${subCover.second}")
                                            diskCacheKey("${subCover.first}|${subCover.second}")
                                        }
                                    }
                                    .build()
                            }
                            Column(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (item.dir && item.subPath.isNotEmpty()) {
                                                val currentPath = currentNavStack.firstOrNull() ?: ""
                                                if (currentPath.startsWith("z:")) {
                                                    val inner = currentPath.removePrefix("z:")
                                                    val sepIdx = inner.indexOf("//")
                                                    if (sepIdx >= 0) {
                                                        var archivePath = inner.substring(0, sepIdx)
                                                        if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
                                                        val chapterPath = "z:${archivePath}//${item.subPath}"
                                                        onChapterNavigate(chapterPath)
                                                    }
                                                } else if (currentPath.startsWith("d:")) {
                                                    onChapterNavigate("d:${item.auth}|${item.tree}|${item.doc}")
                                                }
                                            } else if (!item.dir) {
                                                onItemOpen(item)
                                            }
                                            onDismiss()
                                        },
                                        onLongClick = { if (chPath != null) onItemLongPress(item) },
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(0.72f)
                                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = coverModel,
                                        contentDescription = item.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                    if (isCurrent) {
                                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                                    }
                                    if (status != TocItemStatus.UNREAD) {
                                        Surface(
                                            Modifier.align(Alignment.TopEnd).padding(2.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            color = status.color.copy(alpha = 0.95f)
                                        ) {
                                            Text(
                                                text = status.label,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                fontSize = 8.sp,
                                                color = Color.White,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                Text(
                                    dispName(item.name),
                                    Modifier
                                        .then(if (tocMarquee) Modifier.basicMarquee() else Modifier)
                                        .padding(4.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.sp,
                                    maxLines = if (tocMarquee) 1 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    Text("无目录（单本漫画）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            } else {
                // ─── 浏览页签 ───
                if (pageCount > 0 && thumbLoader != null) {
                    // ★ 单本单页豁免：长按格子切换"此页单独显示"；有豁免时给全重置入口。
                    //   单页布局 soloCapable=false：长按不触发（无合并可言），零 UI 噪音
                    var soloDialogFor by remember { mutableStateOf(-1) }
                    if (soloCapable) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "长按页面可标记「单独显示」（不参与双页合并）",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (soloPages.isNotEmpty()) {
                                TextButton(onClick = onClearSoloPages, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text("重置豁免(${soloPages.size})", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    LazyVerticalGrid(
                        columns = gridCols,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(pageCount) { idx ->
                            var bitmap by remember(idx) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(idx) {
                                thumbCache.get(idx)?.let { bitmap = it; return@LaunchedEffect }
                                val bmp = withContext(Dispatchers.IO) { thumbLoadSemaphore.withPermit { thumbLoader?.invoke(idx) } }
                                if (bmp != null) thumbCache.put(idx, bmp)
                                bitmap = bmp
                            }
                            Column(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = { onPageClick(idx); onDismiss() },
                                        onLongClick = { if (soloCapable) soloDialogFor = idx },
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(0.72f)
                                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap!!.asImageBitmap(),
                                            contentDescription = "第${idx + 1}页",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Text("${idx + 1}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (idx == currentPage) {
                                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                                    }
                                    if (idx in soloPages) {
                                        Surface(
                                            Modifier.align(Alignment.TopEnd).padding(2.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.95f)
                                        ) {
                                            Text("独", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 8.sp, color = Color.White)
                                        }
                                    }
                                }
                                Text("${idx + 1}", Modifier.padding(2.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // ★ 豁免切换对话框（长按弹出）：按当前态给反向动作文案
                    if (soloDialogFor >= 0) {
                        val pageDisp = soloDialogFor + 1
                        val alreadySolo = soloDialogFor in soloPages
                        AlertDialog(
                            onDismissRequest = { soloDialogFor = -1 },
                            title = { Text("第 $pageDisp 页", fontSize = 18.sp) },
                            text = {
                                Text(
                                    if (alreadySolo) "此页当前为「单独显示」，不参与双页合并。恢复后将与相邻页重新配对。"
                                    else "将此页标记为「单独显示」？标记后该页不参与双页合并，跨页表实时重排（仅本书当前章生效）。",
                                    fontSize = 14.sp
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { onToggleSoloPage(soloDialogFor); soloDialogFor = -1 }) {
                                    Text(if (alreadySolo) "恢复合并" else "单独显示", color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { soloDialogFor = -1 }) { Text("取消") }
                            }
                        )
                    }
                } else {
                    Text("还没有可浏览的页面", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
        }
    }
    if (isLandscape) {
        // 右侧滑出全高抽屉（进入即带入场动画）
        val state = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visibleState = state,
                enter = androidx.compose.animation.slideInHorizontally { it },
                exit = androidx.compose.animation.slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(440.dp)
                        .clickable(interactionSource = null, indication = null, onClick = {}),
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    tocPanel()
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            sheetMaxWidth = 520.dp,
            dragHandle = null,  // ★ 顶部拖动横条纯装饰不可点，去掉把空间还给列表
        ) {
            tocPanel()
        }
    }
}
internal fun decodeThumbBytes(bytes: ByteArray): Bitmap? {
    val opts = BitmapFactory.Options()
    opts.inSampleSize = 4
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
