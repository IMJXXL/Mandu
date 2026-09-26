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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged

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
                ?: volumes.first().title
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
            val an = when (a) { is ShelfItem.Single -> a.book.title; is ShelfItem.Series -> a.title }
            val bn = when (b) { is ShelfItem.Single -> b.book.title; is ShelfItem.Series -> b.title }
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

@OptIn(ExperimentalMaterial3Api::class)
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

    // ★ v1.11 排序记忆修复：sort/sortAsc 原为纯内存 remember（Library.kt:88-89），选完不落盘，
    //   进书返回（NavHost dispose→重组）或冷启动都会重置回 LAST。改为 SharedPreferences 持久化，
    //   与本项目 read_mode_$bookId / library prefs 同一套风格；valueOf 包 try-catch 防枚举变更崩溃。
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }

    var books by remember { mutableStateOf<List<Book>?>(null) }
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
    var coverEditBook by remember { mutableStateOf<Book?>(null) }

    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedBooks by remember { mutableStateOf(setOf<Long>()) }
    BackHandler(isMultiSelect) { isMultiSelect = false; selectedBooks = emptySet() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShelfDialog by remember { mutableStateOf(false) }
    var showRenameBatch by remember { mutableStateOf(false) }
    var showCreateShelf by remember { mutableStateOf(false) }
    var newShelfName by remember { mutableStateOf("") }

    var selectedShelfId by remember { mutableStateOf<Long?>(null) }
    var selectedShelfName by remember { mutableStateOf("") }

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

    LaunchedEffect(Unit) {
        dao.getAllBookshelves().collect { bookshelves = it }
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

    LaunchedEffect(sort, sortAsc, debouncedQuery, filter, selectedShelfId) {
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
        flow.distinctUntilChanged().collect {
            // ★ 取证：书架数据加载完成（死亡若发生在 books 加载前后可由 stages 定位）
            com.mangareader.DiagTracer.mark(ctx, "library: books loaded n=${it.size}")
            val canAggregate = debouncedQuery.isEmpty() && selectedShelfId == null && filter == FilterMode.ALL
            if (canAggregate) {
                val aggregated = withContext(Dispatchers.IO) { sortShelfItems(aggregateKomgaSeries(it), sort) }
                books = it
                shelfItems = if (sortAsc) aggregated.reversed() else aggregated
            } else {
                val ordered = withContext(Dispatchers.IO) {
                    if (sort == SortMode.NAME) {
                        it.sortedWith(Comparator { a, b -> naturalCompare(a.title, b.title) })
                    } else it
                }
                books = if (sortAsc) ordered.reversed() else ordered
                shelfItems = null
            }
        }
    }

    deleteTarget?.let { b ->
        val customNote = if (b.customCoverPath != null) "已设置的自定义封面将一并删除。" else ""
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除") },
            text = { Text("删除「${b.title}」？不删原始文件。$customNote") },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { b.customCoverPath?.let { engine.deleteCustomCoverByPath(it) } }
                    dao.deleteBook(b); deleteTarget = null
                }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
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
                                                        Toast.makeText(ctx, "无法删除「${b.title}」: ${e.message}", Toast.LENGTH_SHORT).show()
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
                            dao.deleteBooksByIds(selectedBooks.toList())
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
                Column {
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
        AlertDialog(onDismissRequest = { showBookInfo = null }, title = { Text(b.title) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        coil3.compose.AsyncImage(
                            model = coil3.request.ImageRequest.Builder(ctx)
                                .data(com.mangareader.viewer.CoverData(b.displayCoverPath ?: "", dynOn = false))
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(width = 96.dp, height = 128.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
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

    coverEditBook?.let { b ->
        CoverEditDialog(
            book = b, engine = engine, ctx = ctx,
            onResult = { newPath ->
                scope.launch(Dispatchers.IO) {
                    // 新封面文件名带新时间戳（缓存 key 随之更新），旧文件在此清理
                    b.customCoverPath?.let { engine.deleteCustomCoverByPath(it) }
                    dao.updateBookCustomCoverPath(b.id, newPath)
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

    Scaffold(
        topBar = {
            if (isMultiSelect) TopAppBar(
                title = { Text("已选 ${selectedBooks.size} 本") },
                navigationIcon = { IconButton(onClick = { isMultiSelect = false; selectedBooks = emptySet() }) { Icon(Icons.Default.Close, "取消选择") } },
                actions = {
                    // 批量离线超分：把选中的书全部加入后台超分队列
                    IconButton(onClick = {
                        val targets = books.orEmpty().filter { it.id in selectedBooks }
                        // 异步入队（大书 IO 不卡主线程），完成后主线程 Toast
                        com.mangareader.sr.SrBatchRunner.enqueueBooks(ctx, targets.map { it.id to it.title }) { n ->
                            (ctx as? android.app.Activity)?.runOnUiThread {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (n > 0) "已加入批量增强队列（$n 章），通知栏可看进度\n（安卓13+ 需先允许通知权限）" else "所选书籍已在队列中",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        isMultiSelect = false; selectedBooks = emptySet()
                    }) { Icon(Icons.Default.AutoFixHigh, "批量增强") }
                    IconButton(onClick = { selectedBooks = if (selectedBooks.isEmpty()) books.orEmpty().map { it.id }.toSet() else emptySet() }) {
                        Icon(Icons.Default.SelectAll, if (selectedBooks.isEmpty()) "全选" else "取消全选")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) else TopAppBar(
                title = { Column {
                    Text("瑾", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.clickable { showDonation = true })
                    Text("${books?.size ?: 0} 本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { isMultiSelect = true }) { Icon(Icons.Default.Edit, "编辑") }
                    IconButton(onClick = { showLayoutMenu = true }) { Icon(Icons.Default.GridView, "布局") }

                    IconButton(onClick = { showSearch = !showSearch }) { Icon(if (showSearch) Icons.Filled.Close else Icons.Default.Search, "搜索") }
                    // ★ F 历史记录页入口
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, "阅读历史") }
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
                    TextButton(onClick = { showRenameBatch = true }) { Text("重命名", color = MaterialTheme.colorScheme.primary) }
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
                    OutlinedTextField(value = q, onValueChange = { q = it; searchQuery = it }, label = { Text("搜索...") },
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
                items(bookshelves.size) { i ->
                    val shelf = bookshelves[i]
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
            }
            if (selectedShelfId != null) {
                Text("书架: $selectedShelfName", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            if (books == null && shelfItems == null) {
                Box(Modifier.fillMaxSize())
            } else if ((shelfItems ?: books!!.map { ShelfItem.Single(it) }).isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(16.dp))
                        Text("还没有漫画", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("点击设置导入文件夹", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                val thumbDecodePx = com.hippo.ehviewer.coil.CoverThumb.gridDecodePx(
                    LocalConfiguration.current.screenWidthDp, effectiveCols, 12.dp, 8.dp,
                )
                val renderItems = shelfItems ?: books!!.map { ShelfItem.Single(it) }
                if (layoutMode == 3) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(renderItems, key = { when (it) { is ShelfItem.Single -> "b:${it.book.id}"; is ShelfItem.Series -> "s:${it.serverId}:${it.seriesId}" } }, contentType = { when (it) { is ShelfItem.Single -> "book"; is ShelfItem.Series -> "series" } }) { item ->
                            when (item) {
                                is ShelfItem.Single -> {
                                    val b = item.book
                                    val onToggleSelect = remember(b.id, isMultiSelect) { { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } }
                                    val onTap = remember(b.id, isMultiSelect) { { if (isMultiSelect) { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } else openBookThrottled(b.id, null) } }
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
                    LazyVerticalGrid(columns = gridCols, contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(renderItems, key = { when (it) { is ShelfItem.Single -> "b:${it.book.id}"; is ShelfItem.Series -> "s:${it.serverId}:${it.seriesId}" } }, contentType = { when (it) { is ShelfItem.Single -> "book"; is ShelfItem.Series -> "series" } }) { item ->
                            when (item) {
                                is ShelfItem.Single -> {
                                    val b = item.book
                                    val onToggleSelect = remember(b.id, isMultiSelect) { { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } }
                                    val onTap = remember(b.id, isMultiSelect) { { if (isMultiSelect) { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } else openBookThrottled(b.id, null) } }
                                    val onLongClick = remember(b.id) { { showBookInfo = b } }
                                    val onDelete = remember(b.id) { { deleteTarget = b } }
                                    BookCard(b, engine, isMultiSelect, b.id in selectedBooks, thumbDecodePx, layoutMode, hideAllBadges,
                                        onToggleSelect = onToggleSelect,
                                        onTap = onTap,
                                        onLongClick = onLongClick,
                                        onDelete = onDelete)
                                }
                                is ShelfItem.Series -> {
                                    SeriesCard(item, engine, thumbDecodePx, layoutMode, hideAllBadges, onTap = { onOpenKomgaSeries(item.serverId, item.seriesId) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showLayoutMenu) {
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        var sliderCols by remember { mutableStateOf(if (isLandscape) landscapeCols else portraitCols) }
        var layoutSheetVisible by remember { mutableStateOf(false) }
        var dragOffset by remember { mutableStateOf(0f) }

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

                    }
                }
            }
        }
    }
    if (showDonation) DonationDialog(onDismiss = { showDonation = false })

    // ★ "需全量下载"对话框统一宿主（原内联块移入 RemoteDownloadDialogHost，浏览页同挂）
    com.mangareader.smbbridge.RemoteDownloadDialogHost()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(b: Book, engine: ArchiveEngine, isMultiSelect: Boolean, isSelected: Boolean, decodePx: Int, layoutMode: Int, hideAllBadges: Boolean, onToggleSelect: () -> Unit, onTap: () -> Unit, onLongClick: () -> Unit, onDelete: () -> Unit) {
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
        // ★ 远程不支持流式格式（mobi/azw3）→ 第一时间显示"需下载"占位符，不触发封面加载
        val fileExt = b.fileType.lowercase()
        val isRemoteUnsupported = (b.source == "smb" || b.source == "webdav") && b.originalPath == null && fileExt in setOf("mobi", "azw3")

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
                    Text(text = b.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp))
                }
            } else {
                Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
            }
            if (!hideAllBadges) {
                Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = status.second.copy(alpha = 0.9f)) {
                    Text(text = status.first, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
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
                Text(text = b.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
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
                Text(text = b.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hideAllBadges) {
                        Surface(shape = RoundedCornerShape(4.dp), color = statusColor.copy(alpha = 0.9f)) {
                            Text(text = statusText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = Color.White)
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
private fun SeriesCard(series: ShelfItem.Series, engine: ArchiveEngine, decodePx: Int, layoutMode: Int, hideAllBadges: Boolean, onTap: () -> Unit) {
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
                        Text(text = series.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
                }
                if (!hideAllBadges) {
                    Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = status.second.copy(alpha = 0.9f)) {
                        Text(text = status.first, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
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
                            Text(text = statusText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = Color.White)
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
