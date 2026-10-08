package com.mangareader.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import com.mangareader.Scanner
import com.mangareader.data.Folder
import com.mangareader.wifitransfer.WifiTransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiTransferScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = (ctx.applicationContext as App).db

    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var selectedFolder by remember { mutableStateOf<Folder?>(null) }
    var isRunning by remember { mutableStateOf(WifiTransferService.isRunning) }
    var serverUrl by remember { mutableStateOf(WifiTransferService.serverUrl) }

    LaunchedEffect(Unit) {
        folders = withContext(Dispatchers.IO) { db.mangaDao().getAllFoldersList() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val running = WifiTransferService.isRunning
            val url = WifiTransferService.serverUrl
            if (running != isRunning || url != serverUrl) {
                isRunning = running
                serverUrl = url
                if (running && url != null) {
                    Toast.makeText(ctx, "服务已启动：$url", Toast.LENGTH_LONG).show()
                }
            }
            delay(500)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                selectedFolder = Folder(treeUri = uri.toString(), displayName = "新文件夹")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi 传书") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("使用说明", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "1. 选择一个目标文件夹\n" +
                            "2. 点击「启动服务」\n" +
                            "3. 在电脑浏览器打开显示的网址\n" +
                            "4. 上传完成后停止服务，已管理文件夹自动刷新\n\n" +
                            "建议使用压缩包（zip/cbz/cbr）或 EPUB 格式，散图过多影响性能",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text("选择目标文件夹", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
            }

            if (folders.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().clickable { picker.launch(null) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CreateNewFolder, null)
                            Spacer(Modifier.width(12.dp))
                            Text("选择新文件夹", fontSize = 15.sp)
                        }
                    }
                }
            } else {
                items(folders) { folder ->
                    val selected = selectedFolder?.treeUri == folder.treeUri
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selectedFolder = folder },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folder.displayName.ifEmpty { folder.treeUri }, fontSize = 15.sp)
                                Text(Uri.decode(folder.treeUri).substringAfter("/tree/", folder.treeUri), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (selected) Icon(Icons.Default.Check, null)
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth().clickable { picker.launch(null) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CreateNewFolder, null)
                            Spacer(Modifier.width(12.dp))
                            Text("选择其他文件夹…", fontSize = 15.sp)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val folder = selectedFolder ?: return@Button
                        if (isRunning) {
                            ctx.startService(Intent(ctx, WifiTransferService::class.java).setAction(WifiTransferService.ACTION_STOP))
                            isRunning = false
                            serverUrl = null
                            val managed = folders.find { it.treeUri == selectedFolder?.treeUri }
                            if (managed != null) {
                                Toast.makeText(ctx, "正在刷新文件夹…", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    val (added, _) = Scanner(ctx, db.mangaDao()).rescanAndCommit(managed)
                                    Toast.makeText(ctx, "刷新完成，新增 $added 本", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            ctx.startService(
                                Intent(ctx, WifiTransferService::class.java)
                                    .setAction(WifiTransferService.ACTION_START)
                                    .putExtra(WifiTransferService.EXTRA_FOLDER_URI, folder.treeUri)
                            )
                            isRunning = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedFolder != null,
                    colors = if (isRunning) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors()
                ) {
                    Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRunning) "停止服务" else "启动服务")
                }
            }

            if (isRunning && serverUrl != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("服务运行中", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(serverUrl!!, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text("在同一局域网下用浏览器打开上述网址", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}