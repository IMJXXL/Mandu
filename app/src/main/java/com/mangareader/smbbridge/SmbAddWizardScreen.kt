package com.mangareader.smbbridge

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.SmbSourceEntity
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 添加向导（2026-09-06 用户要求，对齐参考项目分步流程）：
 * 1/3 地址与身份 → 2/3 从服务器枚举并选择共享 → 3/3 浏览并选定目录 → 以导入文件夹的形式保存书源。
 * 选定后该书源像本地导入文件夹一样：可扫描、可浏览、书进书架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbAddWizardScreen(onBack: () -> Unit, onSaved: () -> Unit, initial: SmbSourceEntity? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEdit = initial != null

    var step by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }

    // step1 字段（编辑模式预填）
    var displayName by remember { mutableStateOf(initial?.displayName ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "445") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var password by remember { mutableStateOf("") }

    // step2/3 状态
    var tempSource by remember { mutableStateOf<SmbSourceEntity?>(null) }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickedShare by remember { mutableStateOf(initial?.share ?: "") }
    var dir by remember { mutableStateOf(initial?.pathPrefix ?: "") }
    var dirEntries by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }

    // 编辑模式：加载已保存的密码
    LaunchedEffect(isEdit) {
        if (isEdit && password.isBlank()) {
            password = try { com.hippo.ehviewer.smb.SmbPasswordStore.get(initial!!.id) } catch (_: Throwable) { "" }
        }
    }

    val titles = listOf("1/3 地址与账号", "2/3 选择共享", "3/3 选择目录")


    suspend fun connect(): Boolean {
        val portNum = port.toIntOrNull() ?: 445
        if (host.isBlank()) {
            Toast.makeText(ctx, "主机地址不能为空", Toast.LENGTH_SHORT).show()
            return false
        }
        busy = true
        android.util.Log.i("SmbWizard", "connect start")
        val temp = SmbSourceEntity(
            displayName = displayName.ifBlank { host },
            host = host.trim(),
            port = portNum,
            share = "",
            username = username,
            domain = domain,
            addedAt = 0,
        )
        val result = withContext(Dispatchers.IO) {
            runCatching { SmbGateway.listDirectory(temp, password, "") }
        }
        busy = false
        return result.fold(onSuccess = { entries ->
            android.util.Log.i("SmbWizard", "connect OK, entries=${entries.size}")
            tempSource = temp
            shares = entries.map { it.name }
            true
        }, onFailure = {
            android.util.Log.w("SmbWizard", "connect FAILED", it)
            android.util.Log.w("SmbWizard", "cause=${it.cause?.javaClass?.name}: ${it.cause?.message}")
            Toast.makeText(ctx, SmbErrors.humanizeSmb(it), Toast.LENGTH_LONG).show()
            false
        })
    }

    suspend fun loadDirs(d: String) {
        val src = tempSource ?: return
        busy = true
        dirEntries = withContext(Dispatchers.IO) {
            runCatching { SmbGateway.listDirectory(src, password, d, useCache = false) }.getOrDefault(emptyList())
        }
        busy = false
    }


    fun save() {
        val src = tempSource ?: return
        busy = true
        scope.launch(Dispatchers.IO) {
            val id = runCatching {
                if (isEdit && initial != null) {
                    SmbRepository.update(
                        initial.copy(
                            displayName = displayName.ifBlank { host },
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 445,
                            share = pickedShare,
                            pathPrefix = dir,
                            username = username,
                            domain = domain,
                        ),
                        password = password.takeIf { it.isNotBlank() },
                    )
                    initial.id
                } else {
                    SmbRepository.add(
                        displayName = displayName,
                        host = host,
                        port = port.toIntOrNull() ?: 445,
                        share = pickedShare,
                        pathPrefix = dir,
                        username = username,
                        domain = domain,
                        password = password,
                    )
                }
            }.getOrElse {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "保存失败：${it.message?.take(60)}", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            // ★ 任务3：添加/编辑后自动扫描（用户 2026-09-08 要求）
            val scanned = runCatching { SmbScanner.scanSmbServer(id) }.getOrNull()
            withContext(Dispatchers.Main) {
                busy = false
                val msg = if (isEdit) "已更新" else "已保存"
                val scanMsg = when {
                    scanned == null -> ""
                    scanned == 0 -> "，书架无变化"
                    else -> "，扫描新增 $scanned 本，封面后台加载中"
                }
                Toast.makeText(ctx, "$msg：$pickedShare/$dir$scanMsg", Toast.LENGTH_SHORT).show()
                onSaved()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (isEdit) "编辑 SMB 书源" else "添加 SMB 书源", fontSize = 16.sp)
                        Text(titles[step - 1], fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step > 1) step-- else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (step > 1) "上一步" else "返回") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            when (step) {
                1 -> Column {
                    OutlinedTextField(displayName, { displayName = it }, label = { Text("名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(host, { host = it }, label = { Text("主机地址或 IP *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(port, { port = it }, label = { Text("端口 *") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(domain, { domain = it }, label = { Text("域（可选）") }, singleLine = true, modifier = Modifier.weight(2f))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { scope.launch { if (connect()) step = 2 } },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "连接中…" else "连接并获取共享列表") }
                    Text(
                        "下一步将从服务器枚举共享列表；访客共享可不填账号密码",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                2 -> Column {
                    if (busy) {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    if (!busy && shares.isEmpty()) {
                        Text(
                            "未发现共享，请检查主机地址和凭据",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    LazyColumn {
                        items(shares, key = { it }) { share ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        pickedShare = share
                                        tempSource = tempSource?.copy(share = share)
                                        // ★ 任务2：等 loadDirs 完成后再切 step3，避免空白目录列表
                                        scope.launch {
                                            loadDirs("")
                                            step = 3
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Color(0xFF8FB8E8))
                                Spacer(Modifier.width(12.dp))
                                Text(share, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                if (share == pickedShare) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                3 -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "已选共享：$pickedShare",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Text(
                        "当前目录：/${dir}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Card(Modifier.fillMaxWidth().clickable {
                        if (dir.isNotEmpty()) {
                            dir = dir.substringBeforeLast('/', "")
                            scope.launch { loadDirs(dir) }
                        }
                    }) {
                        Text("← 返回上一级目录", fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                    if (busy) {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(dirEntries) { e ->
                            if (e is BrowseEntryRemote.Directory) {
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        val next = if (dir.isEmpty()) e.relativeName else "$dir/${e.relativeName}"
                                        dir = next
                                        scope.launch { loadDirs(next) }
                                    }.padding(horizontal = 4.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = Color(0xFF8FB8E8))
                                    Spacer(Modifier.width(12.dp))
                                    Text(e.name, fontSize = 15.sp)
                                }
                            } else {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { save() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("选定此目录，保存书源") }
                    Text(
                        "保存后该书源像本地导入文件夹一样：可扫描/浏览，书进书架",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
