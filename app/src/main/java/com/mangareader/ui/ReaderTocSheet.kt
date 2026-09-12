package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.Bookmark
import com.mangareader.data.ReadingProgress
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.viewer.NaturalComparator

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

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    var tocSortAsc by remember { mutableStateOf(true) }
    var tocTab by remember { mutableIntStateOf(0) }
    val dateFormat = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    val sortedDisplayItems = remember(displayItems, tocSortAsc) {
        val base = displayItems.sortedWith(compareByDescending<ArchiveEngine.Item> { it.dir }.thenComparing({ it.name }, NaturalComparator))
        if (tocSortAsc) base else base.reversed()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetMaxWidth = 520.dp
    ) {
        Column(Modifier.fillMaxWidth().height(400.dp).padding(16.dp)) {
            // ★ 书签 Tab：章节 / 书签 双页签（章节列表原样保留在页签 0）
            TabRow(
                selectedTabIndex = tocTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(selected = tocTab == 0, onClick = { tocTab = 0 }) {
                    Text("章节", modifier = Modifier.padding(vertical = 10.dp), fontSize = 14.sp)
                }
                Tab(selected = tocTab == 1, onClick = { tocTab = 1 }) {
                    Text("书签 (${bookmarks.size})", modifier = Modifier.padding(vertical = 10.dp), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (tocTab == 1) {
                // ─── 书签页签 ───
                if (bookmarks.isEmpty()) {
                    Text("暂无书签（阅读中点底部「书签」按钮添加当前页）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            } else if (sortedDisplayItems.size > 1) {
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
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sortedDisplayItems.size) { idx ->
                        val item = sortedDisplayItems[idx]
                        val cur = currentNavStack.lastOrNull() ?: ""
                        val isCurrent = viewing && item.subPath.isNotEmpty() && (cur.endsWith(item.subPath) && (cur.length == item.subPath.length || cur[cur.length - item.subPath.length - 1] == '/' || cur[cur.length - item.subPath.length - 1] == '\\'))
                        val status = remember(progress, item.name) { statusForItem(item, progress) }
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
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
                                            // ★ F2.2：d: 书目录点击 → 章节身份路径直达（原来 subPath 恒空导致两分支都不中、静默关闭）
                                            onChapterNavigate("d:${item.auth}|${item.tree}|${item.doc}")
                                        }
                                    } else if (!item.dir) {
                                        onItemOpen(item)
                                    }
                                    onDismiss()
                                }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                                // ★ §36 状态徽章——Surface + 小字，对齐 Library.BookCard 顶部右上角徽章风格。
                                //   未读不画徽章（§36 设计）——避免目录里多为未读文件时一片红色噪音；
                                //   阅读中/已读完才高亮。
                                //   ★ §37(17) 子项 grid（Reader.kt ItemCard）反过来全画——满屏 grid
                                //   用户是"全局视野"决策点，未读也要能看出来（用户原话）；目录是"少而精"场景，
                                //   保留 §36 不画未读语义，二者并存是 1.003 turn 6 稳定态设计。
                                if (status != TocItemStatus.UNREAD) {
                                    Surface(
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
                            Icon(
                                if (item.dir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = if (item.dir) "文件夹" else "文件",
                                tint = if (item.dir) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(item.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Text("无目录（单本漫画）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    }
}
