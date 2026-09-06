package com.mangareader.webdavbridge

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.mangareader.smbbridge.SmbErrors
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV 服务器管理 UI（design.md 2.2.2 WebDavServersScreen / spec 5.7 / 任务组12、13）。
 * **无 API 守卫**：API 26+ 全部可用。
 * ★ 表单用原生 AlertDialog.Builder + EditText（同 SmbServersScreen，绕开 MIUI Compose 输入兼容问题）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavServersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by WebDavRepository.sourcesFlow().collectAsState(initial = emptyList())

    var editing by remember { mutableStateOf<WebDavSourceEntity?>(null) }
    var browsingId by remember { mutableStateOf<Long?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    var showFormTick by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<WebDavSourceEntity?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(showFormTick) {
        if (showFormTick > 0) {
            // 解包 ContextWrapper 链拿 Activity（LocalContext 可能是 ContextThemeWrapper）
            var actCtx = ctx
            while (actCtx !is Activity && actCtx is android.content.ContextWrapper) actCtx = actCtx.baseContext
            (actCtx as? Activity)?.let { activity ->
                showWebDavServerForm(activity, editing) { values, initial ->
                    scope.launch {
                        runCatching {
                            if (initial == null) {
                                WebDavRepository.add(
                                    displayName = values[0],
                                    baseUrl = values[1],
                                    pathPrefix = values[2],
                                    username = values[3],
                                    password = values[4],
                                )
                            } else {
                                WebDavRepository.update(
                                    initial.copy(
                                        displayName = values[0].ifBlank {
                                            runCatching { java.net.URI(values[1]).host }.getOrNull() ?: values[1]
                                        },
                                        baseUrl = WebDavClient.normalizeBaseUrl(values[1]),
                                        pathPrefix = values[2],
                                        username = values[3],
                                    ),
                                    password = values[4].takeIf { it.isNotBlank() },
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
        WebDavBrowserScreen(serverId = id, onBack = { browsingId = null })
        return
    }

    if (showSettings) {
        WebDavSettingsScreen(onBack = { showSettings = false })
        return
    }

    if (showWizard) {
        WebDavAddWizardScreen(onBack = { showWizard = false }, onSaved = { showWizard = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "WebDAV 设置") }
                    IconButton(onClick = { showWizard = true }) { Icon(Icons.Default.Add, "添加服务器（向导）") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (sources.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无服务器，点击右上角 + 添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sources, key = { it.id }) { s ->
                        val busy = busyId == s.id
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(s.displayName, fontSize = 16.sp)
                                Text(
                                    s.baseUrl.trimEnd('/') + "/" + s.pathPrefix,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val statusColor: Color = when {
                                    s.lastError != null -> MaterialTheme.colorScheme.error
                                    s.lastOkAt != null -> Color(0xFF3A9D5D)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val statusText = when {
                                    s.lastError != null -> "错误：${s.lastError.take(80)}"
                                    s.lastOkAt != null -> "正常 · 上次成功 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.lastOkAt))
                                    else -> "未测试"
                                }
                                Text(statusText, fontSize = 12.sp, color = statusColor)
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busyId = s.id
                                                try {
                                                    val n = WebDavScanner.scanWebDavServer(s.id)
                                                    Toast.makeText(ctx, "扫描完成，新增 $n 本", Toast.LENGTH_SHORT).show()
                                                } catch (t: Throwable) {
                                                    Toast.makeText(ctx, SmbErrors.humanizeWebDav(t), Toast.LENGTH_LONG).show()
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
                                                    val password = try { WebDavPasswordStore.get(s.id) } catch (_: Throwable) { "" }
                                                    WebDavClient.testConnection(s, password).getOrThrow()
                                                    WebDavRepository.markOk(s.id)
                                                    Toast.makeText(ctx, "连接正常", Toast.LENGTH_SHORT).show()
                                                } catch (t: Throwable) {
                                                    WebDavRepository.markError(s.id, t.message ?: "连接失败")
                                                    Toast.makeText(ctx, SmbErrors.humanizeWebDav(t), Toast.LENGTH_LONG).show()
                                                } finally {
                                                    busyId = null
                                                }
                                            }
                                        },
                                    ) { Text("测试") }
                                    TextButton(onClick = { browsingId = s.id }) { Text("浏览") }
                                    TextButton(onClick = { browsingId = s.id }) { Text("浏览") }
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busyId = s.id
                                                try {
                                                    val anyHidden = WebDavDbBridge.mangaDatabase?.mangaDao()?.countWebDavHiddenBooks(s.id) ?: 0
                                                    val target = anyHidden == 0
                                                    WebDavDbBridge.mangaDatabase?.mangaDao()?.setWebDavBooksHidden(s.id, target)
                                                    Toast.makeText(ctx, if (target) "已隐藏该书源全部书籍" else "已恢复显示", Toast.LENGTH_SHORT).show()
                                                } finally {
                                                    busyId = null
                                                }
                                            }
                                        },
                                    ) { Text("隐藏/显示") }
                                                                        TextButton(onClick = { editing = s; showFormTick++ }) {
                                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("编辑")
                                    }
                                    TextButton(onClick = { deleting = s }) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("删除")
                                    }
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
            text = { Text("将删除「${s.displayName}」的配置、密码、目录索引、页缓存与封面缩略图，并移除其书架中的全部 WebDAV 书籍。确认删除？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = s
                    deleting = null
                    scope.launch(Dispatchers.IO) {
                        // ★ 对应清理：先按书籍清单清除该书源的磁盘缓存
                        val src = runCatching { WebDavRepository.load(target.id) }.getOrNull()
                        val books = runCatching { WebDavDbBridge.mangaDatabase?.mangaDao()?.getWebDavBooks(target.id) }.getOrNull().orEmpty()
                        clearServerBookCachesWebDav(target.id, src, books)
                        runCatching { WebDavRepository.delete(target) }
                        WebDavDbBridge.mangaDatabase?.mangaDao()?.deleteWebDavBooks(target.id)
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

/** 原生表单对话框（spec 5.7.1：baseUrl 非空且合法）。 */
private fun showWebDavServerForm(
    activity: Activity,
    initial: WebDavSourceEntity?,
    onSave: (List<String>, WebDavSourceEntity?) -> Unit,
) {
    fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun makeField(hint: String, value: String, password: Boolean = false): EditText {
        return EditText(activity).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            if (password) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }

    val fields = listOf(
        makeField("显示名（可选）", initial?.displayName ?: ""),
        makeField("服务器地址 *（如 https://dav.example.com/）", initial?.baseUrl ?: ""),
        makeField("起始路径（可选）", initial?.pathPrefix ?: ""),
        makeField("用户名", initial?.username ?: ""),
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
        .setTitle(if (initial == null) "添加 WebDAV 服务器" else "编辑 WebDAV 服务器")
        .setView(scroll)
        .setPositiveButton("保存", null)
        .setNegativeButton("取消", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = fields.map { it.text.toString().trim() }
            val invalid = when {
                values[1].isBlank() -> "服务器地址"
                !(values[1].startsWith("http://") || values[1].startsWith("https://")) -> "地址格式"
                else -> null
            }
            if (invalid != null) {
                Toast.makeText(activity, "$invalid 无效（需以 http(s):// 开头）", Toast.LENGTH_SHORT).show()
            } else {
                onSave(values, initial)
                dialog.dismiss()
            }
        }
        fields[1].post { fields[1].requestFocus() }
    }
    dialog.show()
}
