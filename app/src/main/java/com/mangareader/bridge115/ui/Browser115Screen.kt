package com.mangareader.bridge115.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.FileEntry115
import com.mangareader.bridge115.browser.DirService115
import kotlinx.coroutines.launch

private val ARCHIVE_EXTS = setOf("cbz", "cbr", "cb7", "zip", "rar", "7z", "pdf", "epub", "mobi", "tar", "cbt")

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Browser115Screen(
    dirService: DirService115,
    mode: String,
    onOpenBook: (fid: String, pickCode: String, name: String) -> Unit,
    onAddToShelf: (fid: String, pickCode: String, name: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentCid = remember { mutableStateOf("0") }
    val currentPath = remember { mutableStateListOf<String>() }
    val cidStack = remember { mutableStateListOf("0") }
    val items = remember { mutableStateListOf<FileEntry115>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }

    val isArchive: (FileEntry115) -> Boolean = { entry ->
        !entry.isDir && ARCHIVE_EXTS.contains(entry.name.substringAfterLast('.', "").lowercase())
    }

    val modeLabel = if (mode == Constants115.MODE_OFFICIAL) "官方" else "非官方"

    LaunchedEffect(currentCid.value) {
        loading = true
        error = null
        scope.launch {
            try {
                val result = dirService.listDir(currentCid.value, mode)
                items.clear()
                items.addAll(result.items)
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentPath.isEmpty()) "115网盘($modeLabel)" else currentPath.last()) },
                navigationIcon = {
                    if (currentPath.isNotEmpty()) {
                        IconButton(onClick = {
                            currentPath.removeAt(currentPath.size - 1)
                            cidStack.removeAt(cidStack.size - 1)
                            currentCid.value = cidStack.last()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.Button(onClick = {
                        loading = true
                        scope.launch {
                            try {
                                val result = dirService.listDir(currentCid.value, mode, forceRefresh = true)
                                items.clear()
                                items.addAll(result.items)
                                error = null
                            } catch (e: Exception) {
                                error = e.message ?: "加载失败"
                            } finally {
                                loading = false
                            }
                        }
                    }) { Text("重试") }
                }
            } else if (items.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("此文件夹为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(items) { entry ->
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = {
                                        if (entry.isDir) {
                                            currentPath.add(entry.name)
                                            cidStack.add(entry.cid)
                                            currentCid.value = entry.cid
                                        } else if (isArchive(entry)) {
                                            onOpenBook(entry.fid, entry.pickCode, entry.name)
                                        }
                                    },
                                    onLongClick = {
                                        if (entry.isDir) {
                                            onOpenBook(entry.fid, entry.cid, entry.name)
                                        } else if (isArchive(entry)) {
                                            showMenu = true
                                        }
                                    }
                                ).padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (entry.isDir) Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.size(12.dp))
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                if (isArchive(entry)) {
                                    Text("可阅读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("加入书架") },
                                    onClick = { showMenu = false; onAddToShelf(entry.fid, entry.pickCode, entry.name) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet115(onDismiss = { showSettings = false }, onLogout = { showSettings = false; onBack() })
    }
}