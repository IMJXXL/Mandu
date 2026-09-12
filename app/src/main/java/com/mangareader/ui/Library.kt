package com.mangareader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

enum class SortMode(val label: String) { LAST("最近阅读"), NAME("名称"), DATE("添加时间"), PROGRESS("阅读进度") }
enum class FilterMode(val label: String) { ALL("全部"), READING("阅读中"), UNREAD("未读"), FINISHED("已读完"), FAV("收藏") }
enum class LayoutMode(val cols: Int, val label: String) { GRID2(2, "2列"), GRID3(3, "3列"), GRID4(4, "4列"), GRID5(5, "5列"), LIST(1, "列表") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Library(openBook: (Long, String?) -> Unit, toggleTheme: () -> Unit, onSettings: () -> Unit = {}, onOpenHistory: () -> Unit = {}) {
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

    var books by remember { mutableStateOf(listOf<Book>()) }
    var bookshelves by remember { mutableStateOf(listOf<Bookshelf>()) }
    var sort by remember {
        mutableStateOf(
            try { SortMode.valueOf(prefs.getString("library_sort", SortMode.NAME.name)!!) } catch (_: Exception) { SortMode.NAME }
        )
    }
    var sortAsc by remember { mutableStateOf(prefs.getBoolean("library_sort_asc", false)) }
    var filter by remember { mutableStateOf(FilterMode.ALL) }
    var showSort by remember { mutableStateOf(false) }
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
    var manualCols by remember { mutableStateOf<Int?>(null) }
    val effectiveCols = manualCols ?: autoCols
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

    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedBooks by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShelfDialog by remember { mutableStateOf(false) }
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
    LaunchedEffect(books.map { it.id }) {
        if (books.isNotEmpty()) {
            val limitedDispatcher = Dispatchers.IO.limitedParallelism(3)
            val jobs = books.map { book ->
                coverScope.launch(limitedDispatcher) {
                    if (book.coverPath == null) return@launch
                    val key = synchronized(md5) { md5.reset(); md5.digest(book.coverPath!!.toByteArray()) }.joinToString("") { "%02x".format(it) }
                    val file = java.io.File(ctx.filesDir, "covers/$key.thumb")
                    if (file.exists()) return@launch
                    try { engine.loadCover(book.coverPath!!) } catch (_: Exception) {}
                }
            }
            jobs.forEach { it.join() }
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
        flow.collect {
            val ordered = if (sort == SortMode.NAME) {
                it.sortedWith(Comparator { a, b -> naturalCompare(a.title, b.title) })
            } else it
            books = if (sortAsc) ordered.reversed() else ordered
        }
    }

    deleteTarget?.let { b ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除") },
            text = { Text("删除「${b.title}」？不删原始文件。") },
            confirmButton = { TextButton(onClick = { scope.launch { dao.deleteBook(b); deleteTarget = null } }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
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
                    Text("确定删除选中的 ${selectedBooks.size} 本漫画？")
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
                                            }
                                        } catch (e: Exception) { Log.e("Library", "delete file error: ${e.message}") }
                                    }
                                }
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

    Scaffold(
        topBar = {
            if (isMultiSelect) TopAppBar(
                title = { Text("已选 ${selectedBooks.size} 本") },
                navigationIcon = { IconButton(onClick = { isMultiSelect = false; selectedBooks = emptySet() }) { Icon(Icons.Default.Close, "取消选择") } },
                actions = {
                    // 批量离线超分：把选中的书全部加入后台超分队列
                    IconButton(onClick = {
                        val targets = books.filter { it.id in selectedBooks }
                        // 异步入队（大书 IO 不卡主线程），完成后主线程 Toast
                        com.mangareader.sr.SrBatchRunner.enqueueBooks(ctx, targets.map { it.id to it.title }) { n ->
                            (ctx as? android.app.Activity)?.runOnUiThread {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (n > 0) "已加入批量超分队列（$n 章），通知栏可看进度\n（安卓13+ 需先允许通知权限）" else "所选书籍已在队列中",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        isMultiSelect = false; selectedBooks = emptySet()
                    }) { Icon(Icons.Default.AutoFixHigh, "批量超分") }
                    IconButton(onClick = { selectedBooks = if (selectedBooks.isEmpty()) books.map { it.id }.toSet() else emptySet() }) {
                        Icon(Icons.Default.SelectAll, if (selectedBooks.isEmpty()) "全选" else "取消全选")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) else TopAppBar(
                title = { Column {
                    Text("瑾", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.clickable { showDonation = true })
                    Text("${books.size} 本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { isMultiSelect = true }) { Icon(Icons.Default.Edit, "编辑") }
                    IconButton(onClick = { showSort = true }) { Icon(Icons.Default.Sort, "排序") }
                    IconButton(onClick = {
                        val next = !sortAsc
                        sortAsc = next
                        prefs.edit().putBoolean("library_sort_asc", next).apply()
                    }) {
                        Icon(if (sortAsc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, "排序方向")
                    }
                    val colsCandidates = if (isTablet) listOf(1, 3, 4, 6) else listOf(1, 2, 3, 4, 5)
                    IconButton(onClick = {
                        val idx = colsCandidates.indexOf(effectiveCols).coerceAtLeast(0)
                        manualCols = colsCandidates[(idx + 1) % colsCandidates.size]
                    }) {
                        Icon(when (effectiveCols) {
                            1 -> Icons.Default.ViewList
                            2 -> Icons.Default.ViewModule
                            3 -> Icons.Default.GridView
                            4 -> Icons.Default.Dashboard
                            else -> Icons.Default.Apps
                        }, "布局")
                    }
                    IconButton(onClick = { showSearch = !showSearch }) { Icon(if (showSearch) Icons.Filled.Close else Icons.Default.Search, "搜索") }
                    // ★ F 历史记录页入口
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, "阅读历史") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        SortMode.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = {
                            sort = s
                            prefs.edit().putString("library_sort", s.name).apply()
                            showSort = false
                        },
                            leadingIcon = { if (sort == s) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) else Spacer(Modifier.size(24.dp)) }) }
                    }
                }
            )
        },
        floatingActionButton = {
            val globalHide = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE).getBoolean("global_hide", false)
            val lr = lastReading
            if (!isMultiSelect && !globalHide && lr != null) {
                Surface(
                    onClick = { openBookThrottled(lr.bookId, lr.path) },
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
            if (books.isEmpty()) {
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
                // ★ 封面解码尺寸：按列数/边距推导（CoverThumb 规则），避免 600×800 过度解码
                val thumbDecodePx = com.hippo.ehviewer.coil.CoverThumb.gridDecodePx(
                    LocalConfiguration.current.screenWidthDp, effectiveCols, 12.dp, 8.dp,
                )
                LazyVerticalGrid(columns = GridCells.Fixed(effectiveCols), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(books, key = { it.id }, contentType = { "book" }) { b ->
                        val onToggleSelect = remember(b.id, isMultiSelect) { { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } }
                        val onTap = remember(b.id, isMultiSelect) { { if (isMultiSelect) { selectedBooks = if (b.id in selectedBooks) selectedBooks - b.id else selectedBooks + b.id } else openBookThrottled(b.id, null) } }
                        val onLongClick = remember(b.id) { { showBookInfo = b } }
                        val onDelete = remember(b.id) { { deleteTarget = b } }
                        BookCard(b, engine, isMultiSelect, b.id in selectedBooks, thumbDecodePx,
                            onToggleSelect = onToggleSelect,
                            onTap = onTap,
                            onLongClick = onLongClick,
                            onDelete = onDelete)
                    }
                }
            }
        }
    }
    if (showDonation) DonationDialog(onDismiss = { showDonation = false })

    val remoteDownloadInfo by com.mangareader.smbbridge.RemoteDownloadDialogState.dialogState.collectAsState()
    val remoteDownloadBusy by com.mangareader.smbbridge.RemoteDownloadDialogState.downloadBusy.collectAsState()
    if (remoteDownloadInfo != null) {
        val info = remoteDownloadInfo!!
        AlertDialog(
            onDismissRequest = { if (!remoteDownloadBusy) com.mangareader.smbbridge.RemoteDownloadDialogState.dismissDialog() },
            title = { Text("需要全量下载") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("此格式（mobi / azw3 等）需要完整下载到本地后才能查看。")
                    if (remoteDownloadBusy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在下载…", fontSize = 13.sp, color = Color(0xFF888888))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { com.mangareader.smbbridge.RemoteDownloadDialogState.startDownload(ctx) },
                    enabled = !remoteDownloadBusy,
                ) { Text(if (remoteDownloadBusy) "下载中…" else "下载") }
            },
            dismissButton = {
                TextButton(
                    onClick = { com.mangareader.smbbridge.RemoteDownloadDialogState.dismissDialog() },
                    enabled = !remoteDownloadBusy,
                ) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(b: Book, engine: ArchiveEngine, isMultiSelect: Boolean, isSelected: Boolean, decodePx: Int, onToggleSelect: () -> Unit, onTap: () -> Unit, onLongClick: () -> Unit, onDelete: () -> Unit) {
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
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Column {
        // ★ 远程不支持流式格式（mobi/azw3）→ 第一时间显示"需下载"占位符，不触发封面加载
        val fileExt = b.fileType.lowercase()
        val isRemoteUnsupported = (b.source == "smb" || b.source == "webdav") && b.originalPath == null && fileExt in setOf("mobi", "azw3")

        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            when {
                isRemoteUnsupported -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                        Text(text = "需下载", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Text(text = fileExt.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.padding(top = 2.dp))
                    }
                }
                b.coverPath != null -> {
                    val coverRequest = remember(b.coverPath, decodePx) {
                        coil3.request.ImageRequest.Builder(ctx)
                            .data(com.mangareader.viewer.CoverData(b.coverPath))
                            .coverThumb(
                                decodePx,
                                memoryKey = "cover:${b.id}@${b.coverPath}",
                                diskKey = "${b.coverPath}",
                            )
                            .decoderFactory(com.mangareader.coil.StaticFirstFrameDecoder.Factory())
                            .build()
                    }
                    coil3.compose.AsyncImage(
                        model = coverRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
            Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = status.second.copy(alpha = 0.9f)) {
                Text(text = status.first, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
            }
            // ★ 来源角标（任务A）：远程书显示来源，已下载书显示"已下载"来源
            if (b.source == "smb" || b.source == "webdav") {
                Surface(
                    Modifier.align(Alignment.TopStart).padding(4.dp),
                    RoundedCornerShape(6.dp),
                    color = if (b.source == "smb") Color(0xCC4A7BD4) else Color(0xCC8A5CD4),
                ) {
                    Text(
                        text = if (b.source == "smb") "SMB" else "WebDAV",
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
            if (isMultiSelect) {
                Box(Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    Checkbox(checked = isSelected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                }
            }
            if (b.totalPages > 0) Text(text = "${b.readPages}/${b.totalPages}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (progress > 0f) Text(text = "${(progress * 100).toInt()}%", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(text = b.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
        }
        }
    }
}

/** 删除 SMB 单本页缓存（原始 + HDR .jpg 兄弟），返回释放字节数 */
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
