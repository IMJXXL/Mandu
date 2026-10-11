package com.mangareader.webdavbridge

import com.hippo.ehviewer.util.LocalNetworkPermissionEffect
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import com.mangareader.ui.CONTENT_MAX_WIDTH_DP
import com.mangareader.ui.isTabletWide
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.WebDavSourceEntity
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import com.mangareader.browse.BrowseCover
import com.mangareader.browse.BrowseCoverThumb
import com.mangareader.browse.BrowseCovers
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.BrowseContentMode
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.filterRemoteByContentMode
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import com.mangareader.data.Book
import com.mangareader.smbbridge.DownloadStatus
import com.mangareader.smbbridge.DownloadTask
import com.mangareader.smbbridge.SmbDownloadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WebDavBrowserScreen(serverId: Long, onBack: () -> Unit, onOpenBook: (Long, Int) -> Unit = { _, _ -> }, onOpenSettings: () -> Unit = {}) {
    LocalNetworkPermissionEffect()  // Android 17 局域网 socket 运行时授权（本地 WebDAV 同受管制）
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<WebDavSourceEntity?>(null) }
    var password by remember { mutableStateOf("") }
    var dir by rememberSaveable { mutableStateOf("") }
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
                WebDavGateway.searchDirectory(src, password, dir, q) { partial -> searchHits = partial }
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
        source = WebDavRepository.load(serverId)
        if (source == null) {
            error = "WebDAV 服务器配置不存在"
            return@LaunchedEffect
        }
        password = try { WebDavPasswordStore.get(serverId) } catch (_: Throwable) { "" }
    }

    suspend fun listDir(d: String): List<BrowseEntryRemote> = withContext(Dispatchers.IO) {
        val src = source ?: return@withContext emptyList()
        runCatching { WebDavGateway.listDirectory(src, password, d) }
            .onFailure { error = com.mangareader.smbbridge.SmbErrors.humanizeWebDav(it).take(120) }
            .getOrDefault(emptyList())
    }

    LaunchedEffect(source, dir) {
        if (source != null) {
            // 换目录即作废上一目录的在途封面提取（RAR/7z 页0提取不再卡住下一目录封面）
            com.hippo.ehviewer.library.ArchiveCoverCache.onBrowseFolderChanged("dav:$serverId:$dir")
            loading = true
            entries = listDir(dir)
            loading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            com.hippo.ehviewer.library.ArchiveCoverCache.onBrowseFolderLeft("dav:")
        }
    }

    // ★ 内容模式过滤（对齐上游 filterRemoteByContentMode / Folder 模式）：
    //   分类器对"直接含图的文件夹"会同时给出 Directory(LeafImages) 与 FolderGallery 两行，
    //   UI 不过滤就会每个文件夹显示两次。Folder 模式隐藏合成画廊行、保留目录/散图/档案，
    //   散图行可点击直达阅读器（openFolderImage）。
    val visibleEntries = remember(entries) {
        entries.filterRemoteByContentMode(
            BrowseContentMode.Folder,
            showHiddenFiles = true,
            showVirtualGalleries = true,
        )
    }

    fun joinRel(child: String): String = if (dir.isEmpty()) child else "$dir/$child"

    fun clearBookCaches(remote: String) {
        val src = source ?: return
        scope.launch(Dispatchers.IO) {
            val rel = remote.substringBefore(':')
            var freed = 0L
            runCatching {
                java.io.File(WebDavCache.thumbCachePath(src.id, rel).toString()).delete()
                val page = java.io.File(WebDavCache.cachePathForRemoteFile(src.id, rel).toString())
                freed += page.takeIf { it.isFile }?.length() ?: 0L
                page.delete()
                val streamDir = java.io.File(
                    com.hippo.ehviewer.library.ArchiveStreamPageCache.dirFor("webdav:${src.id}:$rel").toString(),
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
            val dao = WebDavDbBridge.mangaDatabase?.mangaDao() ?: return@launch
            val filePath = WebDavPaths.bookPath(src.id, remote)
            val existing = dao.getBookByPath(filePath)
            val bookId = if (existing != null) {
                existing.id
            } else {
                val book = Book(
                    title = name.substringBeforeLast('.').ifEmpty { name },
                    filePath = filePath,
                    fileType = ext,
                    coverPath = WebDavPaths.coverPath(src.id, remote),
                    source = "webdav",
                    isHidden = true,
                )
                dao.insertBookWithStableId(book)
            }
            withContext(Dispatchers.Main) {
                if (bookId > 0) onOpenBook(bookId, -1)
            }
        }
    }

    /** 确保远程文件夹书存在（浏览插入隐藏书），就绪后回调 bookId 打开。 */
    fun ensureWebDavFolderBook(remoteDir: String, displayName: String, onReady: (Long) -> Unit) {
        val src = source ?: return
        scope.launch(Dispatchers.IO) {
            val dao = WebDavDbBridge.mangaDatabase?.mangaDao() ?: return@launch
            val filePath = WebDavPaths.bookPath(src.id, remoteDir)
            val existing = dao.getBookByPath(filePath)
            if (existing != null) {
                withContext(Dispatchers.Main) { onReady(existing.id) }
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
                    title = displayName,
                    filePath = filePath,
                    fileType = "",
                    coverPath = WebDavPaths.coverPath(src.id, "$remoteDir/${images.first()}"),
                    source = "webdav",
                    isHidden = true,
                )
                val bookId = dao.insertBookWithStableId(book)
                withContext(Dispatchers.Main) { if (bookId > 0) onReady(bookId) }
            }
        }
    }

    fun addFolder(dirName: String) = ensureWebDavFolderBook(
        remoteDir = if (dir.isEmpty()) dirName else "$dir/$dirName",
        displayName = dirName,
    ) { id -> onOpenBook(id, -1) }

    /**
     * ★ 点击散图直达阅读器（对齐上游 openFolderImage）：当前目录作为文件夹书打开，
     * 定位到被点图片在目录图片序列中的页——等价于"书架打开 A 再跳第 N 页"。
     * 页序与阅读器一致：两者都来自同一 listDirectory 分类管线（自然排序的 RegularFile）。
     */
    fun openFolderImage(e: BrowseEntryRemote.RegularFile) {
        if (e.name.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) return
        val images = visibleEntries
            .filterIsInstance<BrowseEntryRemote.RegularFile>()
            .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .map { it.name }
        if (images.isEmpty()) return
        val page = images.indexOf(e.name).coerceAtLeast(0)
        ensureWebDavFolderBook(
            remoteDir = dir,
            displayName = dir.substringAfterLast('/').ifEmpty { source?.displayName ?: "图片文件夹" },
        ) { id -> onOpenBook(id, page) }
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

    // ★ E3：大文件确认状态
    var showLargeConfirm by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (最大MB, 本数)

    fun queueBatchDownload(toDownload: List<Pair<BrowseEntryRemote, Triple<String, String, String>>>) {
        val src = source ?: return
        scope.launch(Dispatchers.IO) {
            val dao = WebDavDbBridge.mangaDatabase?.mangaDao()
            for ((_, info) in toDownload) {
                val (remotePath, fileName, _) = info
                val bookPath = WebDavPaths.bookPath(src.id, remotePath)
                val existingBook = dao?.getBookByPath(bookPath)
                SmbDownloadQueue.enqueue(
                    DownloadTask(
                        id = 0,
                        title = fileName.substringBeforeLast('.').ifEmpty { fileName },
                        sourceKind = "webdav",
                        sourcePath = bookPath,
                        bookId = existingBook?.id ?: 0L,
                    )
                )
            }
        }
        isMultiSelectMode = false
        selectedRemotes = emptySet()
        Toast.makeText(ctx, "已加入下载队列（${toDownload.size} 本）", Toast.LENGTH_SHORT).show()
    }

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

        // ★ E3：大文件确认门——先探最大体积，超过 128MB 弹确认
        scope.launch(Dispatchers.IO) {
            var maxBytes = 0L
            for ((_, info) in toDownload) {
                val (remotePath, _, ext) = info
                if (ext !in WebDavScanner.SCANNABLE_FORMATS) continue
                val sz = runCatching { WebDavClient.fileSizeOrNull(src, password, remotePath) }.getOrNull() ?: continue
                if (sz > maxBytes) maxBytes = sz
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
                            if (isMultiSelectMode) "已选 ${selectedRemotes.size} 项" else (source?.displayName ?: "WebDAV 浏览"),
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
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (searchQuery.isNotEmpty()) { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) } } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (!searching) runSearch() }),
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
                Text(
                    "「${searchQuery.trim()}」· ${searchHits.size} 个结果（上限 2000）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP).weight(1f)) {
                    items(searchHits, key = { h -> h::class.simpleName + "@" + h.name + "@" + searchHitPath(h) }) { h ->
                        val hitExt = h.name.substringAfterLast('.', "").lowercase()
                        val hitPlaceholder = when {
                            h is BrowseEntryRemote.Directory -> Icons.Default.Folder
                            h is BrowseEntryRemote.FolderGallery -> Icons.Default.Folder
                            h is BrowseEntryRemote.VideoFile -> Icons.Default.Movie
                            else -> Icons.Default.Download
                        }
                        val hitSid = source?.id
                        var hitCover by remember(h, hitSid) { mutableStateOf(hitSid?.let { davHitCover(it, searchDir, h) }) }
                        LaunchedEffect(h, hitSid) {
                            if (hitCover != null || hitSid == null || source == null) return@LaunchedEffect
                            val src = source!!
                            val dirRel = when (h) {
                                is BrowseEntryRemote.Directory -> joinSearchPath(searchDir, h.relativeName)
                                is BrowseEntryRemote.FolderGallery -> {
                                    val rel = h.relativeName.replace('\\', '/')
                                    if (ZipAsDirListing.zipFileRootName(rel) != null) null
                                    else joinSearchPath(searchDir, rel)
                                }
                                else -> null
                            }
                            if (dirRel != null) {
                                val names = runCatching { WebDavGateway.listChildFileNames(src, password, dirRel) }.getOrDefault(emptyList())
                                val coverName = names.firstOrNull { isImageFileName(it) }
                                if (coverName != null) {
                                    hitCover = BrowseCover.DavImage(hitSid, joinSearchPath(dirRel, coverName))
                                }
                            }
                        }
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
                                                // zip-as-dir 画廊：进 zip 根；真实目录：进完整相对路径
                                                val rel = h.relativeName.replace('\\', '/')
                                                val target = if (ZipAsDirListing.zipFileRootName(rel) != null) {
                                                    listOf(searchDir, rel.substringBefore('/')).filter { it.isNotEmpty() }.joinToString("/")
                                                } else {
                                                    listOf(searchDir, rel).filter { it.isNotEmpty() }.joinToString("/")
                                                }
                                                exitSearch()
                                                dir = target
                                            }
                                            is BrowseEntryRemote.RegularFile -> {
                                                // 命中散图：先进入其所在目录，再由用户点图直达该页
                                                val parent = h.fileName.replace('\\', '/').substringBeforeLast('/')
                                                val target = listOf(searchDir, parent).filter { it.isNotEmpty() }.joinToString("/")
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
                            BrowseCoverThumb(
                                modifier = Modifier.size(56.dp),
                                cover = hitCover,
                                placeholder = hitPlaceholder,
                            )
                            Spacer(Modifier.width(12.dp))
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
            } else when {
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
                        columns = if (isTabletWide()) GridCells.Adaptive(96.dp) else GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItemsIndexed(visibleEntries, key = { i, e -> "$i-${e.name}" }) { _, e ->
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
                                    if (WebDavUnsupportedFormatHandler.isUnsupported(e.fileName.substringAfterLast('.', ""))) Icons.Default.Download
                                    else Icons.Default.InsertDriveFile
                                is BrowseEntryRemote.VideoFile -> Icons.Default.Movie
                                else -> when {
                                    WebDavUnsupportedFormatHandler.isUnsupported(e.name.substringAfterLast('.', "")) -> Icons.Default.Download
                                    e.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS -> Icons.Default.Download
                                    else -> Icons.Default.InsertDriveFile
                                }
                            }
                            com.mangareader.browse.BrowseItemGridCell(
                                name = e.name,
                                cover = source?.id?.let { sid -> BrowseCovers.davCover(sid, dir, e) },
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
                                                if (e is BrowseEntryRemote.RegularFile && ext in IMAGE_EXTENSIONS) {
                                                    openFolderImage(e)
                                                } else if (ext in WebDavScanner.SCANNABLE_FORMATS) addArchive(joinRel(e.name), e.name, ext)
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
                else -> LazyColumn(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP)) {
                    items(visibleEntries) { e ->
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
                                                dir = if (dir.isEmpty()) e.relativeName else "$dir/${e.relativeName}"
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
                                    cover = source?.id?.let { sid -> BrowseCovers.davCover(sid, dir, e) },
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
                                    cover = source?.id?.let { sid -> BrowseCovers.davCover(sid, dir, e) },
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
                                cover = source?.id?.let { sid -> BrowseCovers.davCover(sid, dir, e) },
                                placeholder = Icons.Default.Download,
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
                                placeholder = Icons.Default.Movie,
                            )
                            else -> {
                                val ext = e.name.substringAfterLast('.', "").lowercase()
                                FileRow(
                                    e.name, joinRel(e.name),
                                    isSelected, isMultiSelectMode,
                                    {
                                        if (isMultiSelectMode && remotePath != null) {
                                            selectedRemotes = if (isSelected) selectedRemotes - remotePath else selectedRemotes + remotePath
                                        } else if (e is BrowseEntryRemote.RegularFile && ext in IMAGE_EXTENSIONS) {
                                            openFolderImage(e)
                                        } else if (ext in WebDavScanner.SCANNABLE_FORMATS) {
                                            addArchive(joinRel(e.name), e.name, ext)
                                        }
                                    },
                                    {
                                        longPressedName = e.name
                                        longPressedRemote = remotePath
                                        showItemMenu = true
                                    },
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    cover = source?.id?.let { sid -> BrowseCovers.davCover(sid, dir, e) },
                                    placeholder = if (ext in IMAGE_EXTENSIONS) Icons.Default.Download else Icons.Default.InsertDriveFile,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ★ "需全量下载"对话框宿主（与书架/SMB 浏览一致，见 RemoteDownloadDialogHost 注释）
    com.mangareader.smbbridge.RemoteDownloadDialogHost()

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
                            val dao = WebDavDbBridge.mangaDatabase?.mangaDao()
                            val bookPath = WebDavPaths.bookPath(src.id, r)
                            val existingBook = dao?.getBookByPath(bookPath)
                            SmbDownloadQueue.enqueue(
                                DownloadTask(
                                    id = 0,
                                    title = longPressedName.substringBeforeLast('.').ifEmpty { longPressedName },
                                    sourceKind = "webdav",
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

private fun joinSearchPath(parent: String, child: String): String =
    if (parent.isEmpty()) child.trim('/') else "${parent.trim('/')}/${child.trim('/')}"

/** 深搜命中行的封面（命中路径相对 [searchDir]）：目录取封面名、档案取归档首页、图片取自身。 */
private fun davHitCover(sourceId: Long, searchDir: String, h: BrowseEntryRemote): BrowseCover? {
    fun join(parent: String, child: String): String = joinSearchPath(parent, child)
    return when (h) {
        is BrowseEntryRemote.Directory ->
            h.coverFileName?.takeIf { isImageFileName(it) }
                ?.let { BrowseCover.DavImage(sourceId, join(join(searchDir, h.relativeName), it)) }
        is BrowseEntryRemote.FolderGallery ->
            h.coverFileName?.takeIf { isImageFileName(it) }?.let { cover ->
                val rel = h.relativeName.replace('\\', '/')
                if (ZipAsDirListing.zipFileRootName(rel) != null) {
                    BrowseCover.DavArchive(sourceId, join(searchDir, rel.substringBefore('/')))
                } else {
                    BrowseCover.DavImage(sourceId, join(join(searchDir, rel.substringBeforeLast('/')), rel.substringAfterLast('/')))
                }
            }
        is BrowseEntryRemote.ArchiveGallery ->
            BrowseCover.DavArchive(sourceId, join(searchDir, join(h.parentRelativeName, h.fileName)))
        is BrowseEntryRemote.RegularFile ->
            if (isImageFileName(h.fileName)) BrowseCover.DavImage(sourceId, join(searchDir, h.fileName)) else null
        else -> null
    }
}

/** 命中行的稳定 key（同目录同名不同类型不会撞 key）。 */
private fun searchHitPath(h: BrowseEntryRemote): String = when (h) {
    is BrowseEntryRemote.Directory -> h.relativeName
    is BrowseEntryRemote.ArchiveGallery -> "${h.parentRelativeName}/${h.fileName}"
    is BrowseEntryRemote.FolderGallery -> h.relativeName
    is BrowseEntryRemote.VideoFile -> h.fileName
    is BrowseEntryRemote.RegularFile -> h.fileName
    else -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    name: String,
    remotePath: String,
    isSelected: Boolean,
    showCheckbox: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
    placeholder: ImageVector = Icons.Default.InsertDriveFile,
) {
    val ext = remotePath.substringBefore(':').substringAfterLast('.', "").lowercase()
    Row(
        modifier.fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick),
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
            BrowseCoverThumb(modifier = Modifier.size(56.dp), cover = cover, placeholder = placeholder)
            Spacer(Modifier.width(12.dp))
        } else {
            val unsupported = WebDavUnsupportedFormatHandler.isUnsupported(ext)
            Icon(
                if (unsupported) Icons.Default.Download else placeholder,
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
