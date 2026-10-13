package com.mangareader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.graphics.Bitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import android.graphics.drawable.Drawable
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage

import com.hippo.ehviewer.coil.coverThumb
import com.hippo.ehviewer.library.naturalCompare
import com.mangareader.App
import com.mangareader.Scanner
import com.mangareader.data.Book
import com.mangareader.data.Bookshelf
import com.mangareader.data.ReadingProgress
import com.mangareader.data.SearchHistory
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

import kotlinx.coroutines.yield
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

enum class SortMode(val label: String) { LAST("最近阅读"), NAME("名称"), DATE("添加时间"), PROGRESS("阅读进度") }
enum class FilterMode(val label: String) { ALL("全部"), READING("阅读中"), UNREAD("未读"), FINISHED("已读完"), FAV("收藏") }
enum class LayoutMode(val cols: Int, val label: String) { GRID2(2, "2列"), GRID3(3, "3列"), GRID4(4, "4列"), GRID5(5, "5列"), LIST(1, "列表") }

sealed interface ShelfItem {
    data class Single(val book: Book) : ShelfItem
    data class Series(
        val serverId: String,
        val seriesId: String,
        val title: String,
        val coverPath: String?,
        val volumeCount: Int,
        val books: List<Book>,
    ) : ShelfItem
}

/**
 * ★ 首页搜索下探子文件夹（2026-10-11 用户需求）的命中项：
 *   书名 LIKE 之外，在目录书的子文件夹/已访问子路径里按名称匹配。
 *   jumpPath 供 Reader(bookId, jumpPath) 直达命中的那一层；relPath 是书名下的相对路径展示。
 */
private data class DeepSearchHit(
    val bookId: Long,
    val bookTitle: String,
    val jumpPath: String,
    val relPath: String,
    val isDir: Boolean,
)

private fun buildChildPath(path: String, item: ArchiveEngine.Item): String? {
    if (path.startsWith("z:")) {
        val inner = path.removePrefix("z:")
        val sepIdx = inner.indexOf("//")
        if (sepIdx < 0) return null
        val archivePath = inner.substring(0, sepIdx)
        val currentSubPath = inner.substring(sepIdx + 2)
        val base = if (currentSubPath.isEmpty()) "" else if (currentSubPath.endsWith("/")) currentSubPath else "$currentSubPath/"
        return "z:$archivePath//${base}${item.name}/"
    }
    return "d:${item.auth}|${item.tree}|${item.doc}"
}

private fun buildLeafPath(item: ArchiveEngine.Item): String {
    return "a:${item.auth}|${item.tree}|${item.doc}|${item.name.substringAfterLast(".").lowercase()}"
}

/**
 * 搜索下探·即时层配套：从进度路径里剥出书根之下的相对段。
 * d: 书根="d:auth|tree|doc" ↔ 进度叶="a:auth|tree|doc|ext"（比对 SAF docId 前缀）；
 * z: 书根="z:archive//sub/" ↔ 进度="z:archive//sub/…"（同档案才剥前缀）；
 * smb:/webdav: 直接前缀剥离。无法确认属于该书 → null（宁缺勿滥，防跨书误挂）。
 */
private fun deepProgressRel(bookPath: String, path: String): String? {
    return runCatching {
        when {
            bookPath.startsWith("d:") && (path.startsWith("a:") || path.startsWith("d:")) -> {
                val bDoc = bookPath.removePrefix("d:").split('|').getOrNull(2) ?: return null
                val pDoc = path.removePrefix(if (path.startsWith("a:") ) "a:" else "d:").split('|').getOrNull(2) ?: return null
                val rel = pDoc.removePrefix(if (bDoc.endsWith("/")) bDoc else "$bDoc/")
                rel.trimStart('/').ifEmpty { null }
            }
            path.startsWith(bookPath) -> path.removePrefix(bookPath).trimStart('/').ifEmpty { null }
            bookPath.startsWith("z:") && path.startsWith("z:") -> {
                val bInner = bookPath.removePrefix("z:")
                val pInner = path.removePrefix("z:")
                if (bInner.substringBefore("//") != pInner.substringBefore("//")) return null
                val rel = pInner.substringAfter("//").removePrefix(bInner.substringAfter("//"))
                rel.trimStart('/').ifEmpty { null }
            }
            else -> null
        }
    }.getOrNull()
}

private fun convertRemoteEntriesLib(
    entries: List<com.hippo.ehviewer.library.BrowseEntryRemote>,
    parentPath: String,
    auth: String,
): List<ArchiveEngine.Item>? {
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

private fun komgaServerIdFromPath(filePath: String): String? {
    if (!filePath.startsWith("komga:")) return null
    val parts = filePath.split(":")
    return if (parts.size >= 3) parts[1] else null
}

private fun aggregateKomgaSeries(books: List<Book>): List<ShelfItem> {
    val seriesBuckets = LinkedHashMap<Pair<String, String>, MutableList<Book>>()
    val singles = mutableListOf<ShelfItem>()
    for (b in books) {
        val sid = b.seriesId
        val serverId = if (sid != null) komgaServerIdFromPath(b.filePath) else null
        if (sid != null && serverId != null) {
            seriesBuckets.getOrPut(serverId to sid) { mutableListOf() }.add(b)
        } else if (sid != null && sid.startsWith("local:")) {
            // 本地 ComicInfo 系列（AutoTagger 写入），伪 serverId="local" 与卷列表页路由对齐
            seriesBuckets.getOrPut("local" to sid) { mutableListOf() }.add(b)
        } else {
            singles.add(ShelfItem.Single(b))
        }
    }
    val result = mutableListOf<ShelfItem>()
    result.addAll(singles)
    for ((key, volumes) in seriesBuckets) {
        if (volumes.size >= 2) {
            val (serverId, seriesId) = key
            val seriesName = volumes.firstOrNull { !it.seriesName.isNullOrEmpty() }?.seriesName
                ?: volumes.first().displayTitle
            // 系列卡封面：子卷 displayCoverPath（含自定义封面）优先
            val coverPath = volumes.firstOrNull { !it.displayCoverPath.isNullOrEmpty() }?.displayCoverPath
            result.add(ShelfItem.Series(serverId, seriesId, seriesName, coverPath, volumes.size, volumes))
        } else {
            volumes.forEach { result.add(ShelfItem.Single(it)) }
        }
    }
    return result
}

private fun sortShelfItems(items: List<ShelfItem>, sort: SortMode): List<ShelfItem> {
    return when (sort) {
        SortMode.LAST -> items.sortedByDescending { item ->
            when (item) {
                is ShelfItem.Single -> item.book.lastReadAt ?: 0L
                is ShelfItem.Series -> item.books.maxOfOrNull { it.lastReadAt ?: 0L } ?: 0L
            }
        }
        SortMode.DATE -> items.sortedByDescending { item ->
            when (item) {
                is ShelfItem.Single -> item.book.addedAt
                is ShelfItem.Series -> item.books.minOfOrNull { it.addedAt } ?: 0L
            }
        }
        SortMode.NAME -> items.sortedWith(Comparator { a, b ->
            val an = when (a) { is ShelfItem.Single -> a.book.displayTitle; is ShelfItem.Series -> a.title }
            val bn = when (b) { is ShelfItem.Single -> b.book.displayTitle; is ShelfItem.Series -> b.title }
            naturalCompare(an, bn)
        })
        SortMode.PROGRESS -> items.sortedByDescending { item ->
            when (item) {
                is ShelfItem.Single -> if (item.book.totalPages > 0) item.book.readPages.toFloat() / item.book.totalPages else 0f
                is ShelfItem.Series -> item.books.maxOfOrNull { if (it.totalPages > 0) it.readPages.toFloat() / it.totalPages else 0f } ?: 0f
            }
        }
    }
}

/** 搜索下探命中行：书名 › 书内相对路径，点击带 jumpPath 直达该层 */
@Composable
private fun DeepHitRow(hit: DeepSearchHit, onTap: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (hit.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(hit.bookTitle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                hit.relPath, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Library(openBook: (Long, String?) -> Unit, toggleTheme: () -> Unit, onSettings: () -> Unit = {}, onOpenHistory: () -> Unit = {}, onOpenKomgaSeries: (String, String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val dao = app.db.mangaDao()
    val scope = rememberCoroutineScope()
    val scanner = remember { Scanner(ctx, dao) }
    val engine = remember { ArchiveEngine(ctx) }

    // ★ ⑨ 修复辅助：500ms 节流，源头阻断 FAB 双击与 grid tween 双触发（launchSingleTop 已在 MainActivity 加双保险）
    //   节流时间戳必须放 remember —— 放 LaunchedEffect 内每次 recompose 会被重置。
    var lastOpenAt by remember { mutableLongStateOf(0L) }
    var showDonation by remember { mutableStateOf(false) }
    fun openBookThrottled(id: Long, jump: String?) {
        val now = System.currentTimeMillis()
        if (now - lastOpenAt < 500) return
        lastOpenAt = now
        openBook(id, jump)
    }


    suspend fun listRemoteFolderChildren(path: String): List<ArchiveEngine.Item>? {
        return withContext(Dispatchers.IO) {
            try {
                if (path.startsWith("smb:")) {
                    val ref = com.mangareader.smbbridge.SmbPaths.parseBookPath(path) ?: return@withContext null
                    val source = com.hippo.ehviewer.smb.SmbRepository.load(ref.serverId) ?: return@withContext null
                    val password = com.hippo.ehviewer.smb.SmbPasswordStore.get(ref.serverId)
                    val remoteDir = if (source.share.isBlank()) ref.fullRel else ref.rel
                    val entries = com.hippo.ehviewer.smb.SmbGateway.listDirectory(source, password, remoteDir)
                    convertRemoteEntriesLib(entries, path, "smb")
                } else if (path.startsWith("webdav:")) {
                    val ref = com.mangareader.webdavbridge.WebDavPaths.parseBookPath(path) ?: return@withContext null
                    val source = com.hippo.ehviewer.webdav.WebDavRepository.load(ref.serverId) ?: return@withContext null
                    val password = com.hippo.ehviewer.webdav.WebDavPasswordStore.get(ref.serverId)
                    val entries = com.hippo.ehviewer.webdav.WebDavGateway.listDirectory(source, password, ref.remotePath)
                    convertRemoteEntriesLib(entries, path, "webdav")
                } else null
            } catch (_: Exception) { null }
        }
    }

    suspend fun randomDescend(path: String, depth: Int): String? {
        if (depth > 10) return null
        val children = try {
            if (path.startsWith("smb:") || path.startsWith("webdav:")) {
                listRemoteFolderChildren(path) ?: emptyList()
            } else {
                engine.children(path)
            }
        } catch (_: Exception) { emptyList() }
        if (children.isEmpty()) return if (path.startsWith("z:")) path else null
        val item = children.random()
        return if (item.dir) {
            val childPath = if (path.startsWith("smb:") || path.startsWith("webdav:")) item.doc else buildChildPath(path, item)
            if (childPath == null) null else randomDescend(childPath, depth + 1)
        } else {
            if (path.startsWith("z:")) path
            else if (path.startsWith("smb:") || path.startsWith("webdav:")) item.doc
            else buildLeafPath(item)
        }
    }

    suspend fun enumerateLeaves(path: String, bookId: Long, depth: Int): List<Pair<Long, String>> {
        if (depth > 10) return emptyList()
        val children = try { engine.children(path) } catch (_: Exception) { emptyList() }
        val result = mutableListOf<Pair<Long, String>>()
        for (item in children) {
            if (item.dir) {
                val childPath = buildChildPath(path, item) ?: continue
                result.addAll(enumerateLeaves(childPath, bookId, depth + 1))
                yield()
            } else {
                val leaf = if (path.startsWith("z:")) path else buildLeafPath(item)
                result.add(Pair(bookId, leaf))
            }
        }
        return result
    }

    /**
     * 搜索下探·下探层（2026-10-11）：对一本目录书逐层走子文件夹按名称匹配。
     * 只走 d: SAF 树与 smb:/webdav: 远程目录书（engine.children 对远程禁用，走网关列目录）；
     * z: 归档书不现场开包（零开包原则），由进度即时层兜底。
     * 封顶：深度 ≤6、每书 ≤200 目录、命中 ≤30、全局 deadline 由调用方传入——
     * SAF/远程列目录慢，改词即整任务取消，deadline 兜底防拖死。
     */
    suspend fun deepWalkBook(
        book: Book,
        query: String,
        deadlineMs: Long,
        onHits: (List<DeepSearchHit>) -> Unit,
    ) {
        val root = book.filePath
        val remote = root.startsWith("smb:") || root.startsWith("webdav:")
        if (!remote && !root.startsWith("d:")) return
        data class Node(val path: String, val rel: String, val depth: Int)
        val queue = ArrayDeque<Node>()
        queue.add(Node(root, "", 0))
        var visited = 0
        val hits = ArrayList<DeepSearchHit>()
        while (queue.isNotEmpty() && visited < 200 && hits.size < 30 &&
            System.currentTimeMillis() < deadlineMs && coroutineContext.isActive
        ) {
            val node = queue.removeFirst()
            visited++
            val children: List<ArchiveEngine.Item> = try {
                if (remote) listRemoteFolderChildren(node.path) ?: emptyList()
                else withContext(Dispatchers.IO) { engine.children(node.path) }
            } catch (_: Exception) { emptyList() }
            for (item in children) {
                val childRel = if (node.rel.isEmpty()) item.name else "${node.rel}/${item.name}"
                if (item.name.contains(query, ignoreCase = true)) {
                    val jump = when {
                        remote -> item.doc
                        item.dir -> buildChildPath(node.path, item) ?: continue
                        else -> buildLeafPath(item)
                    }
                    hits.add(DeepSearchHit(book.id, book.displayTitle, jump, childRel, item.dir))
                }
                if (item.dir && node.depth < 5) {
                    val childPath = if (remote) item.doc else buildChildPath(node.path, item) ?: continue
                    queue.add(Node(childPath, childRel, node.depth + 1))
                }
            }
        }
        if (hits.isNotEmpty()) onHits(hits)
    }

    fun makeOpenedKey(bookId: Long, leafPath: String?): String {
        return if (leafPath.isNullOrEmpty()) bookId.toString() else "$bookId:$leafPath"
    }


    // ★ v1.11 排序记忆修复：sort/sortAsc 原为纯内存 remember（Library.kt:88-89），选完不落盘，
    //   进书返回（NavHost dispose→重组）或冷启动都会重置回 LAST。改为 SharedPreferences 持久化，
    //   与本项目 read_mode_$bookId / library prefs 同一套风格；valueOf 包 try-catch 防枚举变更崩溃。
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }

    var books by remember { mutableStateOf<List<Book>?>(null) }
    var randomLoading by remember { mutableStateOf(false) }
    var randomLeafPool by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    val randomOpenedSet = remember { mutableSetOf<String>() }
    var shelfItems by remember { mutableStateOf<List<ShelfItem>?>(null) }
    var bookshelves by remember { mutableStateOf(listOf<Bookshelf>()) }
    var sort by remember {
        mutableStateOf(
            try { SortMode.valueOf(prefs.getString("library_sort", SortMode.NAME.name)!!) } catch (_: Exception) { SortMode.NAME }
        )
    }
    var sortAsc by remember { mutableStateOf(prefs.getBoolean("library_sort_asc", false)) }
    var filter by remember {
        mutableStateOf(try { FilterMode.valueOf(prefs.getString("library_filter", FilterMode.ALL.name)!!) } catch (_: Exception) { FilterMode.ALL })
    }

    fun onRandomOpen() {
        val list = books
        if (list.isNullOrEmpty()) return
        val pool = randomLeafPool
        if (pool != null && pool.isNotEmpty()) {
            val available = pool.filter { makeOpenedKey(it.first, it.second) !in randomOpenedSet }
            val candidates = if (available.isNotEmpty()) available else { randomOpenedSet.clear(); pool }
            val (bookId, leafPath) = candidates.random()
            randomOpenedSet.add(makeOpenedKey(bookId, leafPath))
            if (leafPath.isEmpty()) openBookThrottled(bookId, null)
            else openBookThrottled(bookId, leafPath)
            return
        }
        val unread = list.filter { it.readPages == 0 }
        val reading = list.filter { it.readPages > 0 && (it.totalPages <= 0 || it.readPages < it.totalPages) }
        val finished = list.filter { it.totalPages > 0 && it.readPages >= it.totalPages }
        val priorityPool = when {
            unread.isNotEmpty() -> unread
            reading.isNotEmpty() -> reading
            else -> finished.ifEmpty { list }
        }
        val folders = priorityPool.filter { it.filePath.startsWith("d:") || it.filePath.startsWith("z:") || it.filePath.startsWith("smb:") || it.filePath.startsWith("webdav:") }
        val directFiles = priorityPool.filter { !folders.contains(it) }
        val pickFolder = folders.isNotEmpty() && (directFiles.isEmpty() || kotlin.random.Random.nextBoolean())
        val b = if (pickFolder) folders.random() else directFiles.randomOrNull() ?: folders.random()
        val fp = b.filePath
        if (fp.startsWith("d:") || fp.startsWith("z:")) {
            randomLoading = true
            scope.launch {
                val leaf = withContext(Dispatchers.IO) { randomDescend(fp, 0) }
                randomLoading = false
                if (leaf != null) {
                    randomOpenedSet.add(makeOpenedKey(b.id, leaf))
                    openBookThrottled(b.id, leaf)
                }
                else Toast.makeText(ctx, "随机失败，请重试", Toast.LENGTH_SHORT).show()
            }
        } else if (fp.startsWith("smb:") || fp.startsWith("webdav:")) {
            randomLoading = true
            scope.launch {
                val hasLocalFallback = directFiles.isNotEmpty()
                val leaf = if (hasLocalFallback) {
                    withTimeoutOrNull(1000L) { withContext(Dispatchers.IO) { randomDescend(fp, 0) } }
                } else {
                    withContext(Dispatchers.IO) { randomDescend(fp, 0) }
                }
                randomLoading = false
                if (leaf != null) {
                    randomOpenedSet.add(makeOpenedKey(b.id, leaf))
                    openBookThrottled(b.id, leaf)
                } else {
                    val localBook = directFiles.randomOrNull()
                    if (localBook != null) {
                        randomOpenedSet.add(makeOpenedKey(localBook.id, null))
                        openBookThrottled(localBook.id, null)
                    }
                    else {
                        randomOpenedSet.add(makeOpenedKey(b.id, null))
                        openBookThrottled(b.id, null)
                    }
                }
            }
        } else {
            randomOpenedSet.add(makeOpenedKey(b.id, null))
            openBookThrottled(b.id, null)
        }
    }


    // 标签筛选状态（design.md §三）：已选集合 + AND/OR + 最近5个，均持久化
    var selectedTagIds by remember { mutableStateOf(prefs.getString("library_tag_ids", "")?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()) }
    var tagLogicAnd by remember { mutableStateOf(prefs.getBoolean("tag_filter_and", true)) }
    var recentTagIds by remember { mutableStateOf(prefs.getString("library_recent_tag_ids", "")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showShelfDrawer by remember { mutableStateOf(false) }
    var showBatchTagPicker by remember { mutableStateOf(false) }
    val tagsWithCounts by dao.getTagsWithCounts().collectAsState(initial = emptyList())

    fun toggleLibraryTag(id: Long) {
        val next = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id
        selectedTagIds = next
        prefs.edit().putString("library_tag_ids", next.joinToString(",")).apply()
        if (id in next) {
            val r = (listOf(id) + (recentTagIds - id)).take(5)
            recentTagIds = r
            prefs.edit().putString("library_recent_tag_ids", r.joinToString(",")).apply()
        }
    }
    fun removeLibraryTag(id: Long) {
        selectedTagIds = selectedTagIds - id
        prefs.edit().putString("library_tag_ids", selectedTagIds.joinToString(",")).apply()
    }
    fun clearLibraryTags() {
        selectedTagIds = emptySet()
        prefs.edit().putString("library_tag_ids", "").apply()
    }

    // 已删除/已隐藏的标签自动从选中集剔除，避免"chip 消失了但筛选还在暗中生效"（审查问题2）
    var tagListLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(tagsWithCounts) {
        if (tagsWithCounts.isNotEmpty()) {
            tagListLoaded = true
            val valid = tagsWithCounts.map { it.tag.id }.toSet()
            val pruned = selectedTagIds.filter { it in valid }
            if (pruned.size != selectedTagIds.size) {
                selectedTagIds = pruned.toSet()
                prefs.edit().putString("library_tag_ids", selectedTagIds.joinToString(",")).apply()
            }
        }
    }

    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val autoCols = if (isTablet) {
        when {
            configuration.screenWidthDp > 1100 -> 6
            configuration.screenWidthDp > 800 -> 4
            else -> 3
        }
    } else {
        when {
            configuration.screenWidthDp > 900 -> 5
            configuration.screenWidthDp > 600 -> 4
            else -> 3
        }
    }
    val portraitCols by com.hippo.ehviewer.Settings.thumbColumnsPortrait.valueFlow().collectAsState(initial = com.hippo.ehviewer.Settings.thumbColumnsPortrait.value)
    val landscapeCols by com.hippo.ehviewer.Settings.thumbColumnsLandscape.valueFlow().collectAsState(initial = com.hippo.ehviewer.Settings.thumbColumnsLandscape.value)
    val resolvedCols = LayoutColumnsResolver.currentValue(configuration.orientation, portraitCols, landscapeCols)
    val gridCols = LayoutColumnsResolver.toGridCols(resolvedCols)
    val effectiveCols = if (resolvedCols in 1..8) resolvedCols else autoCols
    var layoutMode by remember { mutableStateOf(prefs.getInt("layout_mode", 0)) }
    var showLayoutMenu by remember { mutableStateOf(false) }
    var hideAllBadges by remember { mutableStateOf(prefs.getBoolean("hide_all_badges", false)) }
    var titleMarquee by remember { mutableStateOf(prefs.getBoolean("shelf_title_marquee", false)) }
    var currentOrientation by remember { mutableStateOf(prefs.getInt("app_orientation", 0)) }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    // 搜索防抖 300ms
    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty()) {
            debouncedQuery = ""
        } else {
            delay(300)
            debouncedQuery = searchQuery
        }
    }
    var showSearch by remember { mutableStateOf(false) }
    var isDark by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Book?>(null) }
    var showBookInfo by remember { mutableStateOf<Book?>(null) }

    var showBookSettings by remember { mutableStateOf<Book?>(null) }
    var showComicInfo by remember { mutableStateOf<Book?>(null) }
    var comicInfoData by remember { mutableStateOf<com.mangareader.metadata.ComicInfo?>(null) }
    var comicInfoLoading by remember { mutableStateOf(false) }
    var coverEditBook by remember { mutableStateOf<Book?>(null) }

    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedBooks by remember { mutableStateOf(setOf<Long>()) }
    BackHandler(isMultiSelect) { isMultiSelect = false; selectedBooks = emptySet() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShelfDialog by remember { mutableStateOf(false) }
    var showRenameBatch by remember { mutableStateOf(false) }
    var showRenameSingle by remember { mutableStateOf(false) }
    var showCreateShelf by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var mergedBookView by remember { mutableStateOf<Book?>(null) }
    var mergeMembers by remember { mutableStateOf<List<Book>>(emptyList()) }

    var newShelfName by remember { mutableStateOf("") }

    var selectedShelfId by remember { mutableStateOf<Long?>(prefs.getLong("selected_shelf_id", -1L).takeIf { it >= 0 }) }
    var selectedShelfName by remember { mutableStateOf("") }
    LaunchedEffect(selectedShelfId) {
        prefs.edit().putLong("selected_shelf_id", selectedShelfId ?: -1L).apply()
    }
    LaunchedEffect(bookshelves, selectedShelfId) {
        selectedShelfName = bookshelves.find { it.id == selectedShelfId }?.name ?: ""
    }

    var lastReading by remember { mutableStateOf<ReadingProgress?>(null) }

    LaunchedEffect(Unit) {
        dao.getLastReading().collect { lastReading = it }
    }

    LaunchedEffect(Unit) {
        val autoRefresh = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE).getBoolean("auto_refresh", false)
        if (autoRefresh) {
            scope.launch {
                val foldersList = withContext(Dispatchers.IO) { dao.getAllFoldersList() }
                var totalNew = 0
                foldersList.forEach { f -> totalNew += scanner.rescan(f) }
                if (totalNew > 0) Toast.makeText(ctx, "发现 $totalNew 本新漫画", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ★ 远程书架启动自动刷新（2026-10-07 方案 A）：对齐本地 auto_refresh——设置开关打开时
    //   进书架自动扫一次 SMB/WebDAV（每进程每源一次，扫描器内部守卫）；无网静默跳过；
    //   只在有新书时 toast（对齐本地语义），失败完全静默
    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val wantDav = prefs.getBoolean("auto_refresh_webdav", false)
        val wantSmb = prefs.getBoolean("auto_refresh_smb", false)
        if (!wantDav && !wantSmb) return@LaunchedEffect
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (cm?.activeNetwork == null) return@LaunchedEffect
        // ★ P1：点火即走（扫描器自有 scope），书架组合销毁不影响扫描；toast 汇总经回调
        var pending = if (wantDav) 1 else 0
        pending += if (wantSmb) 1 else 0
        var totalNew = 0
        val onNew: (Int) -> Unit = { n ->
            totalNew += n
            pending -= 1
            if (pending <= 0 && totalNew > 0) {
                Toast.makeText(ctx, "自动刷新：新增 $totalNew 本", Toast.LENGTH_SHORT).show()
            }
        }
        runCatching {
            if (wantDav) com.mangareader.webdavbridge.WebDavScanner.onAutoScanNew = onNew
            if (wantSmb) com.mangareader.smbbridge.SmbScanner.onAutoScanNew = onNew
        }
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                if (wantDav) com.hippo.ehviewer.webdav.WebDavRepository.sourcesFlow().first().forEach { src ->
                    com.mangareader.webdavbridge.WebDavScanner.autoScanAsync(src.id)
                }
                if (wantSmb) com.hippo.ehviewer.smb.SmbRepository.sourcesFlow().first().forEach { src ->
                    com.mangareader.smbbridge.SmbScanner.autoScanAsync(src.id)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        dao.getVisibleBookshelves().collect { bookshelves = it }
    }

    // Preload covers for books that don't have disk-cached covers yet
    val coverScope = rememberCoroutineScope()
    val md5 = remember { java.security.MessageDigest.getInstance("MD5") }
    LaunchedEffect(books?.isNotEmpty()) {
        if (!books.isNullOrEmpty()) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try { com.hippo.ehviewer.webdav.WebDavClient.warmUp() } catch (_: Exception) {}
            }
        }
    }
    LaunchedEffect(books?.map { it.id } ?: emptyList<Long>()) {
        if (!books.isNullOrEmpty()) {
            val limitedDispatcher = Dispatchers.IO.limitedParallelism(3)
            val jobs = books!!.map { book ->
                coverScope.launch(limitedDispatcher) {
                    val coverPath = book.coverPath ?: return@launch
                    if (coverPath.startsWith("cv:smb:") || coverPath.startsWith("cv:webdav:") || coverPath.startsWith("cv:komga:") || coverPath.startsWith("cv:opds:")) return@launch
                    // custom: 私有文件即取即显，无需预热
                    if (coverPath.startsWith("custom:")) return@launch
                    val key = synchronized(md5) { md5.reset(); md5.digest(coverPath.toByteArray()) }.joinToString("") { "%02x".format(it) }
                    val file = java.io.File(ctx.filesDir, "covers/$key.thumb")
                    if (file.exists()) return@launch
                    try {
                        engine.loadCover(coverPath)
                    } catch (_: Exception) {}
                }
            }
            jobs.forEach { it.join() }
        }
    }

    LaunchedEffect(books?.map { it.id } ?: emptyList<Long>()) {
        if (books.isNullOrEmpty()) { randomLeafPool = null; return@LaunchedEffect }
        val leafPool = withContext(Dispatchers.IO) {
            val list = books!!
            val unread = list.filter { it.readPages == 0 }
            val reading = list.filter { it.readPages > 0 && (it.totalPages <= 0 || it.readPages < it.totalPages) }
            val finished = list.filter { it.totalPages > 0 && it.readPages >= it.totalPages }
            val pool = when {
                unread.isNotEmpty() -> unread
                reading.isNotEmpty() -> reading
                else -> finished.ifEmpty { list }
            }
            val result = mutableListOf<Pair<Long, String>>()
            for (b in pool) {
                val fp = b.filePath
                if (fp.startsWith("d:") || fp.startsWith("z:")) {
                    result.addAll(enumerateLeaves(fp, b.id, 0))
                } else if (!fp.startsWith("smb:") && !fp.startsWith("webdav:")) {
                    result.add(Pair(b.id, ""))
                }
                yield()
            }
            result
        }
        randomLeafPool = leafPool
    }

    // 自定义封面孤儿自愈：covers 被清（签名降级/清数据）→ 置回默认封面，不留死指针
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                dao.booksWithCustomCover().forEach { b ->
                    val name = b.customCoverPath?.removePrefix("custom:") ?: return@forEach
                    if (!java.io.File(ctx.filesDir, "covers/$name").exists()) {
                        dao.updateBookCustomCoverPath(b.id, null)
                    }
                }
            }
        }
    }

    LaunchedEffect(sort, sortAsc, debouncedQuery, filter, selectedShelfId, selectedTagIds, tagLogicAnd) {
        val globalHide = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE).getBoolean("global_hide", false)
        if (globalHide) {
            books = emptyList()
            return@LaunchedEffect
        }
        val flow = when {
            selectedShelfId != null -> dao.getBooksByShelf(selectedShelfId!!)
            debouncedQuery.isNotEmpty() -> dao.searchVisibleBooks(debouncedQuery)
            filter == FilterMode.FAV -> dao.getVisibleFavoriteBooks()
            filter == FilterMode.READING -> dao.getVisibleReadingBooks()
            filter == FilterMode.UNREAD -> dao.getVisibleUnreadBooks()
            filter == FilterMode.FINISHED -> dao.getVisibleFinishedBooks()
            else -> when (sort) {
                SortMode.LAST -> dao.getVisibleBooks()
                SortMode.NAME -> dao.getVisibleBooksByName()
                SortMode.DATE -> dao.getVisibleBooksByDateAdded()
                SortMode.PROGRESS -> dao.getVisibleBooksByProgress()
            }
        }
        // 标签筛选（AND=HAVING COUNT 索引查询 / OR=IN），先取允许集再内存交集（spec.md §八）
        val allowedTagBookIds: Set<Long>? = if (selectedTagIds.isEmpty()) null else withContext(Dispatchers.IO) {
            val tagBooks = if (tagLogicAnd) dao.getBooksByTagsAnd(selectedTagIds.toList(), selectedTagIds.size)
                else dao.getBooksByTagsOr(selectedTagIds.toList())
            tagBooks.map { it.id }.toHashSet()
        }
        flow.distinctUntilChanged().collect { loaded ->
            // ★ 取证：书架数据加载完成（死亡若发生在 books 加载前后可由 stages 定位）
            com.mangareader.DiagTracer.mark(ctx, "library: books loaded n=" + loaded.size)
            val list = if (allowedTagBookIds != null) loaded.filter { it.id in allowedTagBookIds } else loaded
            val canAggregate = debouncedQuery.isEmpty() && selectedShelfId == null && filter == FilterMode.ALL && selectedTagIds.isEmpty()
            if (canAggregate) {
                val aggregated = withContext(Dispatchers.IO) { sortShelfItems(aggregateKomgaSeries(list), sort) }
                books = list
                shelfItems = if (sortAsc) aggregated.reversed() else aggregated
            } else {
                // ★ 排序修复：书架/筛选/搜索路径此前只认「名称」排序，最近阅读/添加时间/进度
                //   全被无视（DAO 查询写死 ORDER BY：书架=title ASC，筛选/搜索=lastReadAt DESC），
                //   用户选了时间排序进书架看到的是书名序=「新旧掺一起」。四键与 sortShelfItems 对齐。
                val ordered = withContext(Dispatchers.IO) {
                    when (sort) {
                        SortMode.NAME -> list.sortedWith(Comparator { a, b -> naturalCompare(a.displayTitle, b.displayTitle) })
                        SortMode.LAST -> list.sortedByDescending { it.lastReadAt ?: 0L }
                        SortMode.DATE -> list.sortedByDescending { it.addedAt }
                        SortMode.PROGRESS -> list.sortedByDescending { if (it.totalPages > 0) it.readPages.toFloat() / it.totalPages else 0f }
                    }
                }
                books = if (sortAsc) ordered.reversed() else ordered
                shelfItems = null
            }
        }
    }

    // ★ 首页搜索下探子文件夹（2026-10-11 用户需求）：书名 LIKE 之外的两层匹配——
    //   ①即时层：reading_progress 已访问子路径按名称匹配（进度表 LIKE 一次查询，z: 归档/
    //     远程书零开包也命中看过的卷）；②下探层：目录书（d:/smb:/webdav:）逐层列子文件夹
    //     匹配，封顶+改词取消+总 deadline，逐书流式上屏。z: 归档不现场开包（沿用审查方案
    //     「零开包」原则，未看过的卷搜不到，属已知边界）。
    var deepHits by remember { mutableStateOf<List<DeepSearchHit>>(emptyList()) }
    LaunchedEffect(debouncedQuery) {
        val q = debouncedQuery.trim()
        if (q.isEmpty()) { deepHits = emptyList(); return@LaunchedEffect }
        deepHits = emptyList()
        val seen = HashSet<String>()
        val collected = ArrayList<DeepSearchHit>()
        fun push(h: DeepSearchHit) {
            if (collected.size >= 60) return
            if (seen.add("${h.bookId}|${h.jumpPath}")) collected.add(h)
        }
        val all = withContext(Dispatchers.IO) {
            runCatching { dao.getVisibleBooks().first() }.getOrDefault(emptyList())
        }
        val byId = all.associateBy { it.id }
        val titleMatched = books.orEmpty().mapTo(HashSet()) { it.id }
        withContext(Dispatchers.IO) {
            runCatching {
                dao.searchVisibleProgressPaths(q).forEach { p ->
                    val b = byId[p.bookId] ?: return@forEach
                    val rel = deepProgressRel(b.filePath, p.path) ?: return@forEach
                    if (!rel.contains(q, ignoreCase = true)) return@forEach
                    push(DeepSearchHit(b.id, b.displayTitle, p.path, rel, false))
                }
            }
        }
        if (collected.isNotEmpty()) deepHits = collected.toList()
        if (collected.size < 60 && isActive) {
            val deadline = System.currentTimeMillis() + 6000
            for (b in all) {
                if (!isActive || collected.size >= 60 || System.currentTimeMillis() >= deadline) break
                if (b.id in titleMatched) continue
                val before = collected.size
                deepWalkBook(b, q, deadline) { walkHits -> walkHits.forEach { push(it) } }
                if (collected.size != before) deepHits = collected.toList()
            }
        }
    }

    deleteTarget?.let { b ->
        val customNote = if (b.customCoverPath != null) "已设置的自定义封面将一并删除。" else ""
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除") },
            text = { Text("删除「${b.displayTitle}」？不删原始文件。$customNote") },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        b.customCoverPath?.let { engine.deleteCustomCoverByPath(it) }
                        if (b.source == "merged") dao.dissolveMerge(b.id) else dao.deleteBook(b)
                    }
                    deleteTarget = null
                }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }

    if (showTagPicker) {
        TagPickerSheet(
            visible = true,
            allTags = tagsWithCounts,
            selectedIds = selectedTagIds,
            andLogic = tagLogicAnd,
            onToggle = { toggleLibraryTag(it) },
            onLogicChange = {
                tagLogicAnd = it
                prefs.edit().putBoolean("tag_filter_and", it).apply()
            },
            onClear = { clearLibraryTags() },
            onDismiss = { showTagPicker = false },
        )
    }

    if (showShelfDrawer) {
        val drawerState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showShelfDrawer = false }, sheetState = drawerState, sheetMaxWidth = 520.dp) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState())) {
                Text("全部书架", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                bookshelves.forEach { shelf ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selectedShelfId = shelf.id
                        selectedShelfName = shelf.name
                        filter = FilterMode.ALL
                        prefs.edit().putString("library_filter", FilterMode.ALL.name).apply()
                        showShelfDrawer = false
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Book, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(shelf.name, fontSize = 15.sp, color = if (selectedShelfId == shelf.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    if (showBatchTagPicker) {
        TagPickerSheet(
            visible = true,
            allTags = tagsWithCounts,
            selectedIds = emptySet(),
            andLogic = true,
            addMode = true,
            onToggle = {},
            onApply = { ids ->
                val names = ids.mapNotNull { id -> tagsWithCounts.find { it.tag.id == id }?.tag?.name }
                if (names.isNotEmpty()) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            selectedBooks.forEach { bid -> dao.setBookTags(bid, names, 1) }
                        }
                        isMultiSelect = false; selectedBooks = emptySet()
                    }
                }
                showBatchTagPicker = false
            },
            onLogicChange = {},
            onClear = {},
            onDismiss = { showBatchTagPicker = false },
        )
    }

    if (showDeleteConfirm) {
        val deleteMode = remember { mutableStateOf("shelf") }
        var countdown by remember { mutableIntStateOf(5) }
        LaunchedEffect(showDeleteConfirm) { countdown = 5; while (countdown > 0) { delay(1000); countdown-- } }
        AlertDialog(onDismissRequest = { showDeleteConfirm = false }, title = { Text("⚠️ 删除确认") },
            text = {
                Column {
                    if (deleteMode.value == "local") {
                        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                            Text("警告：选择「同时删文件」将永久删除源文件，此操作不可恢复！", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("确定删除选中的 ${selectedBooks.size} 本漫画？已设置的自定义封面将一并删除。")
                    Spacer(Modifier.height(16.dp))
                    Text("选择删除方式：", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { deleteMode.value = "shelf" },
                            shape = RoundedCornerShape(8.dp),
                            color = if (deleteMode.value == "shelf") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.BookmarkRemove, null, tint = if (deleteMode.value == "shelf") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(4.dp))
                                Text("仅移除记录", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (deleteMode.value == "shelf") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text("保留原始文件", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { deleteMode.value = "local" },
                            shape = RoundedCornerShape(8.dp),
                            color = if (deleteMode.value == "local") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(4.dp))
                                Text("同时删文件", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                                Text("不可恢复", fontSize = 11.sp, color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            dao.removeBooksFromAllShelves(selectedBooks.toList())
                            if (deleteMode.value == "local") {
                                selectedBooks.forEach { bookId ->
                                    val book = dao.getBookById(bookId)
                                    book?.let { b ->
                                        try {
                                            val rawPath = if (b.filePath.startsWith("d:")) b.filePath.removePrefix("d:")
                                            else if (b.filePath.startsWith("a:")) b.filePath.removePrefix("a:")
                                            else b.filePath
                                            val segments = rawPath.split("|", limit = 4)
                                            if (segments.size >= 3) {
                                                val auth = segments[0]
                                                val treeId = segments[1]
                                                val docId = segments[2]
                                                val treeUri = Uri.parse("content://$auth/tree/${Uri.encode(treeId)}")
                                                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                                try {
                                                    DocumentsContract.deleteDocument(ctx.contentResolver, docUri)
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(ctx, "无法删除「${b.displayTitle}」: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                engine.deleteCoverCache(b.coverPath ?: "")
                                                b.customCoverPath?.let { engine.deleteCustomCoverByPath(it) }
                                            }
                                        } catch (e: Exception) { Log.e("Library", "delete file error: ${e.message}") }
                                    }
                                }
                            }
                            // 自定义封面文件一并清理（仅移除记录模式也清，防孤儿）
                            selectedBooks.forEach { bid ->
                                dao.getBookById(bid)?.customCoverPath?.let { engine.deleteCustomCoverByPath(it) }
                            }
                            val virtualIds = selectedBooks.filter { id ->
                                dao.getBookById(id)?.source == "merged"
                            }
                            virtualIds.forEach { dao.dissolveMerge(it) }
                            dao.deleteBooksByIds(selectedBooks.filter { it !in virtualIds }.toList())
                        }
                        selectedBooks = emptySet()
                        isMultiSelect = false
                        showDeleteConfirm = false
                    }
                }, enabled = countdown <= 0) { Text(if (countdown > 0) "确认删除 ($countdown)" else "确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } })
    }

    if (showShelfDialog) {
        AlertDialog(onDismissRequest = { showShelfDialog = false }, title = { Text("添加到书架") },
            text = {
                Column(Modifier.heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { showCreateShelf = true; showShelfDialog = false }) {
                        Text("+ 新建书架", color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (bookshelves.isEmpty()) Text("还没有书架，点击上方创建")
                    else bookshelves.forEach { shelf ->
                        Row(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    selectedBooks.forEach { bookId ->
                                        val existing = dao.getShelvesForBook(bookId)
                                        if (existing.none { it.bookshelfId == shelf.id }) {
                                            dao.insertBookshelfItem(com.mangareader.data.BookshelfItem(bookshelfId = shelf.id, bookId = bookId))
                                        }
                                    }
                                }
                                Toast.makeText(ctx, "已添加到「${shelf.name}」", Toast.LENGTH_SHORT).show()
                                showShelfDialog = false
                            }
                        }.padding(vertical = 8.dp)) {
                            Text(shelf.name)
                        }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { showShelfDialog = false }) { Text("关闭") } })
    }

    if (showRenameBatch) {
        val selectedBookList = books.orEmpty().filter { it.id in selectedBooks }
        RenameBatchDialog(
            books = selectedBookList,
            onConfirm = { updates ->
                scope.launch {
                    withContext(Dispatchers.IO) { dao.updateBookTitlesBatch(updates) }
                    showRenameBatch = false
                    isMultiSelect = false
                    selectedBooks = emptySet()
                }
            },
            onDismiss = { showRenameBatch = false }
        )
    }

    if (showRenameSingle) {
        val selectedBook = books.orEmpty().firstOrNull { it.id in selectedBooks }
        selectedBook?.let { sb ->
            val initialName = sb.displayTitle
            var renameInput by remember(showRenameSingle, sb.id) { mutableStateOf(initialName) }
            AlertDialog(
                onDismissRequest = { showRenameSingle = false },
                title = { Text("重命名") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = renameInput,
                            onValueChange = { renameInput = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.clearCustomTitle(sb.id) }
                                showRenameSingle = false
                                isMultiSelect = false
                                selectedBooks = emptySet()
                            }
                        }) { Text("恢复文件名", color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val newTitle = renameInput.trim()
                        if (newTitle.isEmpty()) return@TextButton
                        scope.launch {
                            if (newTitle != initialName) {
                                withContext(Dispatchers.IO) { dao.updateBookTitle(sb.id, newTitle) }
                            }
                            showRenameSingle = false
                            isMultiSelect = false
                            selectedBooks = emptySet()
                        }
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { showRenameSingle = false }) { Text("取消") } }
            )
        }
    }

    if (showMergeDialog) {
        val selectedBookList = books.orEmpty().filter { it.id in selectedBooks }
        val defaultName = selectedBookList.firstOrNull()?.displayTitle ?: "合并合集"
        var mergeName by remember(showMergeDialog) { mutableStateOf(defaultName) }
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("合并为虚拟书") },
            text = {
                OutlinedTextField(
                    value = mergeName, onValueChange = { mergeName = it },
                    label = { Text("书名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedBookList.map { it.id }
                    scope.launch(Dispatchers.IO) {
                        val virtualId = dao.mergeBooks(ids, mergeName.trim())
                        withContext(Dispatchers.Main) {
                            if (virtualId > 0) {
                                Toast.makeText(ctx, "已合并", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "合并失败：需至少2本书", Toast.LENGTH_SHORT).show()
                            }
                            showMergeDialog = false
                            isMultiSelect = false; selectedBooks = emptySet()
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showMergeDialog = false }) { Text("取消") } },
        )
    }


    if (showCreateShelf) {
        AlertDialog(onDismissRequest = { showCreateShelf = false }, title = { Text("新建书架") },
            text = {
                OutlinedTextField(value = newShelfName, onValueChange = { newShelfName = it },
                    label = { Text("书架名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newShelfName.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val shelfId = dao.insertBookshelf(Bookshelf(name = newShelfName.trim()))
                                selectedBooks.forEach { bookId ->
                                    dao.insertBookshelfItem(com.mangareader.data.BookshelfItem(bookshelfId = shelfId, bookId = bookId))
                                }
                            }
                            Toast.makeText(ctx, "已创建书架「${newShelfName.trim()}」", Toast.LENGTH_SHORT).show()
                            newShelfName = ""
                            showCreateShelf = false
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateShelf = false; newShelfName = "" }) { Text("取消") } })
    }

    showBookInfo?.let { b ->
        val progress = if (b.totalPages > 0) b.readPages * 100 / b.totalPages else 0
        val f = b.totalPages > 0 && b.readPages >= b.totalPages
        val r = !f && (b.readPages > 0 || (b.lastReadAt != null && b.lastReadAt > 0))
        val statusText = when { f -> "已读完"; r -> "阅读中"; else -> "未读" }
        AlertDialog(onDismissRequest = { showBookInfo = null }, title = { Text(b.displayTitle) },
            text = {
                Column(Modifier.heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState())) {
                    // ★ 原文件名直接放长按弹窗顶部（智能解析改名后书名与原名差异大，用户要能当场对出来）；
                    //   取名走 fileNameOf——SAF 路径末段是 |ext 后缀，不能直接 substringAfterLast('|')
                    val rawName = remember(b.filePath) { com.mangareader.util.BookTitleParser.fileNameOf(b.filePath) }
                    if (rawName.isNotBlank() && rawName != b.displayTitle) {
                        Text("原文件名: $rawName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                    }
                    // 标签区（design.md §七）：元数据/我的两行轻分组 + 移除 + 添加（自动补全）+ 转手动
                    var bookTags by remember(b.id) { mutableStateOf(listOf<com.mangareader.data.TagWithSource>()) }
                    var tagInput by remember(b.id) { mutableStateOf("") }
                    suspend fun refreshBookTags() { withContext(Dispatchers.IO) { bookTags = dao.getTagsForBookWithSource(b.id) } }
                    LaunchedEffect(b.id) { refreshBookTags() }
                    Spacer(Modifier.height(10.dp))
                    Text("标签", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    val (xmlTagList, manualTagList) = bookTags.partition { it.linkSource == 0 }
                    if (bookTags.isEmpty()) Text("还没有标签（导入带 ComicInfo.xml 的书可自动获得，或在下方手动添加）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (xmlTagList.isNotEmpty()) {
                        Text("元数据", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            xmlTagList.forEach { tws ->
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TagDot(tws.tag.name, tws.tag.color, size = 8.dp)
                                        Spacer(Modifier.width(5.dp))
                                        Text(tws.tag.name, fontSize = 12.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text("×", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.clickable { scope.launch { withContext(Dispatchers.IO) { dao.deleteTagLinkByTagAndBook(tws.tag.id, b.id) }; refreshBookTags() } })
                                    }
                                }
                            }
                        }
                    }
                    if (manualTagList.isNotEmpty()) {
                        Text("我的", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            manualTagList.forEach { tws ->
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TagDot(tws.tag.name, tws.tag.color, size = 8.dp)
                                        Spacer(Modifier.width(5.dp))
                                        Text(tws.tag.name, fontSize = 12.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text("×", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.clickable { scope.launch { withContext(Dispatchers.IO) { dao.deleteTagLinkByTagAndBook(tws.tag.id, b.id) }; refreshBookTags() } })
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = tagInput, onValueChange = { tagInput = it }, placeholder = { Text("添加标签…", fontSize = 12.sp) },
                            singleLine = true, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
                        TextButton(onClick = {
                            val name = tagInput.trim()
                            if (name.isNotEmpty()) {
                                scope.launch { withContext(Dispatchers.IO) { dao.setBookTags(b.id, listOf(name), 1) }; refreshBookTags() }
                                tagInput = ""
                            }
                        }) { Text("添加") }
                    }
                    val tagSuggestions = tagsWithCounts.filter { it.tag.name.contains(tagInput.trim(), ignoreCase = true) && bookTags.none { b2 -> b2.tag.id == it.tag.id } }.take(6)
                    if (tagInput.isNotBlank() && tagSuggestions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            tagSuggestions.forEach { tws ->
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable {
                                    scope.launch { withContext(Dispatchers.IO) { dao.setBookTags(b.id, listOf(tws.tag.name), 1) }; refreshBookTags() }
                                    tagInput = ""
                                }) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TagDot(tws.tag.name, tws.tag.color, size = 8.dp)
                                        Spacer(Modifier.width(5.dp))
                                        Text(tws.tag.name, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // displayCoverPath 为空（书无任何封面来源）时给占位图，空串 CoverData 会让 Coil 找不到 Fetcher 显示空白
                        if (b.displayCoverPath != null) {
                            coil3.compose.AsyncImage(
                                model = coil3.request.ImageRequest.Builder(ctx)
                                    .data(com.mangareader.viewer.CoverData(b.displayCoverPath!!, dynOn = false))
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.size(width = 96.dp, height = 128.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                Modifier.size(width = 96.dp, height = 128.dp).clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            TextButton(onClick = { coverEditBook = b; showBookInfo = null }) { Text("更换封面") }
                            TextButton(
                                enabled = b.customCoverPath != null,
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            b.customCoverPath?.let { engine.deleteCustomCoverByPath(it) }
                                            dao.updateBookCustomCoverPath(b.id, null)
                                        }
                                        withContext(Dispatchers.Main) {
                                            coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
                                            Toast.makeText(ctx, "已恢复默认封面", Toast.LENGTH_SHORT).show()
                                            showBookInfo = null
                                        }
                                    }
                                },
                            ) { Text("恢复默认封面") }
                            TextButton(onClick = {
                                showComicInfo = b
                                comicInfoLoading = true
                                comicInfoData = null
                                scope.launch(Dispatchers.IO) {
                                    val info = com.mangareader.metadata.ComicInfoReader.read(ctx, b.filePath)
                                    comicInfoData = info
                                    comicInfoLoading = false
                                }
                            }) { Text("详情") }
                            TextButton(onClick = { showBookSettings = b; showBookInfo = null }) { Text("阅读设置") }
                            Text("设置应用后对该书所有子卷生效", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("状态: $statusText")
                    Spacer(Modifier.height(8.dp))
                    Text("进度: ${b.readPages}/${b.totalPages} 页 ($progress%)")
                    Spacer(Modifier.height(8.dp))
                    Text("文件类型: ${b.fileType.uppercase()}")
                    Spacer(Modifier.height(8.dp))
                    Text("来源: ${b.source}")
                }
            },
            confirmButton = { TextButton(onClick = { showBookInfo = null }) { Text("关闭") } },
            dismissButton = if (b.source == "smb" || b.source == "webdav") {
                {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            var freed = 0L
                            runCatching {
                                when (b.source) {
                                    "smb" -> {
                                        val ref = com.mangareader.smbbridge.SmbPaths.parseBookPath(b.filePath)
                                        if (ref != null) {
                                            val src = com.hippo.ehviewer.smb.SmbRepository.load(ref.serverId)
                                            val remoteRaw = if (src?.share?.isBlank() == false) ref.rel else ref.fullRel
                                            val zipSplit = com.hippo.ehviewer.library.ZipAsDirListing.splitZipBrowsePath(remoteRaw)
                                            val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
                                            val cacheKey = "smb:${ref.serverId}:$remote"
                                            freed += clearSmbPageCache(ref.serverId, remote)
                                            freed += deleteExtractDirs(cacheKey)
                                        }
                                    }
                                    "webdav" -> {
                                        val ref = com.mangareader.webdavbridge.WebDavPaths.parseBookPath(b.filePath)
                                        if (ref != null) {
                                            val remoteRaw = ref.remotePath
                                            val zipSplit = com.hippo.ehviewer.library.ZipAsDirListing.splitZipBrowsePath(remoteRaw)
                                            val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
                                            val cacheKey = "webdav:${ref.serverId}:$remote"
                                            freed += clearWebDavPageCache(ref.serverId, remote)
                                            freed += deleteExtractDirs(cacheKey)
                                        }
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "已清除缓存 ${freed / 1024 / 1024} MB", Toast.LENGTH_SHORT).show()
                                showBookInfo = null
                            }
                        }
                    }) { Text("清除缓存", color = MaterialTheme.colorScheme.error) }
                }
            } else null)
    }

    showComicInfo?.let { b ->
        AlertDialog(
            onDismissRequest = { showComicInfo = null; comicInfoData = null; comicInfoLoading = false },
            title = { Text("漫画详情") },
            text = {
                Column(Modifier.heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState())) {
                    // ★ 原文件名：智能解析后书名可能与原名差异大，这里提供回查入口。
                    //   取名走 fileNameOf——SAF 路径末段是 |ext 后缀，不能直接 substringAfterLast('|')
                    val rawName = remember(b.filePath) {
                        com.mangareader.util.BookTitleParser.fileNameOf(b.filePath)
                    }
                    if (rawName.isNotBlank()) {
                        Text("原文件名: $rawName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (comicInfoLoading) {
                        Text("正在读取 ComicInfo.xml…")
                    } else if (comicInfoData == null || comicInfoData!!.isEmpty()) {
                        Text("无元数据信息")
                    } else {
                        val info = comicInfoData!!
                        info.series?.let { Text("系列: $it"); Spacer(Modifier.height(6.dp)) }
                        info.number?.let { Text("卷号: $it"); Spacer(Modifier.height(6.dp)) }
                        info.volume?.let { Text("卷: $it"); Spacer(Modifier.height(6.dp)) }
                        info.title?.let { Text("标题: $it"); Spacer(Modifier.height(6.dp)) }
                        info.writer?.let { Text("作者: $it"); Spacer(Modifier.height(6.dp)) }
                        info.penciller?.let { Text("线稿: $it"); Spacer(Modifier.height(6.dp)) }
                        info.summary?.let {
                            Text("摘要: $it", maxLines = 8, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                        }
                        info.genre?.let { Text("类型: $it"); Spacer(Modifier.height(6.dp)) }
                        info.publisher?.let { Text("出版社: $it"); Spacer(Modifier.height(6.dp)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showComicInfo = null; comicInfoData = null; comicInfoLoading = false }) { Text("关闭") } },
        )
    }




    coverEditBook?.let { b ->
        CoverEditDialog(
            book = b, engine = engine, ctx = ctx,
            onResult = { newPath ->
                scope.launch(Dispatchers.IO) {
                    // 先写 DB 再删旧文件：孤儿自愈按"DB 路径+文件存在性"判定，反序会在
                    // "旧文件已删、DB 未更新"的窗口被自愈误清（新文件名≠旧名，晚删不影响显示）
                    dao.updateBookCustomCoverPath(b.id, newPath)
                    b.customCoverPath?.let { engine.deleteCustomCoverByPath(it) }
                    withContext(Dispatchers.Main) {
                        coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
                        Toast.makeText(ctx, if (newPath != null) "封面已更新" else "封面保存失败", Toast.LENGTH_SHORT).show()
                        coverEditBook = null
                    }
                }
            },
            onDismiss = { coverEditBook = null },
        )
    }

    val compactTopBar = isLandscapeOrientation() && !isTabletWide()

    Scaffold(
        topBar = {
            if (isMultiSelect) TopAppBar(
                title = { Text("已选 ${selectedBooks.size} 本") },
                navigationIcon = { IconButton(onClick = { isMultiSelect = false; selectedBooks = emptySet() }) { Icon(Icons.Default.Close, "取消选择") } },
                actions = {
                    // 批量离线超分：把选中的书全部加入后台超分队列（已读章节）
                    IconButton(onClick = {
                        val targets = books.orEmpty().filter { it.id in selectedBooks }
                        // 异步入队（大书 IO 不卡主线程），完成后主线程 Toast
                        com.mangareader.sr.SrBatchRunner.enqueueBooks(ctx, targets.map { it.id to it.displayTitle }) { r ->
                            (ctx as? android.app.Activity)?.runOnUiThread {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (r.chapters > 0) "已加入批量增强队列（${r.chapters} 章 ${r.pages} 页），通知栏可看进度\n（安卓13+ 需先允许通知权限）" else "没有找到可处理的页面——所选书籍可能无法离线枚举",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        isMultiSelect = false; selectedBooks = emptySet()
                    }) { Icon(Icons.Default.AutoFixHigh, "批量增强") }
                    var srMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { srMenu = true }) { Icon(Icons.Default.MoreVert, "增强") }
                        DropdownMenu(expanded = srMenu, onDismissRequest = { srMenu = false }) {
                            val srTargets = books.orEmpty().filter { it.id in selectedBooks }
                            DropdownMenuItem(
                                text = { Text("批量增强（已读章节）") },
                                onClick = {
                                    srMenu = false
                                    com.mangareader.sr.SrBatchRunner.enqueueBooks(ctx, srTargets.map { it.id to it.displayTitle }, false) { r ->
                                        (ctx as? android.app.Activity)?.runOnUiThread {
                                            Toast.makeText(ctx, if (r.chapters > 0) "已加入队列：${r.chapters} 章 ${r.pages} 页" else "没有找到可处理的页面——所选书可能无法离线枚举", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                    isMultiSelect = false; selectedBooks = emptySet()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("整本增强（含未读章，占用更多存储）") },
                                onClick = {
                                    srMenu = false
                                    com.mangareader.sr.SrBatchRunner.enqueueBooks(ctx, srTargets.map { it.id to it.displayTitle }, true) { r ->
                                        (ctx as? android.app.Activity)?.runOnUiThread {
                                            Toast.makeText(ctx, if (r.chapters > 0) "已加入队列：${r.chapters} 章 ${r.pages} 页，空闲时自动逐章处理" else "没有找到可处理的页面——所选书可能无法离线枚举", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                    isMultiSelect = false; selectedBooks = emptySet()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("清除增强产物（不可恢复）", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    srMenu = false
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        srTargets.forEach {
                                            com.mangareader.sr.SrCore.diskCache.removeBatchBook(it.id)
                                            com.mangareader.sr.SrCore.diskCache.removeArtifacts(it.id)
                                        }
                                    }
                                    Toast.makeText(ctx, "已清除所选书的增强产物", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                    IconButton(onClick = { selectedBooks = if (selectedBooks.isEmpty()) books.orEmpty().map { it.id }.toSet() else emptySet() }) {
                        Icon(Icons.Default.SelectAll, if (selectedBooks.isEmpty()) "全选" else "取消全选")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) else TopAppBar(
                title = {
                    var batchStatus by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        fun summarize() {
                            try {
                                if (!com.mangareader.sr.SrCore.initialized) return
                                val q = com.mangareader.sr.SrCore.queueStore.load()
                                batchStatus = if (q.isEmpty()) ""
                                else "增强中：${q.size} 章 ${q.sumOf { it.processedPages }}/${q.sumOf { it.totalPages }} 页"
                            } catch (_: Exception) {}
                        }
                        summarize()
                        com.mangareader.sr.SrCore.queueProgress.collect { summarize() }
                    }
                    if (compactTopBar) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("瑾", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.clickable { showDonation = true })
                            Text("${books?.size ?: 0} 本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (batchStatus.isNotEmpty()) {
                                Text(batchStatus, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onSettings() })
                            }
                        }
                    } else {
                        Column {
                            Text("瑾", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.clickable { showDonation = true })
                            Text("${books?.size ?: 0} 本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (batchStatus.isNotEmpty()) {
                                Text(batchStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onSettings() })
                            }
                        }
                    }
                },
                // ★ 快赢 #5：与其他页统一用 surface（消除跳页"提亮一档"色差）
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    IconButton(onClick = { isMultiSelect = true }) { Icon(Icons.Default.Edit, "编辑") }
                    IconButton(onClick = { showLayoutMenu = true }) { Icon(Icons.Default.GridView, "布局") }

                    IconButton(onClick = { showSearch = !showSearch }) { Icon(if (showSearch) Icons.Filled.Close else Icons.Default.Search, "搜索") }
                    // ★ F 历史记录页入口
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, "阅读历史") }
                    IconButton(onClick = { onRandomOpen() }, enabled = !books.isNullOrEmpty()) { Icon(Icons.Default.Casino, "随机打开") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
                }
            )
        },
        floatingActionButton = {
            val globalHide = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE).getBoolean("global_hide", false)
            // ★ 阅读设置 → 显示 可关：首页「继续阅读」按钮显隐（home_continue_read，默认开）
            val homeContinueOn = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE).getBoolean("home_continue_read", true)
            val lr = lastReading
            if (!isMultiSelect && !globalHide && homeContinueOn && lr != null) {
                Surface(
                    onClick = {
                        android.util.Log.d("HomeContinue", "bookId=${lr.bookId} path=${lr.path} updatedAt=${lr.updatedAt}")
                        openBookThrottled(lr.bookId, lr.path)
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        Modifier.height(40.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("继续阅读", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        bottomBar = {
            if (isMultiSelect && selectedBooks.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { showShelfDialog = true }) { Text("添加到书架", color = MaterialTheme.colorScheme.primary) }
                        TextButton(onClick = { showBatchTagPicker = true }) { Text("打标签", color = MaterialTheme.colorScheme.primary) }
                    run {
                        val selectedBookList = books.orEmpty().filter { it.id in selectedBooks }
                        val virtualCount = selectedBookList.count { it.source == "merged" }
                        val normalCount = selectedBookList.size - virtualCount
                        val canDissolve = virtualCount == 1 && normalCount == 0
                        val canMerge = !canDissolve && selectedBookList.size >= 2
                        val mergeLabel = if (canDissolve) "解散" else "合并"
                        val mergeEnabled = canDissolve || canMerge
                        TextButton(
                            onClick = {
                                if (canDissolve) {
                                    val virtualId = selectedBookList.first().id
                                    scope.launch(Dispatchers.IO) {
                                        dao.dissolveMerge(virtualId)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(ctx, "已解散", Toast.LENGTH_SHORT).show()
                                            isMultiSelect = false; selectedBooks = emptySet()
                                        }
                                    }
                                } else if (canMerge) {
                                    showMergeDialog = true
                                }
                            },
                            enabled = mergeEnabled,
                        ) { Text(mergeLabel, color = if (mergeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(onClick = {
                        if (selectedBooks.size == 1) showRenameSingle = true
                        else showRenameBatch = true
                    }) { Text("重命名", color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = { showDeleteConfirm = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (showSearch) {
                val searchHistory = remember { SearchHistory(ctx) }
                var history by remember { mutableStateOf(searchHistory.getHistory()) }
                var q by remember { mutableStateOf("") }
                val focusReq = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusReq.requestFocus() }
                Column {
                    OutlinedTextField(value = q, onValueChange = { q = it; searchQuery = it }, label = { Text("搜索书名、标签…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (q.isNotEmpty()) {
                                IconButton(onClick = { q = ""; searchQuery = "" }) { Icon(Icons.Default.Close, null) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focusReq),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (q.isNotBlank()) { searchHistory.add(q); history = searchHistory.getHistory() }
                        }))
                    if (history.isNotEmpty() && q.isEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("搜索历史", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { searchHistory.clear(); history = emptyList() }, contentPadding = PaddingValues(0.dp)) { Text("清除", fontSize = 12.sp) }
                        }
                        history.take(10).forEach { item ->
                            Row(Modifier.fillMaxWidth().clickable { q = item; searchQuery = item; searchHistory.add(item); history = searchHistory.getHistory() }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text(item, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FilterMode.entries.size) { i ->
                    val f = FilterMode.entries[i]
                    FilterChip(selected = filter == f && selectedShelfId == null, onClick = {
                        filter = f
                        prefs.edit().putString("library_filter", f.name).apply()
                        selectedShelfId = null
                        selectedShelfName = ""
                    }, label = { Text(f.label, fontSize = 13.sp) })
                }
                // 书架收纳（design.md §三）：最多 6 个 chip + 更多抽屉；选中的第 7+ 个书架顶替首位移除位
                val shelfLimit = 6
                val visibleShelves = if (bookshelves.size <= shelfLimit + 1) bookshelves else run {
                    val head = bookshelves.take(shelfLimit)
                    val sel = bookshelves.find { it.id == selectedShelfId }
                    if (sel != null && sel !in head) listOf(sel) + head.dropLast(1) else head
                }
                items(visibleShelves.size) { i ->
                    val shelf = visibleShelves[i]
                    FilterChip(selected = selectedShelfId == shelf.id, onClick = {
                        if (selectedShelfId == shelf.id) {
                            selectedShelfId = null
                            selectedShelfName = ""
                        } else {
                            selectedShelfId = shelf.id
                            selectedShelfName = shelf.name
                            filter = FilterMode.ALL
                            prefs.edit().putString("library_filter", FilterMode.ALL.name).apply()
                        }
                    }, label = { Text(shelf.name, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Book, null, Modifier.size(16.dp)) }
                    )
                }
                if (bookshelves.size > shelfLimit + 1) {
                    item {
                        FilterChip(selected = false, onClick = { showShelfDrawer = true },
                            label = { Text("书架 更多 ›", fontSize = 13.sp) })
                    }
                }
                if (compactTopBar && prefs.getBoolean("tag_filter_row_enabled", true)) {
                    tagFilterItems(
                        allTags = tagsWithCounts,
                        selectedIds = selectedTagIds,
                        recentIds = recentTagIds,
                        onToggle = { toggleLibraryTag(it) },
                        onRemove = { removeLibraryTag(it) },
                        onClear = { clearLibraryTags() },
                        onOpenPicker = { showTagPicker = true },
                    )
                }
            }
            if (!compactTopBar && selectedShelfId != null) {
                Text("书架: $selectedShelfName", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            if (!compactTopBar && prefs.getBoolean("tag_filter_row_enabled", true)) TagFilterRow(
                allTags = tagsWithCounts,
                selectedIds = selectedTagIds,
                recentIds = recentTagIds,
                onToggle = { toggleLibraryTag(it) },
                onRemove = { removeLibraryTag(it) },
                onClear = { clearLibraryTags() },
                onOpenPicker = { showTagPicker = true },
            )

            if (books == null && shelfItems == null) {
                // ★ 快赢 #8：大书库冷启动 1-2s 空屏补加载反馈
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if ((shelfItems ?: books!!.map { ShelfItem.Single(it) }).isEmpty() && deepHits.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (selectedTagIds.isNotEmpty() || selectedShelfId != null || filter != FilterMode.ALL || debouncedQuery.isNotEmpty()) {
                            Icon(Icons.Default.FilterAlt, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(16.dp))
                            Text("没有满足筛选条件的漫画", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                clearLibraryTags()
                                selectedShelfId = null; selectedShelfName = ""
                                filter = FilterMode.ALL
                                prefs.edit().putString("library_filter", FilterMode.ALL.name).apply()
                                searchQuery = ""
                            }) { Text("清除全部筛选") }
                        } else {
                            Icon(Icons.Default.MenuBook, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(16.dp))
                            Text("还没有漫画", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("点击设置导入文件夹", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                val thumbDecodePx = com.hippo.ehviewer.coil.CoverThumb.gridDecodePx(
                    LocalConfiguration.current.screenWidthDp, effectiveCols, 12.dp, 8.dp,
                )
                val renderItems = shelfItems ?: books!!.map { ShelfItem.Single(it) }
                // ★ 首页滚动记忆（2026-10-11 用户需求）：锚定首项 key 为主、index 兜底，落盘
                //   SharedPreferences——杀进程/滑掉最近任务后重进也回原位；打开书再返回的场景由
                //   「列表就绪后显式恢复」兜住（默认 rememberSaveable 在 books 异步加载完成前就被
                //   空列表钳到 0，返回后回顶即此病）。搜索结果态不记（结果集每次都是新的）。
                //   只存 index 不存像素 offset：列数变化下误差一屏内可接受（审查方案既定取舍）。
                val shelfItemKey: (ShelfItem) -> String = { when (it) { is ShelfItem.Single -> "b:${it.book.id}"; is ShelfItem.Series -> "s:${it.serverId}:${it.seriesId}" } }
                val shelfListState = rememberLazyListState()
                val shelfGridState = rememberLazyGridState()
                val renderItemsState = rememberUpdatedState(renderItems)
                var shelfRestored by remember { mutableStateOf(false) }
                fun persistShelfScroll(items: List<ShelfItem>, index: Int) {
                    if (items.isEmpty()) return
                    val item = items.getOrNull(index) ?: return
                    prefs.edit().putInt("library_scroll_index", index)
                        .putString("library_scroll_anchor", shelfItemKey(item)).apply()
                }
                LaunchedEffect(layoutMode, shelfListState, shelfGridState) {
                    snapshotFlow {
                        if (layoutMode == 3) shelfListState.firstVisibleItemIndex to shelfListState.isScrollInProgress
                        else shelfGridState.firstVisibleItemIndex to shelfGridState.isScrollInProgress
                    }.collect { (idx, scrolling) ->
                        if (shelfRestored && !scrolling && debouncedQuery.isEmpty()) {
                            persistShelfScroll(renderItemsState.value, idx)
                        }
                    }
                }
                LaunchedEffect(renderItems.isNotEmpty()) {
                    if (renderItems.isEmpty() || shelfRestored) return@LaunchedEffect
                    val savedIdx = prefs.getInt("library_scroll_index", -1)
                    val savedKey = prefs.getString("library_scroll_anchor", null)
                    val anchorIdx = savedKey?.let { key -> renderItems.indexOfFirst { shelfItemKey(it) == key } } ?: -1
                    val target = when {
                        anchorIdx >= 0 -> anchorIdx
                        savedIdx > 0 -> savedIdx.coerceAtMost(renderItems.size - 1)
                        else -> -1
                    }
                    if (target >= 0) {
                        if (layoutMode == 3) shelfListState.scrollToItem(target) else shelfGridState.scrollToItem(target)
                    }
                    shelfRestored = true
                }
                if (layoutMode == 3) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = shelfListState,
                        modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (deepHits.isNotEmpty()) {
                            item(key = "deep:header", contentType = "deepHeader") {
                                Text(
                                    "子文件夹命中 ${deepHits.size} 条",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                )
                            }
                            items(deepHits, key = { "deep:${it.bookId}:${it.jumpPath}" }, contentType = { "deepHit" }) { hit ->
                                DeepHitRow(hit, onTap = { openBookThrottled(hit.bookId, hit.jumpPath) })
                            }
                        }
                        items(renderItems, key = shelfItemKey, contentType = { when (it) { is ShelfItem.Single -> "book"; is ShelfItem.Series -> "series" } }) { item ->
                            when (item) {
                                is ShelfItem.Single -> {
                                    val b = item.book
                                    val onToggleSelect = remember(b.id, isMultiSelect) { { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } }
                                    val onTap = remember(b.id, isMultiSelect, b.source) { { if (isMultiSelect) { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } else if (b.source == "merged") mergedBookView = b else openBookThrottled(b.id, null) } }
                                    val onLongClick = remember(b.id) { { showBookInfo = b } }
                                    val onDelete = remember(b.id) { { deleteTarget = b } }
                                    BookListRow(b, engine, isMultiSelect, b.id in selectedBooks, thumbDecodePx, hideAllBadges,
                                        onToggleSelect = onToggleSelect,
                                        onTap = onTap,
                                        onLongClick = onLongClick,
                                        onDelete = onDelete)
                                }
                                is ShelfItem.Series -> {
                                    SeriesListRow(item, engine, thumbDecodePx, hideAllBadges, onTap = { onOpenKomgaSeries(item.serverId, item.seriesId) })
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(state = shelfGridState, columns = gridCols, contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (deepHits.isNotEmpty()) {
                            item(key = "deep:header", span = { GridItemSpan(maxLineSpan) }, contentType = "deepHeader") {
                                Text(
                                    "子文件夹命中 ${deepHits.size} 条",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                )
                            }
                            items(deepHits, key = { "deep:${it.bookId}:${it.jumpPath}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "deepHit" }) { hit ->
                                DeepHitRow(hit, onTap = { openBookThrottled(hit.bookId, hit.jumpPath) })
                            }
                        }
                        items(renderItems, key = shelfItemKey, contentType = { when (it) { is ShelfItem.Single -> "book"; is ShelfItem.Series -> "series" } }) { item ->
                            when (item) {
                                is ShelfItem.Single -> {
                                    val b = item.book
                                    val onToggleSelect = remember(b.id, isMultiSelect) { { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } }
                                    val onTap = remember(b.id, isMultiSelect, b.source) { { if (isMultiSelect) { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } else if (b.source == "merged") mergedBookView = b else openBookThrottled(b.id, null) } }
                                    val onLongClick = remember(b.id) { { showBookInfo = b } }
                                    val onDelete = remember(b.id) { { deleteTarget = b } }
                                    BookCard(b, engine, isMultiSelect, b.id in selectedBooks, thumbDecodePx, layoutMode, hideAllBadges, titleMarquee,
                                        onToggleSelect = onToggleSelect,
                                        onTap = onTap,
                                        onLongClick = onLongClick,
                                        onDelete = onDelete)
                                }
                                is ShelfItem.Series -> {
                                    SeriesCard(item, engine, thumbDecodePx, layoutMode, hideAllBadges, titleMarquee, onTap = { onOpenKomgaSeries(item.serverId, item.seriesId) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (randomLoading) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
    if (showLayoutMenu) {
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        var sliderCols by remember { mutableStateOf(if (isLandscape) landscapeCols else portraitCols) }
        var layoutSheetVisible by remember { mutableStateOf(false) }
        var dragOffset by remember { mutableStateOf(0f) }

        // ★ 自绘弹层必须自己接系统返回键（本层不是 Dialog/ModalBottomSheet，不拦截返回）：
        //   缺失时 back 会落在 library 路由上直接退 app（2026-10-11 用户实报）。
        //   与点遮罩/下拉拖拽关闭同款两步置 false。
        BackHandler {
            layoutSheetVisible = false
            showLayoutMenu = false
        }

        LaunchedEffect(Unit) { layoutSheetVisible = true }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (layoutSheetVisible) 0.3f else 0f))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = { layoutSheetVisible = false; showLayoutMenu = false }
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = layoutSheetVisible,
                enter = slideInVertically(tween(300)) { it },
                exit = slideOutVertically(tween(300)) { it }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                        .offset { IntOffset(0, dragOffset.roundToInt()) }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset > 200f) { layoutSheetVisible = false; showLayoutMenu = false }
                                    dragOffset = 0f
                                },
                                onVerticalDrag = { change, delta ->
                                    if (delta > 0) dragOffset += delta
                                    change.consume()
                                }
                            )
                        }
                        .clickable(interactionSource = null, indication = null, onClick = {}),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            )
                        }
                        Text("布局模式", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("紧凑网格", "松散网格", "仅封面", "列表").forEachIndexed { i, label ->
                        FilterChip(
                            selected = layoutMode == i,
                            onClick = {
                                layoutMode = i
                                prefs.edit().putInt("layout_mode", i).apply()
                            },
                            label = { Text(label) },
                        )
                    }
                }
                if (layoutMode != 3) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("列数（0=自动）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(if (sliderCols == 0) "自动" else "${sliderCols}列", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = sliderCols.toFloat(),
                        onValueChange = { v -> sliderCols = v.toInt() },
                        onValueChangeFinished = {
                            if (isLandscape) com.hippo.ehviewer.Settings.thumbColumnsLandscape.value = sliderCols
                            else com.hippo.ehviewer.Settings.thumbColumnsPortrait.value = sliderCols
                        },
                        valueRange = 0f..10f,
                        steps = 10,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("关闭", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("10列", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("排序方式", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortMode.entries.forEach { s ->
                        FilterChip(
                            selected = sort == s,
                            onClick = {
                                sort = s
                                prefs.edit().putString("library_sort", s.name).apply()
                            },
                            label = { Text(s.label) },
                        )
                    }
                }
                Text("排序方向", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sortAsc,
                        onClick = {
                            sortAsc = true
                            prefs.edit().putBoolean("library_sort_asc", true).apply()
                        },
                        label = { Text("升序") },
                    )
                    FilterChip(
                        selected = !sortAsc,
                        onClick = {
                            sortAsc = false
                            prefs.edit().putBoolean("library_sort_asc", false).apply()
                        },
                        label = { Text("降序") },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("隐藏所有角标", fontSize = 14.sp)
                        Text("开启后书架不显示来源与状态角标", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = hideAllBadges, onCheckedChange = {
                        hideAllBadges = it
                        prefs.edit().putBoolean("hide_all_badges", it).apply()
                    })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("书名超长滚动", fontSize = 14.sp)
                        Text("书名太长显示不全时在封面上滚动播放；默认关", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = titleMarquee, onCheckedChange = {
                        titleMarquee = it
                        prefs.edit().putBoolean("shelf_title_marquee", it).apply()
                    })
                }
                Text("软件方向", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("跟随手机" to 0, "竖屏" to 1, "横屏" to 2).forEach { (label, value) ->
                        FilterChip(
                            selected = currentOrientation == value,
                            onClick = {
                                currentOrientation = value
                                prefs.edit().putInt("app_orientation", value).apply()
                                (ctx as? android.app.Activity)?.requestedOrientation = when (value) {
                                    1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                    else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }

                    }
                }
            }
        }
    }
    if (showDonation) DonationDialog(onDismiss = { showDonation = false })

    // ★ "需全量下载"对话框统一宿主（原内联块移入 RemoteDownloadDialogHost，浏览页同挂）
    com.mangareader.smbbridge.RemoteDownloadDialogHost()

    showBookSettings?.let { b ->
        BookSettingsSheetHost(
            book = b,
            engine = engine,
            onDismiss = { showBookSettings = null },
        )
    }

    if (mergedBookView != null) {
        val virtualBook = mergedBookView!!
        LaunchedEffect(virtualBook.id) {
            mergeMembers = withContext(Dispatchers.IO) { dao.getMergeMembers(virtualBook.id) }
        }
        BackHandler { mergedBookView = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(virtualBook.displayTitle) },
                    navigationIcon = { IconButton(onClick = { mergedBookView = null }) { Icon(Icons.Default.ArrowBack, "返回") } },

                )
            }
        ) { pad ->
            if (mergeMembers.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Text("暂无成员", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val memberThumbPx = com.hippo.ehviewer.coil.CoverThumb.gridDecodePx(
                    LocalConfiguration.current.screenWidthDp, effectiveCols, 12.dp, 8.dp,
                )
                LazyVerticalGrid(
                    columns = gridCols,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(pad),
                ) {
                    items(mergeMembers, key = { it.id }) { b ->
                        BookCard(b, engine, false, false, memberThumbPx, layoutMode, hideAllBadges, titleMarquee,
                            onToggleSelect = {},
                            onTap = { openBookThrottled(b.id, null) },
                            onLongClick = { showBookInfo = b },
                            onDelete = { deleteTarget = b })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(b: Book, engine: ArchiveEngine, isMultiSelect: Boolean, isSelected: Boolean, decodePx: Int, layoutMode: Int, hideAllBadges: Boolean, titleMarquee: Boolean, onToggleSelect: () -> Unit, onTap: () -> Unit, onLongClick: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    // ★ LV119 对齐参考项目：直接用 coverPath (cv:...) 作为 Coil3 model，
    //   CoverFetcher 返回原始字节 → Coil3 decoders 自动识别动图（GIF/WebP/APNG）
    val progress = if (b.totalPages > 0) b.readPages.toFloat() / b.totalPages else 0f
    val isFinished = b.totalPages > 0 && b.readPages >= b.totalPages
    val isReading = !isFinished && (b.readPages > 0 || (b.lastReadAt != null && b.lastReadAt > 0))
    val status = when {
        isFinished -> "已读完" to Color(0xFF81C784)
        isReading -> "阅读中" to Color(0xFFFFB74D)
        else -> "未读" to Color(0xFFE57373)
    }

    Surface(
        modifier = Modifier.combinedClickable(onClick = onTap, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
    ) {
        Column {
        // ★ 远程不支持流式格式 → 第一时间显示"需下载"占位符，不触发封面加载
        //   （2026-10-06：mobi/azw/azw3 M2 流式已通、两个 Handler 集合清空后恒 false；
        //   原硬编码 setOf("mobi","azw3") 漏 azw 且不跟随流式支持范围，改走 Handler 查询）
        val fileExt = b.fileType.lowercase()
        val isRemoteUnsupported = (b.source == "smb" || b.source == "webdav") && b.originalPath == null &&
            (if (b.source == "smb") com.mangareader.smbbridge.SmbUnsupportedFormatHandler.isUnsupported(fileExt)
             else com.mangareader.webdavbridge.WebDavUnsupportedFormatHandler.isUnsupported(fileExt))

        val coverRatio = 0.72f
        Box(Modifier.fillMaxWidth().aspectRatio(coverRatio).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            when {
                isRemoteUnsupported -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                        Text(text = "需下载", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Text(text = fileExt.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.padding(top = 2.dp))
                    }
                }
                b.displayCoverPath != null -> {
                    val dispCover = b.displayCoverPath!!
                    if (b.source == "komga") android.util.Log.i("CoverFetch", "BookCard komga coverPath=${b.coverPath} title=${b.title}")
                    val coverRequest = remember(dispCover, decodePx) {
                        coil3.request.ImageRequest.Builder(ctx)
                            .data(com.mangareader.viewer.CoverData(dispCover, dynOn = false))
                            .coverThumb(
                                decodePx,
                                memoryKey = "cover:${b.id}@$dispCover",
                                diskKey = "$dispCover",
                            )
                            .decoderFactory(com.mangareader.coil.CoverAnimGateDecoder)
                            .build()
                    }
                    coil3.compose.SubcomposeAsyncImage(
                        model = coverRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            if (layoutMode == 0) {
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))) {
                    Text(text = b.displayTitle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, modifier = Modifier.fillMaxWidth().then(if (titleMarquee) Modifier.basicMarquee() else Modifier).padding(horizontal = 6.dp, vertical = 4.dp))
                }
            } else {
                Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
            }
            if (!hideAllBadges) {
                Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = status.second.copy(alpha = 0.9f)) {
                    Text(text = status.first, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp,
                        color = Color(0xFF1A1A1A))  // ★ #3 对比度：浅粉彩底配白字 1.7~2.9:1 不达标，改深字（6.6~10:1）
                }
            }
            // ★ 来源角标（任务A）：远程书显示来源，已下载书显示"已下载"来源
            if (!hideAllBadges) {
                if (b.source == "smb" || b.source == "webdav" || b.source == "komga") {
                    Surface(
                        Modifier.align(Alignment.TopStart).padding(4.dp),
                        RoundedCornerShape(6.dp),
                        color = if (b.source == "smb") Color(0xCC4A7BD4) else if (b.source == "webdav") Color(0xCC8A5CD4) else Color(0xCC5CB85C),
                    ) {
                        Text(
                            text = if (b.source == "smb") "SMB" else if (b.source == "webdav") "WebDAV" else "Komga",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            color = Color.White,
                        )
                    }
                } else if (b.originalPath != null) {
                    val isFromSmb = b.originalPath!!.startsWith("smb:")
                    Surface(
                        Modifier.align(Alignment.TopStart).padding(4.dp),
                        RoundedCornerShape(6.dp),
                        color = if (isFromSmb) Color(0xCC4A7BD4) else Color(0xCC8A5CD4),
                    ) {
                        Text(
                            text = if (isFromSmb) "SMB↓" else "WebDAV↓",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            color = Color.White,
                        )
                    }
                }
            }
            if (isMultiSelect) {
                Box(Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    Checkbox(checked = isSelected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                }
            }
            if (b.totalPages > 0) Text(text = "${b.readPages}/${b.totalPages}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (progress > 0f) Text(text = "${(progress * 100).toInt()}%", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        if (layoutMode == 1) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(text = b.displayTitle, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(b: Book, engine: ArchiveEngine, isMultiSelect: Boolean, isSelected: Boolean, decodePx: Int, hideAllBadges: Boolean, onToggleSelect: () -> Unit, onTap: () -> Unit, onLongClick: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val progress = if (b.totalPages > 0) b.readPages.toFloat() / b.totalPages else 0f
    val isFinished = b.totalPages > 0 && b.readPages >= b.totalPages
    val isReading = !isFinished && (b.readPages > 0 || (b.lastReadAt != null && b.lastReadAt > 0))
    val statusText = when {
        isFinished -> "已读完"
        isReading -> "阅读中"
        else -> "未读"
    }
    val statusColor = when {
        isFinished -> Color(0xFF81C784)
        isReading -> Color(0xFFFFB74D)
        else -> Color(0xFFE57373)
    }
    val fileExt = b.fileType.lowercase()
    val isRemoteUnsupported = (b.source == "smb" || b.source == "webdav") && b.originalPath == null && fileExt in setOf("mobi", "azw3")

    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isMultiSelect) {
                Checkbox(checked = isSelected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary), modifier = Modifier.padding(end = 8.dp))
            }
            Box(Modifier.size(width = 56.dp, height = 78.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                when {
                    isRemoteUnsupported -> {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
                    }
                    b.displayCoverPath != null -> {
                        val dispCover = b.displayCoverPath!!
                        val coverRequest = remember(dispCover, decodePx) {
                            coil3.request.ImageRequest.Builder(ctx)
                                .data(com.mangareader.viewer.CoverData(dispCover, dynOn = false))
                                .coverThumb(decodePx, memoryKey = "cover:${b.id}@$dispCover", diskKey = "$dispCover")
                                .decoderFactory(com.mangareader.coil.CoverAnimGateDecoder)
                                .build()
                        }
                        coil3.compose.SubcomposeAsyncImage(model = coverRequest, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                if (progress > 0f) Text(text = "${(progress * 100).toInt()}%", fontSize = 9.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = b.displayTitle, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hideAllBadges) {
                        Surface(shape = RoundedCornerShape(4.dp), color = statusColor.copy(alpha = 0.9f)) {
                            Text(text = statusText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp,
                                color = Color(0xFF1A1A1A))  // ★ #3 对比度同上（浅底深字）
                        }
                    }
                    if (b.source == "smb" || b.source == "webdav" || b.source == "komga") {
                        Text(text = b.source.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(text = fileExt.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (progress > 0f && b.totalPages > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (b.totalPages > 0) {
                    Text(text = "${b.readPages}/${b.totalPages}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (progress > 0f) {
                    Text(text = "${(progress * 100).toInt()}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
                b.lastReadAt?.takeIf { it > 0 }?.let {
                    Text(
                        text = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesCard(series: ShelfItem.Series, engine: ArchiveEngine, decodePx: Int, layoutMode: Int, hideAllBadges: Boolean, titleMarquee: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val coverPath = series.coverPath
    val maxProgress = series.books.maxOfOrNull { if (it.totalPages > 0) it.readPages.toFloat() / it.totalPages else 0f } ?: 0f
    val anyReading = series.books.any { it.readPages > 0 || (it.lastReadAt != null && it.lastReadAt > 0) }
    val allFinished = series.books.isNotEmpty() && series.books.all { it.totalPages > 0 && it.readPages >= it.totalPages }
    val status = when {
        allFinished -> "已读完" to Color(0xFF81C784)
        anyReading -> "阅读中" to Color(0xFFFFB74D)
        else -> "未读" to Color(0xFFE57373)
    }

    Surface(
        modifier = Modifier.clickable(onClick = onTap),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
    ) {
        Column {
            val coverRatio = 0.72f
            Box(Modifier.fillMaxWidth().aspectRatio(coverRatio).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (coverPath != null) {
                    val coverRequest = remember(coverPath, decodePx) {
                        coil3.request.ImageRequest.Builder(ctx)
                            .data(com.mangareader.viewer.CoverData(coverPath, dynOn = false))
                            .coverThumb(
                                decodePx,
                                memoryKey = "cover:series:${series.serverId}:${series.seriesId}@$coverPath",
                                diskKey = "$coverPath",
                            )
                            .decoderFactory(com.mangareader.coil.CoverAnimGateDecoder)
                            .build()
                    }
                    coil3.compose.SubcomposeAsyncImage(
                        model = coverRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (layoutMode == 0) {
                    Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))) {
                        Text(text = series.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, modifier = Modifier.fillMaxWidth().then(if (titleMarquee) Modifier.basicMarquee() else Modifier).padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
                }
                if (!hideAllBadges) {
                    Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = status.second.copy(alpha = 0.9f)) {
                        Text(text = status.first, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp,
                        color = Color(0xFF1A1A1A))  // ★ #3 对比度：浅粉彩底配白字 1.7~2.9:1 不达标，改深字（6.6~10:1）
                    }
                }
                if (!hideAllBadges) {
                    Surface(
                        Modifier.align(Alignment.TopStart).padding(4.dp),
                        RoundedCornerShape(6.dp),
                        color = Color(0xCC5CB85C),
                    ) {
                        Text(
                            text = "Komga",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            color = Color.White,
                        )
                    }
                }
                Surface(Modifier.align(Alignment.BottomEnd).padding(6.dp), RoundedCornerShape(4.dp), color = Color(0xCC000000)) {
                    Text(text = "${series.volumeCount}卷", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
                if (maxProgress > 0f) Text(text = "${(maxProgress * 100).toInt()}%", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
            if (layoutMode == 1) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(text = series.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesListRow(series: ShelfItem.Series, engine: ArchiveEngine, decodePx: Int, hideAllBadges: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val coverPath = series.coverPath
    val maxProgress = series.books.maxOfOrNull { if (it.totalPages > 0) it.readPages.toFloat() / it.totalPages else 0f } ?: 0f
    val anyReading = series.books.any { it.readPages > 0 || (it.lastReadAt != null && it.lastReadAt > 0) }
    val allFinished = series.books.isNotEmpty() && series.books.all { it.totalPages > 0 && it.readPages >= it.totalPages }
    val statusText = when {
        allFinished -> "已读完"
        anyReading -> "阅读中"
        else -> "未读"
    }
    val statusColor = when {
        allFinished -> Color(0xFF81C784)
        anyReading -> Color(0xFFFFB74D)
        else -> Color(0xFFE57373)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 78.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (coverPath != null) {
                    val coverRequest = remember(coverPath, decodePx) {
                        coil3.request.ImageRequest.Builder(ctx)
                            .data(com.mangareader.viewer.CoverData(coverPath, dynOn = false))
                            .coverThumb(decodePx, memoryKey = "cover:series:${series.serverId}:${series.seriesId}@$coverPath", diskKey = "$coverPath")
                            .decoderFactory(com.mangareader.coil.CoverAnimGateDecoder)
                            .build()
                    }
                    coil3.compose.SubcomposeAsyncImage(model = coverRequest, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                if (maxProgress > 0f) Text(text = "${(maxProgress * 100).toInt()}%", fontSize = 9.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = series.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hideAllBadges) {
                        Surface(shape = RoundedCornerShape(4.dp), color = statusColor.copy(alpha = 0.9f)) {
                            Text(text = statusText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp,
                                color = Color(0xFF1A1A1A))  // ★ #3 对比度同上（浅底深字）
                        }
                    }
                    Text(text = "KOMGA", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(text = "${series.volumeCount}卷", modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (maxProgress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { maxProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (maxProgress > 0f) {
                    Text(text = "${(maxProgress * 100).toInt()}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
                series.books.maxOfOrNull { it.lastReadAt ?: 0L }?.takeIf { it > 0 }?.let {
                    Text(
                        text = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

}
private fun clearSmbPageCache(serverId: Long, remote: String): Long {
    val pagePath = com.hippo.ehviewer.smb.SmbCache.cachePathForRemoteFile(serverId, remote)
    val pageFile = java.io.File(pagePath.toString())
    var freed = 0L
    if (pageFile.isFile) freed += pageFile.length()
    pageFile.delete()
    val uhdrPath = com.hippo.ehviewer.image.hdr.HdrConvertCache.uhdrSiblingOf(pagePath)
    if (uhdrPath.toString() != pagePath.toString()) {
        val uhdrFile = java.io.File(uhdrPath.toString())
        if (uhdrFile.isFile) freed += uhdrFile.length()
        uhdrFile.delete()
    }
    com.hippo.ehviewer.smb.SmbCache.markAbsent(pagePath)
    com.hippo.ehviewer.smb.SmbCache.markAbsent(uhdrPath)
    return freed
}

/** 删除 WebDAV 单本页缓存（原始 + HDR .jpg 兄弟），返回释放字节数 */
private fun clearWebDavPageCache(serverId: Long, remote: String): Long {
    val pagePath = com.hippo.ehviewer.webdav.WebDavCache.cachePathForRemoteFile(serverId, remote)
    val pageFile = java.io.File(pagePath.toString())
    var freed = 0L
    if (pageFile.isFile) freed += pageFile.length()
    pageFile.delete()
    val uhdrPath = com.hippo.ehviewer.image.hdr.HdrConvertCache.uhdrSiblingOf(pagePath)
    if (uhdrPath.toString() != pagePath.toString()) {
        val uhdrFile = java.io.File(uhdrPath.toString())
        if (uhdrFile.isFile) freed += uhdrFile.length()
        uhdrFile.delete()
    }
    com.hippo.ehviewer.webdav.WebDavCache.markAbsent(pagePath)
    com.hippo.ehviewer.webdav.WebDavCache.markAbsent(uhdrPath)
    return freed
}

/** 删除三类提取缓存目录，返回释放字节数 */
private fun deleteExtractDirs(cacheKey: String): Long {
    var freed = 0L
    for (dir in listOf(
        com.hippo.ehviewer.library.ArchiveStreamPageCache.dirFor(cacheKey),
        com.hippo.ehviewer.library.SolidExtractCache.dirFor(cacheKey),
        com.hippo.ehviewer.library.DocumentExtractCache.dirFor(cacheKey),
    )) {
        val f = java.io.File(dir.toString())
        if (f.isDirectory) {
            f.walkBottomUp().forEach { if (it.isFile) freed += it.length() }
            f.deleteRecursively()
        }
    }
    return freed
}
