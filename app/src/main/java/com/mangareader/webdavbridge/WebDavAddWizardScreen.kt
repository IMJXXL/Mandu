package com.mangareader.webdavbridge

import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.hippo.ehviewer.util.LocalNetworkPermissionEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mangareader.smbbridge.SmbErrors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV 添加/编辑向导（对齐参考项目分步流程，2026-09-06 用户要求；
 * 2026-09-11 加 initial 编辑模式对齐 SmbAddWizardScreen）：
 * 1/2 地址与身份（连接测试）→ 2/2 浏览并选定目录 → 以导入文件夹的形式保存书源。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavAddWizardScreen(onBack: () -> Unit, onSaved: () -> Unit, initial: WebDavSourceEntity? = null) {
    LocalNetworkPermissionEffect()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEdit = initial != null

    var step by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }

    BackHandler { if (step > 1) step-- else onBack() }

    var displayName by remember { mutableStateOf(initial?.displayName ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var noCoverFetch by remember { mutableStateOf(initial?.noCoverFetch ?: false) }

    var tempSource by remember { mutableStateOf<WebDavSourceEntity?>(null) }
    var dir by remember { mutableStateOf(initial?.pathPrefix ?: "") }
    var dirEntries by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }

    // 编辑模式：加载已保存的密码回填（对齐 SmbAddWizardScreen）
    LaunchedEffect(isEdit) {
        if (isEdit && password.isBlank()) {
            password = try { com.hippo.ehviewer.webdav.WebDavPasswordStore.get(initial!!.id) } catch (_: Throwable) { "" }
        }
    }

    val titles = listOf("1/2 地址与身份", "2/2 选择目录")

    suspend fun connect(): Boolean {
        if (baseUrl.isBlank() || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            Toast.makeText(ctx, "服务器地址无效（需 http(s):// 开头）", Toast.LENGTH_SHORT).show()
            return false
        }
        busy = true
        android.util.Log.i("SmbWizard", "connect start")
        val temp = WebDavSourceEntity(
            displayName = displayName.ifBlank { runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: baseUrl },
            baseUrl = WebDavClient.normalizeBaseUrl(baseUrl),
            username = username,
            addedAt = 0,
        )
        val result = withContext(Dispatchers.IO) {
            runCatching {
                WebDavClient.testConnection(temp, password).getOrThrow()
                WebDavGateway.listDirectory(temp, password, "", useCache = false)
            }
        }
        busy = false
        return result.fold(onSuccess = { entries ->
            android.util.Log.i("SmbWizard", "connect OK, entries=${entries.size}")
            tempSource = temp
            dirEntries = entries
            true
        }, onFailure = {
            android.util.Log.w("WebDavWizard", "connect FAILED", it)
            android.util.Log.w("WebDavWizard", "cause=${it.cause?.javaClass?.name}: ${it.cause?.message}")
            Toast.makeText(ctx, SmbErrors.humanizeWebDav(it), Toast.LENGTH_LONG).show()
            false
        })
    }

    suspend fun loadDirs(d: String) {
        val src = tempSource ?: return
        busy = true
        dirEntries = withContext(Dispatchers.IO) {
            runCatching { WebDavGateway.listDirectory(src, password, d, useCache = false) }.getOrDefault(emptyList())
        }
        busy = false
    }

    fun save() {
        val src = tempSource ?: return
        busy = true
        scope.launch(Dispatchers.IO) {
            val savedId = runCatching {
                if (isEdit && initial != null) {
                    WebDavRepository.update(
                        initial.copy(
                            displayName = displayName.ifBlank {
                                runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: baseUrl
                            },
                            baseUrl = baseUrl,
                            pathPrefix = dir,
                            username = username,
                            noCoverFetch = noCoverFetch,
                        ),
                        password = password.takeIf { it.isNotBlank() },
                    )
                    initial.id
                } else {
                    val newId = WebDavRepository.add(
                        displayName = displayName,
                        baseUrl = baseUrl,
                        pathPrefix = dir,
                        username = username,
                        password = password,
                    )
                    if (noCoverFetch) {
                        com.hippo.ehviewer.webdav.WebDavRepository.load(newId)?.let {
                            com.hippo.ehviewer.webdav.WebDavRepository.update(it.copy(noCoverFetch = true), null)
                        }
                    }
                    newId
                }
            }.getOrElse {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "保存失败：${it.message?.take(60)}", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            // ★ 对齐 SmbAddWizardScreen：先扫描完成后才导航返回，
            //   否则 onSaved() 销毁 Composable 取消 scope，扫描协程永远跑不完
            val scanned = runCatching { WebDavScanner.scanWebDavServer(savedId as Long) }.getOrNull()
            withContext(Dispatchers.Main) {
                busy = false
                val msg = if (isEdit) "已更新" else "已保存"
                val scanMsg = when {
                    scanned == null -> ""
                    scanned == 0 -> "，书架无变化"
                    else -> if (noCoverFetch) "，扫描新增 $scanned 本（已开启不主动加载封面）" else "，扫描新增 $scanned 本，封面后台加载中"
                }
                Toast.makeText(ctx, "$msg：/$dir$scanMsg", Toast.LENGTH_SHORT).show()
                onSaved()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (isEdit) "编辑 WebDAV 书源" else "添加 WebDAV 书源", fontSize = 16.sp)
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
            if (step == 1) {
                Column {
                    OutlinedTextField(displayName, { displayName = it }, label = { Text("名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("服务器地址 *") }, supportingText = { Text("示例：https://dav.example.com/") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("不主动加载封面", fontSize = 14.sp)
                            Text("只显示打开过的书的封面，点开阅读后自动补上；防服务器限流，阅读不受影响", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = noCoverFetch, onCheckedChange = { noCoverFetch = it })
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { scope.launch { if (connect()) step = 2 } },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "连接中…" else "连接并浏览目录") }
                    Text(
                        "自签名 HTTPS 服务请先在 WebDAV 设置中开启「允许自签名/不安全证书」",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Text(
                        "选定目录后，该书源像本地导入文件夹一样：可扫描/浏览，书进书架",
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
                    Text(
                        "当前目录：/${dir}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                    if (busy) {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    if (!busy && dirEntries.isEmpty()) {
                        Text(
                            "目录为空，请检查地址和凭据，或返回上一级目录",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
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
                }
            }
        }
    }
}
