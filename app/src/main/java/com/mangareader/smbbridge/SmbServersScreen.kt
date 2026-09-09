package com.mangareader.smbbridge

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.SmbSourceEntity
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 服务器管理 UI（design.md 2.2.2 SmbServersScreen / spec 5.1 / 任务组2、3）。
 *
 * ★ 表单用原生 AlertDialog.Builder + EditText（2026-09-05）：Compose 对话框在测试设备
 *   （Redmi/MIUI Android 12）上键盘弹出但无法输入（日志无崩溃栈，判定焦点兼容问题），
 *   原生 View 表单绕开该兼容层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbServersScreen(onBack: () -> Unit, onOpenBook: (Long) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())

    var editing by remember { mutableStateOf<SmbSourceEntity?>(null) }
    var browsingId by remember { mutableStateOf<Long?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    var showFormTick by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<SmbSourceEntity?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showDownloadQueue by remember { mutableStateOf(false) }

    // 表单触发：tick 变化时弹原生对话框（Compose 侧只做导航，不承载输入）
    LaunchedEffect(showFormTick) {
        if (showFormTick > 0) {
            // 解包 ContextWrapper 链拿 Activity（LocalContext 可能是 ContextThemeWrapper）
            var actCtx = ctx
            while (actCtx !is Activity && actCtx is android.content.ContextWrapper) actCtx = actCtx.baseContext
            (actCtx as? Activity)?.let { activity ->
                showSmbServerForm(activity, editing) { values, initial ->
                    scope.launch {
                        runCatching {
                            if (initial == null) {
                                SmbRepository.add(
                                    displayName = values[0],
                                    host = values[1],
                                    port = values[2].toIntOrNull() ?: 445,
                                    share = values[3],
                                    pathPrefix = values[4],
                                    username = values[5],
                                    domain = values[6],
                                    password = values[7],
                                )
                            } else {
                                SmbRepository.update(
                                    initial.copy(
                                        displayName = values[0].ifBlank { values[1] },
                                        host = values[1].trim(),
                                        port = values[2].toIntOrNull() ?: 445,
                                        share = values[3],
                                        pathPrefix = values[4],
                                        username = values[5],
                                        domain = values[6],
                                    ),
                                    password = values[7].takeIf { it.isNotBlank() },
                                )
                            }
                        }
                        editing = null
                    }
                }
            }
            showFormTick = 0
        }
    }

    browsingId?.let { id ->
        SmbBrowserScreen(serverId = id, onBack = { browsingId = null }, onOpenBook = onOpenBook)
        return
    }

    if (showDownloadQueue) {
        SmbDownloadQueueScreen(onBack = { showDownloadQueue = false })
        return
    }

    if (showSettings) {
        SmbSettingsScreen(onBack = { showSettings = false }, onOpenDownloadQueue = { showDownloadQueue = true })
        return
    }

    if (showWizard) {
        SmbAddWizardScreen(onBack = { showWizard = false }, onSaved = { showWizard = false }, initial = editing)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMB 服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { showDownloadQueue = true }) { Icon(Icons.Default.Download, "下载队列") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "SMB 设置") }
                    IconButton(onClick = { showWizard = true }) { Icon(Icons.Default.Add, "添加服务器（向导）") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!SmbVersionGuard.isSupported()) {
                Text(
                    "当前系统低于 Android 12，仅可管理配置；扫描与阅读需要 Android 12+",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (sources.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无服务器，点击右上角 + 添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sources, key = { it.id }) { s ->
                        val busy = busyId == s.id
                        // ★ 任务5：隐藏状态读取（对齐本地 Switch + 状态文字）
                        val hiddenCount = remember(s.id) { mutableStateOf(0) }
                        LaunchedEffect(s.id) {
                            hiddenCount.value = withContext(Dispatchers.IO) {
                                SmbDbBridge.mangaDatabase?.mangaDao()?.countSmbHiddenBooks(s.id) ?: 0
                            }
                        }
                        val isHidden = hiddenCount.value > 0
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "smb://${s.host}:${s.port}/${s.share}/${s.pathPrefix}".trimEnd('/'),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                val statusColor: Color = when {
                                    s.lastError != null -> MaterialTheme.colorScheme.error
                                    s.lastOkAt != null -> Color(0xFF3A9D5D)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val statusText = when {
                                    s.lastError != null -> "● 错误：${s.lastError.take(60)}"
                                    s.lastOkAt != null -> "● 正常 · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.lastOkAt))}"
                                    else -> "● 未测试"
                                }
                                Text(statusText, fontSize = 12.sp, color = statusColor)
                                Spacer(Modifier.height(10.dp))
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(
                                        enabled = !busy && SmbVersionGuard.isSupported(),
                                        onClick = {
                                            scope.launch {
                                                busyId = s.id
                                                try {
                                                    val n = SmbScanner.scanSmbServer(s.id)
                                                    val msg = if (n == 0) "扫描完成，书架无变化" else "扫描成功：新增 $n 本，封面后台加载中"
                                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                                } catch (t: Throwable) {
                                                    Toast.makeText(ctx, SmbErrors.humanizeSmb(t), Toast.LENGTH_LONG).show()
                                                } finally {
                                                    busyId = null
                                                }
                                            }
                                        },
                                    ) { Text(if (busy) "扫描中…" else "扫描") }
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busyId = s.id
                                                try {
                                                    val password = try { SmbPasswordStore.get(s.id) } catch (_: Throwable) { "" }
                                                    val ok = kotlinx.coroutines.withTimeoutOrNull(5000) {
                                                        runCatching { SmbGateway.listDirectory(s, password, s.pathPrefix, useCache = false) }.isSuccess
                                                    } ?: false
                                                    if (ok) {
                                                        SmbRepository.markOk(s.id)
                                                        Toast.makeText(ctx, "连接正常", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        SmbRepository.markError(s.id, "连接超时或失败")
                                                        Toast.makeText(ctx, "连接失败", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (t: Throwable) {
                                                    SmbRepository.markError(s.id, t.message ?: "连接失败")
                                                    Toast.makeText(ctx, SmbErrors.humanizeSmb(t), Toast.LENGTH_LONG).show()
                                                } finally {
                                                    busyId = null
                                                }
                                            }
                                        },
                                    ) { Text("测试") }
                                    TextButton(onClick = { browsingId = s.id }) { Text("浏览") }
                                    TextButton(onClick = { editing = s; showWizard = true }) {
                                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("编辑")
                                    }
                                    TextButton(onClick = { deleting = s }) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("删除")
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isHidden) "已隐藏" else "已显示", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Switch(
                                        checked = !isHidden,
                                        onCheckedChange = { show ->
                                            scope.launch(Dispatchers.IO) {
                                                SmbDbBridge.mangaDatabase?.mangaDao()?.setSmbBooksHidden(s.id, !show)
                                                hiddenCount.value = SmbDbBridge.mangaDatabase?.mangaDao()?.countSmbHiddenBooks(s.id) ?: 0
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除服务器") },
            text = { Text("将删除「${s.displayName}」的配置、密码、目录索引、页缓存与封面缩略图，并移除其书架中的全部 SMB 书籍。确认删除？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = s
                    deleting = null
                    scope.launch(Dispatchers.IO) {
                        // ★ 对应清理（用户 2026-09-06 要求）：先按书籍清单清除该书源的磁盘缓存
                        val src = runCatching { SmbRepository.load(target.id) }.getOrNull()
                        val books = runCatching { SmbDbBridge.mangaDatabase?.mangaDao()?.getSmbBooks(target.id) }.getOrNull().orEmpty()
                        clearServerBookCachesSmb(target.id, src, books)
                        runCatching { SmbRepository.delete(target) }
                        runCatching { withContext(Dispatchers.Main) {} }
                        SmbDbBridge.mangaDatabase?.mangaDao()?.deleteSmbBooks(target.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "已删除（含缓存）", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/**
 * 原生表单对话框（spec 5.1.1 规则 3：host 非空、port 1-65535、share 非空）。
 * onSave 收到 8 个字段值 + 编辑对象；校验失败不关闭对话框并 Toast 提示。
 */
private fun showSmbServerForm(
    activity: Activity,
    initial: SmbSourceEntity?,
    onSave: (List<String>, SmbSourceEntity?) -> Unit,
) {
    fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun makeField(hint: String, value: String, password: Boolean = false, numeric: Boolean = false): EditText {
        return EditText(activity).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            if (password) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }

    val fields = listOf(
        makeField("显示名（可选）", initial?.displayName ?: ""),
        makeField("主机地址 *", initial?.host ?: ""),
        makeField("端口 *", initial?.port?.toString() ?: "445", numeric = true),
        makeField("共享名 *", initial?.share ?: ""),
        makeField("起始路径（可选，如 comics/）", initial?.pathPrefix ?: ""),
        makeField("用户名（可选）", initial?.username ?: ""),
        makeField("域（可选）", initial?.domain ?: ""),
        makeField(if (initial == null) "密码" else "密码（留空保持不变）", "", password = true),
    )

    val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), 0)
        fields.forEachIndexed { i, et ->
            addView(et)
            if (i < fields.size - 1) addView(android.widget.Space(activity), LinearLayout.LayoutParams(1, dp(8)))
        }
    }
    val scroll = ScrollView(activity).apply { addView(box) }

    val dialog = android.app.AlertDialog.Builder(activity)
        .setTitle(if (initial == null) "添加 SMB 服务器" else "编辑 SMB 服务器")
        .setView(scroll)
        .setPositiveButton("保存", null)
        .setNegativeButton("取消", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = fields.map { it.text.toString().trim() }
            val portNum = values[2].toIntOrNull()
            val invalid = when {
                values[1].isBlank() -> "主机地址"
                portNum == null || portNum !in 1..65535 -> "端口"
                values[3].isBlank() -> "共享名"
                else -> null
            }
            if (invalid != null) {
                Toast.makeText(activity, "$invalid 无效", Toast.LENGTH_SHORT).show()
            } else {
                onSave(values, initial)
                dialog.dismiss()
            }
        }
        // 主机字段自动聚焦（原生 EditText 在 MIUI 上焦点/键盘可靠）
        fields[1].post { fields[1].requestFocus() }
    }
    dialog.show()
}
