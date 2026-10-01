package com.mangareader.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import com.mangareader.autocat.AutoCategorizeConfig
import com.mangareader.autocat.AutoTagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShelfManageSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val dao = remember { app.db.mangaDao() }
    val scope = rememberCoroutineScope()
    var autoCatConfig by remember { mutableStateOf(AutoCategorizeConfig.load(ctx)) }
    var hiddenShelves by remember { mutableStateOf(listOf<com.mangareader.data.Bookshelf>()) }
    var newFieldName by remember { mutableStateOf("") }
    var fieldError by remember { mutableStateOf<String?>(null) }
    var rebuildProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var rebuildJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val rebuildCancel = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var deleteFieldTarget by remember { mutableStateOf<String?>(null) }
    var tagRowEnabled by remember { mutableStateOf(ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE).getBoolean("tag_filter_row_enabled", true)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { hiddenShelves = dao.getHiddenBookshelvesList() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架管理设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP).verticalScroll(rememberScrollState()).padding(12.dp)) {

            // ===== 首页与筛选 =====
            SettingsCard(Icons.Default.Tune, "首页与筛选") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("显示标签筛选入口", fontSize = 14.sp)
                        Text("首页书架行下方常驻「标签」按钮，点开即可按标签筛选", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = tagRowEnabled, onCheckedChange = {
                        tagRowEnabled = it
                        ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE).edit().putBoolean("tag_filter_row_enabled", it).apply()
                    })
                }
            }

            // ===== 自动打标签 =====
            SettingsCard(Icons.Default.Sell, "自动打标签") {
                Text("勾选后导入的书会按这些字段自动打上标签。\n「系列」写入书籍信息，书架里同系列自动合并显示 N 卷", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val standardFields = listOf(
                        "系列" to autoCatConfig.enableSeries,
                        "作者" to autoCatConfig.enableWriter,
                        "类型" to autoCatConfig.enableGenre,
                        "出版社" to autoCatConfig.enablePublisher,
                    )
                    standardFields.forEachIndexed { index, (label, checked) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = {
                                autoCatConfig = when (index) {
                                    0 -> autoCatConfig.copy(enableSeries = it)
                                    1 -> autoCatConfig.copy(enableWriter = it)
                                    2 -> autoCatConfig.copy(enableGenre = it)
                                    else -> autoCatConfig.copy(enablePublisher = it)
                                }
                                autoCatConfig.save(ctx)
                            }, modifier = Modifier.size(36.dp))
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = autoCatConfig.useNamespacePrefix,
                        onCheckedChange = {
                            autoCatConfig = autoCatConfig.copy(useNamespacePrefix = it)
                            autoCatConfig.save(ctx)
                        },
                        modifier = Modifier.height(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("标签带来源前缀（如 作者:张三）", fontSize = 13.sp)
                }
            }

            // ===== 自定义归类字段 =====
            SettingsCard(Icons.Default.EditNote, "自定义归类字段") {
                Text("填 ComicInfo.xml 里的标签名，比如 Translator、Inker", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (autoCatConfig.customFieldNames.isNotEmpty()) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        autoCatConfig.customFieldNames.forEach { fieldName ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = fieldName in autoCatConfig.enabledCustomFields,
                                    onCheckedChange = {
                                        val newEnabled = if (it) autoCatConfig.enabledCustomFields + fieldName else autoCatConfig.enabledCustomFields - fieldName
                                        autoCatConfig = autoCatConfig.copy(enabledCustomFields = newEnabled)
                                        autoCatConfig.save(ctx)
                                    },
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(fieldName, fontSize = 13.sp)
                                IconButton(onClick = { deleteFieldTarget = fieldName }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Default.Close, "删除", modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newFieldName,
                        onValueChange = { newFieldName = it; fieldError = null },
                        label = { Text("XML 标签名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val name = newFieldName.trim()
                        val standardTags = setOf("Series", "Number", "Volume", "Title", "Writer", "Penciller", "Summary", "Genre", "Publisher", "ComicInfo")
                        when {
                            name.isBlank() -> fieldError = "不能为空"
                            name.contains(',') || name.contains('，') -> fieldError = "不能包含逗号"
                            autoCatConfig.customFieldNames.any { it.equals(name, ignoreCase = true) } -> fieldError = "已存在"
                            standardTags.any { it.equals(name, ignoreCase = true) } -> fieldError = "这是标准字段，用上方勾选框启用"
                            else -> {
                                autoCatConfig = autoCatConfig.copy(
                                    customFieldNames = autoCatConfig.customFieldNames + name,
                                    enabledCustomFields = autoCatConfig.enabledCustomFields + name
                                )
                                autoCatConfig.save(ctx)
                                newFieldName = ""
                            }
                        }
                    }) { Text("添加") }
                }
                fieldError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }

            // ===== 重建标签 =====
            SettingsCard(Icons.Default.Refresh, "重建标签") {
                Text("按上方勾选的字段，从每本书的 ComicInfo.xml 重建自动标签（手动标签不受影响）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (rebuildProgress == null) {
                    TextButton(onClick = {
                        rebuildCancel.set(false)
                        rebuildProgress = 0 to 0
                        rebuildJob = scope.launch {
                            AutoTagger.rebuildAll(ctx, { d, t -> rebuildProgress = d to t }, { rebuildCancel.get() })
                            rebuildProgress = null
                        }
                    }) { Text("从 XML 重建标签") }
                } else {
                    val (d, t) = rebuildProgress!!
                    TextButton(onClick = {
                        rebuildCancel.set(true)
                        rebuildJob?.cancel()
                        rebuildProgress = null
                    }) { Text("重建中 $d/$t，点此取消") }
                }
            }

            // ===== 隐藏的书架 =====
            SettingsCard(Icons.Default.Book, "隐藏的书架") {
                if (hiddenShelves.isEmpty()) {
                    Text("没有隐藏的书架", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                hiddenShelves.forEach { shelf ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(shelf.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.setBookshelvesHiddenBatch(listOf(shelf.id), false) }
                                hiddenShelves = hiddenShelves.filter { it.id != shelf.id }
                            }
                        }) { Text("恢复显示") }
                    }
                }
            }
        }
    }

    // 自定义字段删除确认
    deleteFieldTarget?.let { field ->
        AlertDialog(
            onDismissRequest = { deleteFieldTarget = null },
            title = { Text("删除自定义字段") },
            text = { Text("确定删除「$field」？之后导入的书不再按该字段打标签（已打的标签保留）。") },
            confirmButton = {
                TextButton(onClick = {
                    autoCatConfig = autoCatConfig.copy(
                        customFieldNames = autoCatConfig.customFieldNames - field,
                        enabledCustomFields = autoCatConfig.enabledCustomFields - field
                    )
                    autoCatConfig.save(ctx)
                    deleteFieldTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFieldTarget = null }) { Text("取消") } }
        )
    }
}

/** SMB 设置同款 Card 分区容器（图标+标题+内容） */
@Composable
private fun SettingsCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
