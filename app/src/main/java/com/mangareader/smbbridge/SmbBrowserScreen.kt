package com.mangareader.smbbridge

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow

import com.ehviewer.core.database.model.SmbSourceEntity
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import com.mangareader.browse.BrowseCover
import com.mangareader.browse.BrowseCoverThumb
import com.mangareader.browse.BrowseCovers
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.mangareader.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 文件夹浏览器（任务 B 补做，2026-09-06）：逐级浏览服务器目录，选择性入架。
 * 与"扫描即全量入架"并存——浏览模式适合只想加某几本/某个文件夹的场景。
 * 入架逻辑与 SmbScanner 完全同构（同一 Book 结构/路径格式/封面路径）。
 *
 * ★ 2026-09-08：批量下载功能——多选归档文件 → 下载到用户指定路径 → rehome 为本地书。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SmbBrowserScreen(serverId: Long, onBack: () -> Unit, onOpenBook: (Long) -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<SmbSourceEntity?>(null) }
    var password by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // B2 列表/网格模式（全局记忆，对齐上游 BrowseModePersist）
    val listMode by Settings.browseListMode.valueFlow().collectAsState(initial = Settings.browseListMode.value)

    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedRemotes by remember { mutableStateOf<Set<String>>(emptySet()) }

    var showNoDirDialog by remember { mutableStateOf(false) }
    var showItemMenu by remember { mutableStateOf(false) }
    var longPressedName by remember { mutableStateOf("") }
    var longPressedRemote by remember { mutableStateOf<String?>(null) }

    // ★ E1 深搜：提交式搜索，命中边搜边出；离开搜索态即清结果
    var searchMode by remember { mutableStateOf(false) }
    var searchSubmitted by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchDir by remember { mutableStateOf("") }

    fun exitSearch() {
        searchMode = false
        searchSubmitted = false
        searchHits = emptyList()
        searchQuery = ""
    }

    fun runSearch() {
        val src = source ?: return
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        scope.launch {
            searching = true
            searchHits = emptyList()
            searchDir = dir
            searchSubmitted = true
            try {
                SmbGateway.searchDirectory(src, password, dir, q) { partial -> searchHits = partial }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                searching = false
            }
        }
    }

    BackHandler {
        if (isMultiSelectMode) {
            isMultiSelectMode = false
            selectedRemotes = emptySet()
        } else if (searchMode) {
            exitSearch()
        } else if (dir.isNotEmpty()) {
            dir = dir.substringBeforeLast('/', "")
        } else {
            onBack()
        }
    }

    val pickDownloadDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            com.hippo.ehviewer.Settings.smbDownloadDirTreeUri.value = uri.toString()
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val path = if (docId.startsWith("primary:")) {
                android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + docId.substringAfter("primary:")
            } else {
                uri.toString()
            }
            com.hippo.ehviewer.Settings.smbDownloadDirPath.value = path
        }
    }

    LaunchedEffect(serverId) {
        password = try { SmbPasswordStore.get(serverId) } catch (_: Throwable) { "" }
        source = SmbRepository.load(serverId)
        if (source == null) {
            error = "服务器配置不存在"
            return@LaunchedEffect
        }
    }

    suspend fun listDir(d: String): List<BrowseEntryRemote> = withContext(Dispatchers.IO) {
        val src = source ?: return@withContext emptyList()
        android.util.Log.d("SmbBrowseTap", "listDir \"$d\"")
        runCatching { SmbGateway.listDirectory(src, password, d) }
            .onFailure { error = SmbErrors.humanizeSmb(it).take(120) }
            .getOrDefault(emptyList())
    }

    LaunchedEffect(source, dir) {
        if (source != null) {
            loading = true
            entries = listDir(dir)
            loading = false
        }
    }

    fun joinRel(child: String): String = if (dir.isEmpty()) child else "$dir/$child"

    fun clearBookCaches(remote: String) {
        val src = source ?: return
        scope.launch(Dispatchers.IO) {
            val rel = remote.substringBefore(':')
            var freed = 0L
            runCatching {
                java.io.File(SmbCache.thumbCachePath(src.id, rel).toString()).delete()
                val page = java.io.File(SmbCache.cachePathForRemoteFile(src.id, rel).toString())
                freed += page.takeIf { it.isFile }?.length() ?: 0L
                page.delete()
                val streamDir = java.io.File(
                    com.hippo.ehviewer.library.ArchiveStreamPageCache.dirFor("smb:${src.id}:$rel").toString(),
                )
                streamDir.walkBottomUp().forEach { f -> if (f.isFile) freed += f.length() }
                streamDir.deleteRecursively()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "已清理此书缓存 ${freed / 1024 / 1024} MB", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun addArchive(remote: String, name: String, ext: String) {
        val src = source ?: return
        scope.launch(Dispatchers.IO) {
            val dao = SmbDbBridge.mangaDatabase?.mangaDao() ?: return@launch
            val filePath = SmbPaths.bookPath(src.id, src.share, remote)
            val existing = dao.getBookByPath(filePath)
            val bookId = if (existing != null) {
                existing.id
            } else {
                val book = Book(
                    title = name.substringBeforeLast('.').ifEmpty { name },
                    filePath = filePath,
                    fileType = ext,
                    coverPath = SmbPaths.coverPath(src.id, src.share, remote),
                    source = "smb",
                    isHidden = true,
                )
                dao.insertBookWithStableId(book)
            }
            withContext(Dispatchers.Main) {
                if (bookId > 0) onOpenBook(bookId)
            }
        }
    }

    fun addFolder(dirName: String) {
        val src = source ?: return
        val remoteDir = if (dir.isEmpty()) dirName else "$dir/$dirName"
        scope.launch(Dispatchers.IO) {
            val dao = SmbDbBridge.mangaDatabase?.mangaDao() ?: return@launch
            val filePath = SmbPaths.bookPath(src.id, src.share, remoteDir)
            val existing = dao.getBookByPath(filePath)
            if (existing != null) {
                withContext(Dispatchers.Main) { onOpenBook(existing.id) }
                return@launch
            }
            val images = listDir(remoteDir)
                .filter { it !is BrowseEntryRemote.Directory && it !is BrowseEntryRemote.FolderGallery }
                .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .map { it.name }
            if (images.isEmpty()) {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "该文件夹不含图片", Toast.LENGTH_SHORT).show() }
            } else {
                val book = Book(
                    title = dirName,
                    filePath = filePath,
                    fileType = "",
                    coverPath = SmbPaths.coverPath(src.id, src.share, "$remoteDir/${images.first()}"),
                    source = "smb",
                    isHidden = true,
                )
                val bookId = dao.insertBookWithStableId(book)
                withContext(Dispatchers.Main) { if (bookId > 0) onOpenBook(bookId) }
            }
        }
    }

    fun extractDownloadable(e: BrowseEntryRemote): Triple<String, String, String>? = when (e) {
        is BrowseEntryRemote.ArchiveGallery -> Triple(joinRel(e.fileName), e.name, e.fileName.substringAfterLast('.', "").lowercase())
        is BrowseEntryRemote.RegularFile -> {
            val ext = e.name.substringAfterLast('.', "").lowercase()
            Triple(joinRel(e.name), e.name, ext)
        }
        is BrowseEntryRemote.Directory -> Triple(joinRel(e.relativeName), e.name, "")
        is BrowseEntryRemote.FolderGallery -> Triple(joinRel(e.relativeName), e.name, "")
        is BrowseEntryRemote.VideoFile -> Triple(joinRel(e.name), e.name, e.name.substringAfterLast('.', "").lowercase())
        else -> null
    }

    val allDownloadable = entries.mapNotNull { e -> extractDownloadable(e)?.let { e to it } }
    val allRemotes = allDownloadable.map { it.second.first }.toSet()
    val allSelected = selectedRemotes.isNotEmpty() && selectedRemotes.containsAll(allRemotes)

    fun queueBatchDownload(toDownload: List<Pair<BrowseEntryRemote, Triple<String, String, String>>>) {
        val src = source ?: return
        scope.launch(Dispatchers.IO) {
            val dao = SmbDbBridge.mangaDatabase?.mangaDao()
            for ((_, info) in toDownload) {
                val (remotePath, fileName, _) = info
                val bookPath = SmbPaths.bookPath(src.id, src.share, remotePath)
                val existingBook = dao?.getBookByPath(bookPath)
                SmbDownloadQueue.enqueue(
                    DownloadTask(
                        id = 0,
                        title = fileName.substringBeforeLast('.').ifEmpty { fileName },
                        sourceKind = "smb",
                        sourcePath = bookPath,
                        bookId = existingBook?.id ?: 0L,
                    ),
                )
            }
        }
        isMultiSelectMode = false
        selectedRemotes = emptySet()
        Toast.makeText(ctx, "已加入下载队列（${toDownload.size} 本）", Toast.LENGTH_SHORT).show()
    }

    // ★ E3：大文件确认门（对齐上游 >100MB 确认 + 下载 snackbar）
    var showLargeConfirm by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (最大MB, 本数)

    fun startBatchDownload() {
        val treeUriStr = Settings.smbDownloadDirTreeUri.value
        if (treeUriStr.isNullOrEmpty()) {
            showNoDirDialog = true
            return
        }
        val treeUri = Uri.parse(treeUriStr)
        val canWrite = ctx.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
        if (!canWrite) {
            Toast.makeText(ctx, "下载目录不可写，请重新设置", Toast.LENGTH_LONG).show()
            return
        }
        val src = source ?: return
        val toDownload = allDownloadable.filter { it.second.first in selectedRemotes }
        if (toDownload.isEmpty()) return

        // 先探最大体积：超过 128MB 的归档弹确认，防手滑拉爆流量
        scope.launch(Dispatchers.IO) {
            var maxBytes = 0L
            for ((_, info) in toDownload) {
                val (remotePath, _, ext) = info
                if (ext !in SmbScanner.SCANNABLE_FORMATS) continue
                val s = runCatching { SmbGateway.fileSizeOrNull(src, password, remotePath) }.getOrNull() ?: continue
                if (s > maxBytes) maxBytes = s
            }
            withContext(Dispatchers.Main) {
                if (maxBytes > 128L * 1024L * 1024L) {
                    showLargeConfirm = ((maxBytes + 1024L * 1024L - 1) / (1024L * 1024L)).toInt() to toDownload.size
                } else {
                    queueBatchDownload(toDownload)
                }
            }
        }
    }

    if (showLargeConfirm != null) {
        val (maxMb, count) = showLargeConfirm!!
        AlertDialog(
            onDismissRequest = { showLargeConfirm = null },
            title = { Text("大文件下载确认") },
            text = { Text("共 $count 本，最大约 $maxMb MB（超过 128 MB）。继续下载到本地？") },
            confirmButton = {
                TextButton(onClick = {
                    val pending = allDownloadable.filter { it.second.first in selectedRemotes }
                    showLargeConfirm = null
                    queueBatchDownload(pending)
                }) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { showLargeConfirm = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (isMultiSelectMode) "已选 ${selectedRemotes.size} 项" else (source?.displayName ?: "SMB 浏览"),
                            fontSize = 16.sp,
                        )
                        if (!isMultiSelectMode) {
                            Text("/$dir".ifEmpty { "/" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isMultiSelectMode) {
                            isMultiSelectMode = false
                            selectedRemotes = emptySet()
                        } else if (dir.isNotEmpty()) {
                            dir = dir.substringBeforeLast('/', "")
                        } else {
                            onBack()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (!isMultiSelectMode) {
                        IconButton(onClick = { Settings.browseListMode.value = !listMode }) {
                            Icon(
                                if (listMode) Icons.Default.GridView else Icons.Default.ViewList,
                                if (listMode) "切换网格" else "切换列表",
                            )
                        }
                        IconButton(onClick = {
                            if (searchMode) exitSearch() else searchMode = true
                        }) { Icon(Icons.Default.Search, "搜索") }
                    }
                    if (isMultiSelectMode) {
                        IconButton(onClick = {
                            isMultiSelectMode = false
                            selectedRemotes = emptySet()
                        }) { Icon(Icons.Default.Close, "退出多选") }
                    } else {
                        IconButton(onClick = { isMultiSelectMode = true }) {
                            Icon(Icons.Default.SelectAll, "多选")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isMultiSelectMode) {
                BottomAppBar {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            selectedRemotes = if (allSelected) emptySet() else allRemotes
                        }) {
                            Icon(Icons.Default.SelectAll, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (allSelected) "取消全选" else "全选")
                        }
                        Button(
                            onClick = { startBatchDownload() },
                            enabled = selectedRemotes.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("下载 ${selectedRemotes.size}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            if (searchMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索当前目录及子目录", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { runSearch() }, enabled = searchQuery.isNotBlank() && !searching) {
                        Text("搜索")
                    }
                }
            }
            if (searchMode && searchSubmitted) {
                if (searching && searchHits.isEmpty()) {
                    Text(
                        "正在搜索…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize().weight(1f)) {
                    item {
                        Text(
                            "「${searchQuery.trim()}」· ${searchHits.size} 个结果（上限 500）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(searchHits) { h ->
                        val hitExt = h.name.substringAfterLast('.', "").lowercase()
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        when (h) {
                                            is BrowseEntryRemote.Directory -> {
                                                val target = listOf(searchDir, h.relativeName).filter { it.isNotEmpty() }.joinToString("/")
                                                exitSearch()
                                                dir = target
                                            }
                                            is BrowseEntryRemote.ArchiveGallery -> {
                                                val remote = listOf(searchDir, h.parentRelativeName, h.fileName).filter { it.isNotEmpty() }.joinToString("/")
                                                exitSearch()
                                                addArchive(remote, h.name, hitExt)
                                            }
                                            is BrowseEntryRemote.FolderGallery -> {
                                                val zipSeg = h.relativeName.substringBefore('/')
                                                val target = listOf(searchDir, zipSeg).filter { it.isNotEmpty() }.joinToString("/")
                                                exitSearch()
                                                dir = target
                                            }
                                            else -> Unit
                                        }
                                    },
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                when (h) {
                                    is BrowseEntryRemote.Directory -> Icons.Default.Folder
                                    is BrowseEntryRemote.FolderGallery -> Icons.Default.Folder
                                    else -> Icons.Default.InsertDriveFile
                                },
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(h.name, fontSize = 14.sp, maxLines = 1)
                                val rel = when (h) {
                                    is BrowseEntryRemote.Directory -> h.relativeName
                                    is BrowseEntryRemote.ArchiveGallery -> h.parentRelativeName
                                    is BrowseEntryRemote.FolderGallery -> h.relativeName
                                    else -> ""
                                }
                                if (rel.isNotEmpty()) {
                                    Text(rel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            } else {
            Box(Modifier.fillMaxSize().weight(1f)) {
            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { error = null; scope.launch { loading = true; entries = listDir(dir); loading = false } }) { Text("重试") }
                    }
                }
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                !listMode -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItemsIndexed(entries, key = { i, e -> "$i-${e.name}" }) { _, e ->
                            val dl = extractDownloadable(e)
                            val remotePath = dl?.first
                            val isSelected = remotePath != null && remotePath in selectedRemotes
                            val subtitle = when (e) {
                                is BrowseEntryRemote.Directory -> "文件夹"
                                is BrowseEntryRemote.FolderGallery -> "图片文件夹 · ${e.pageCount} 张"
                                is BrowseEntryRemote.ArchiveGallery -> e.fileName.substringAfterLast('.', "").uppercase()
                                is BrowseEntryRemote.VideoFile -> null
                                else -> e.name.substringAfterLast('.', "").uppercase()
                            }
                            val gridPlaceholder = when (e) {
                                is BrowseEntryRemote.Directory, is BrowseEntryRemote.FolderGallery -> Icons.Default.Folder
                                is BrowseEntryRemote.ArchiveGallery ->
                                    if (SmbUnsupportedFormatHandler.isUnsupported(e.fileName.substringAfterLast('.', ""))) Icons.Default.Download
                                    else Icons.Default.InsertDriveFile
                                else -> Icons.Default.InsertDriveFile
                            }
                            com.mangareader.browse.BrowseItemGridCell(
                                name = e.name,
                                cover = source?.id?.let { sid -> BrowseCovers.smbCover(sid, dir, e) },
                                subtitle = subtitle,
                                placeholder = gridPlaceholder,
                                isSelected = isSelected,
                                showCheckbox = isMultiSelectMode && remotePath != null,
                                onClick = {
                                    if (isMultiSelectMode && remotePath != null) {
                                        selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                    } else {
                                        when (e) {
                                            is BrowseEntryRemote.Directory -> {
                                                val target = if (dir.isEmpty()) e.relativeName else "$dir/${e.relativeName}"
                                                dir = target
                                            }
                                            is BrowseEntryRemote.FolderGallery -> addFolder(e.relativeName)
                                            is BrowseEntryRemote.ArchiveGallery -> addArchive(joinRel(e.fileName), e.name, e.fileName.substringAfterLast('.', "").lowercase())
                                            else -> {
                                                val ext = e.name.substringAfterLast('.', "").lowercase()
                                                if (ext in SmbScanner.SCANNABLE_FORMATS) addArchive(joinRel(e.name), e.name, ext)
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    longPressedName = e.name
                                    longPressedRemote = remotePath
                                    showItemMenu = true
                                },
                            )
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries) { e ->
                        val dl = extractDownloadable(e)
                        val remotePath = dl?.first
                        val isSelected = remotePath != null && remotePath in selectedRemotes
                        when (e) {
                            is BrowseEntryRemote.Directory -> Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isMultiSelectMode && remotePath != null) {
                                                selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                            } else {
                                                val target = if (dir.isEmpty()) e.relativeName else "$dir/${e.relativeName}"
                                                android.util.Log.d("SmbBrowseTap", "enter dir=\"$dir\" -> \"$target\" name=${e.name} rel=${e.relativeName} class=${e::class.simpleName}")
                                                dir = target
                                            }
                                        },
                                        onLongClick = {
                                            longPressedName = e.name
                                            longPressedRemote = remotePath
                                            showItemMenu = true
                                        },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isMultiSelectMode && remotePath != null) {
                                    Checkbox(checked = isSelected, onCheckedChange = {
                                        selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                    }, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                BrowseCoverThumb(
                                    modifier = Modifier.size(56.dp),
                                    cover = source?.id?.let { sid -> BrowseCovers.smbCover(sid, dir, e) },
                                    placeholder = Icons.Default.Folder,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(e.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            }
                            is BrowseEntryRemote.FolderGallery -> Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isMultiSelectMode && remotePath != null) {
                                                selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                            } else {
                                                addFolder(e.relativeName)
                                            }
                                        },
                                        onLongClick = {
                                            longPressedName = e.name
                                            longPressedRemote = remotePath
                                            showItemMenu = true
                                        },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isMultiSelectMode && remotePath != null) {
                                    Checkbox(checked = isSelected, onCheckedChange = {
                                        selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                    }, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                BrowseCoverThumb(
                                    modifier = Modifier.size(56.dp),
                                    cover = source?.id?.let { sid -> BrowseCovers.smbCover(sid, dir, e) },
                                    placeholder = Icons.Default.Folder,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, fontSize = 15.sp)
                                    Text("图片文件夹 · ${e.pageCount} 张", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            is BrowseEntryRemote.ArchiveGallery -> FileRow(
                                e.name, joinRel(e.fileName),
                                isSelected, isMultiSelectMode,
                                {
                                    if (isMultiSelectMode && remotePath != null) {
                                        selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                    } else {
                                        addArchive(joinRel(e.fileName), e.name, e.fileName.substringAfterLast('.', "").lowercase())
                                    }
                                },
                                {
                                    longPressedName = e.name
                                    longPressedRemote = remotePath
                                    showItemMenu = true
                                },
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                cover = source?.id?.let { sid -> BrowseCovers.smbCover(sid, dir, e) },
                            )
                            is BrowseEntryRemote.VideoFile -> FileRow(
                                e.name, joinRel(e.name),
                                isSelected, isMultiSelectMode,
                                {
                                    if (isMultiSelectMode && remotePath != null) {
                                        selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                    }
                                },
                                {
                                    longPressedName = e.name
                                    longPressedRemote = remotePath
                                    showItemMenu = true
                                },
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            else -> {
                                val ext = e.name.substringAfterLast('.', "").lowercase()
                                FileRow(
                                    e.name, joinRel(e.name),
                                    isSelected, isMultiSelectMode,
                                    {
                                        if (isMultiSelectMode && remotePath != null) {
                                            selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                        } else if (ext in SmbScanner.SCANNABLE_FORMATS) {
                                            addArchive(joinRel(e.name), e.name, ext)
                                        }
                                    },
                                    {
                                        longPressedName = e.name
                                        longPressedRemote = remotePath
                                        showItemMenu = true
                                    },
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    cover = source?.id?.let { sid -> BrowseCovers.smbCover(sid, dir, e) },
                                )
                            }
                        }
                    }
                }
            }
            }  // ★ E1 搜索态 else 分支结束

        }
        }
        }

    if (showNoDirDialog) {
        AlertDialog(
            onDismissRequest = { showNoDirDialog = false },
            title = { Text("未设置下载路径") },
            text = { Text("请先设置下载保存路径") },
            confirmButton = {
                Row {
                    TextButton(onClick = { showNoDirDialog = false; pickDownloadDirLauncher.launch(null) }) { Text("去设置") }
                    TextButton(onClick = { showNoDirDialog = false }) { Text("知道了") }
                }
            },
        )
    }

    if (showItemMenu) {
        AlertDialog(
            onDismissRequest = { showItemMenu = false },
            title = { Text(longPressedName) },
            text = { Text("选择操作") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showItemMenu = false
                        val r = longPressedRemote ?: return@TextButton
                        val treeUriStr = Settings.smbDownloadDirTreeUri.value
                        if (treeUriStr.isNullOrEmpty()) { showNoDirDialog = true; return@TextButton }
                        val src = source ?: return@TextButton
                        scope.launch(Dispatchers.IO) {
                            val dao = SmbDbBridge.mangaDatabase?.mangaDao()
                            val bookPath = SmbPaths.bookPath(src.id, src.share, r)
                            val existingBook = dao?.getBookByPath(bookPath)
                            SmbDownloadQueue.enqueue(
                                DownloadTask(
                                    id = 0,
                                    title = longPressedName.substringBeforeLast('.').ifEmpty { longPressedName },
                                    sourceKind = "smb",
                                    sourcePath = bookPath,
                                    bookId = existingBook?.id ?: 0L,
                                ),
                            )
                            withContext(Dispatchers.Main) { Toast.makeText(ctx, "已加入下载队列", Toast.LENGTH_SHORT).show() }
                        }
                    }) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("下载")
                    }
                    TextButton(onClick = {
                        showItemMenu = false
                        longPressedRemote?.let { clearBookCaches(it) }
                    }) { Text("清除缓存") }
                }
            },
            dismissButton = { TextButton(onClick = { showItemMenu = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    name: String,
    remotePath: String,
    isSelected: Boolean,
    showCheckbox: Boolean,
    onOpen: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
) {
    val ext = remotePath.substringBefore(':').substringAfterLast('.', "").lowercase()
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onOpen() },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (cover != null) {
            BrowseCoverThumb(modifier = Modifier.size(56.dp), cover = cover, placeholder = Icons.Default.InsertDriveFile)
            Spacer(Modifier.width(12.dp))
        } else {
            // 不支持格式（mobi/azw3 等）→ 书架同款"需下载"占位；其余普通文件图标
            val unsupported = SmbUnsupportedFormatHandler.isUnsupported(ext)
            Icon(
                if (unsupported) Icons.Default.Download else Icons.Default.InsertDriveFile,
                null,
                tint = if (unsupported) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp)
            Text(ext.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DownloadPathBar() {
    val ctx = LocalContext.current
    val downloadPath by Settings.smbDownloadDirPath.collectAsState()
    val pickDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            Settings.smbDownloadDirTreeUri.value = uri.toString()
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val path = if (docId.startsWith("primary:")) {
                android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + docId.substringAfter("primary:")
            } else {
                uri.toString()
            }
            Settings.smbDownloadDirPath.value = path
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { pickDirLauncher.launch(null) }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("下载路径", fontSize = 13.sp)
                Text(
                    text = downloadPath ?: "未设置（点击选择）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (downloadPath != null) {
                TextButton(
                    onClick = {
                        Settings.smbDownloadDirTreeUri.value = null
                        Settings.smbDownloadDirPath.value = null
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("清除", fontSize = 12.sp) }
            }
            Text("选择 ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
