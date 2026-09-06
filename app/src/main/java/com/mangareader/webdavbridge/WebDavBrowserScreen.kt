package com.mangareader.webdavbridge

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import com.mangareader.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 文件夹浏览器（任务 B 补做，2026-09-06）：逐级浏览服务器目录，选择性入架。
 * 与"扫描即全量入架"并存——浏览模式适合只想加某几本/某个文件夹的场景。
 * 入架逻辑与 WebDavScanner 完全同构（同一 Book 结构/路径格式/封面路径）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WebDavBrowserScreen(serverId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<WebDavSourceEntity?>(null) }
    var password by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
            .onFailure { error = it.message?.take(120) ?: "列目录失败" }
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

    /** ★ 单本缓存清理（任务18扩展） */
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

    /** @param remote 完整前缀空间路径（调用方负责拼接 dir） */
    fun addArchive(remote: String, name: String, ext: String) {
        val src = source ?: return
        scope.launch(Dispatchers.IO) {
            val dao = WebDavDbBridge.mangaDatabase?.mangaDao() ?: return@launch
            val book = Book(
                title = name.substringBeforeLast('.').ifEmpty { name },
                filePath = WebDavPaths.bookPath(src.id, remote),
                fileType = ext,
                coverPath = WebDavPaths.coverPath(src.id, remote),
                source = "webdav",
            )
            val row = dao.insertBook(book)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, if (row != -1L) "已加入书架" else "书架中已存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun addFolder(dirName: String) {
        val src = source ?: return
        val remoteDir = if (dir.isEmpty()) dirName else "$dir/$dirName"
        scope.launch(Dispatchers.IO) {
            val dao = WebDavDbBridge.mangaDatabase?.mangaDao() ?: return@launch
            val images = listDir(remoteDir)
                .filter { it !is BrowseEntryRemote.Directory && it !is BrowseEntryRemote.FolderGallery }
                .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .map { it.name }
            withContext(Dispatchers.Main) {
                if (images.isEmpty()) {
                    Toast.makeText(ctx, "该文件夹不含图片，请进入后选择具体文件", Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch(Dispatchers.IO) {
                        val book = Book(
                            title = dirName,
                            filePath = WebDavPaths.bookPath(src.id, remoteDir),
                            fileType = "",
                            coverPath = WebDavPaths.coverPath(src.id, "$remoteDir/${images.first()}"),
                            source = "webdav",
                        )
                        val row = dao.insertBook(book)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, if (row != -1L) "已加入书架（${images.size} 张图）" else "书架中已存在", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(source?.displayName ?: "WebDAV 浏览", fontSize = 16.sp)
                        Text("/$dir".ifEmpty { "/" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (dir.isNotEmpty()) dir = dir.substringBeforeLast('/', "") else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
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
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.name }) { e ->
                        when (e) {
                            is BrowseEntryRemote.Directory -> Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(onClick = { dir = if (dir.isEmpty()) e.relativeName else "$dir/${e.relativeName}" })
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Color(0xFF8FB8E8))
                                Spacer(Modifier.width(12.dp))
                                Text(e.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            }
                            is BrowseEntryRemote.FolderGallery -> Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Color(0xFF9DE8B8))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, fontSize = 15.sp)
                                    Text("图片文件夹 · ${e.pageCount} 张", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { addFolder(e.relativeName) }) { Text("入架") }
                            }
                            is BrowseEntryRemote.ArchiveGallery -> FileRow(e.name, joinRel(e.fileName), ::addArchive, ::clearBookCaches, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            is BrowseEntryRemote.VideoFile -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.InsertDriveFile, null, tint = Color.Gray)
                                Spacer(Modifier.width(12.dp))
                                Text(e.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            }
                            else -> {
                                val ext = e.name.substringAfterLast('.', "").lowercase()
                                if (ext in WebDavScanner.SCANNABLE_FORMATS) {
                                    FileRow(e.name, joinRel(e.name), ::addArchive, ::clearBookCaches, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                } else {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.InsertDriveFile, null, tint = Color.Gray)
                                        Spacer(Modifier.width(12.dp))
                                        Text(e.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    name: String,
    remotePath: String,
    addArchive: (String, String, String) -> Unit,
    clearBookCaches: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = remotePath.substringBefore(':').substringAfterLast('.', "").lowercase()
    Row(
        modifier.fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { clearBookCaches(remotePath) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp)
            Text(ext.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { addArchive(remotePath, name, ext) }) { Text("入架") }
    }
}
