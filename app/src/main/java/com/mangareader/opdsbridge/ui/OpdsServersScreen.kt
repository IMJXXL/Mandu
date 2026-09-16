package com.mangareader.opdsbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.opdsbridge.OpdsBridges
import com.mangareader.opdsbridge.cache.OpdsCache
import com.mangareader.opdsbridge.cover.OpdsEntryLinkCache
import com.mangareader.opdsbridge.data.OpdsConnection
import com.mangareader.opdsbridge.data.OpdsConnectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsServersScreen(onBack: () -> Unit, onOpenBook: (Long) -> Unit = {}) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { OpdsConnectionStore(ctx) }
    var connections by remember { mutableStateOf(store.connections()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OpdsConnection?>(null) }
    var deleting by remember { mutableStateOf<OpdsConnection?>(null) }
    var browsingId by remember { mutableStateOf<String?>(null) }

    browsingId?.let { sid ->
        OpdsBrowserScreen(serverId = sid, onBack = { browsingId = null }, onOpenBook = onOpenBook)
        return
    }

    if (showAdd || editing != null) {
        OpdsAddServerDialog(
            initial = editing,
            onDismiss = { showAdd = false; editing = null },
            onSaved = {
                showAdd = false; editing = null
                connections = store.connections()
            },
        )
        return
    }

    deleting?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除服务器") },
            text = { Text("删除 ${conn.name.ifEmpty { conn.baseUrl }}？将同时清除其缓存数据与书架中的 OPDS 书") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            OpdsCache.evictServer(conn.id)
                            OpdsEntryLinkCache.evictServer(conn.id)
                            OpdsBridges.database.mangaDao().deleteOpdsBooks(conn.id)
                            store.deleteConnection(conn.id)
                        }
                        connections = store.connections()
                        deleting = null
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OPDS 服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, "add")
                    }
                }
            )
        }
    ) { padding ->
        if (connections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无 OPDS 服务器，点击右上角 + 添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(connections) { conn ->
                    val isActive = store.connection()?.id == conn.id
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable {
                                scope.launch { withContext(Dispatchers.IO) { store.setActiveConnection(conn.id) } }
                                browsingId = conn.id
                            }
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(conn.name.ifEmpty { conn.baseUrl.substringAfter("://") }, fontSize = 16.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                                Text(conn.baseUrl, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(when (conn.authType.name) { "NONE" -> "无认证"; "API_KEY" -> "API Key"; "BASIC" -> "Basic Auth"; else -> conn.authType.name }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editing = conn }) {
                                Icon(Icons.Default.Edit, "edit", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { deleting = conn }) {
                                Icon(Icons.Default.Delete, "delete", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}