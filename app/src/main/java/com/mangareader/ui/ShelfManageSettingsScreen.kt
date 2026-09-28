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
    var showMigrateDialog by remember { mutableStateOf(false) }
    var migratePicked by remember { mutableStateOf(setOf<Long>()) }
    var allShelvesCache by remember { mutableStateOf(listOf<com.mangareader.data.Bookshelf>()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { allShelvesCache = dao.getAllBookshelvesList() }
    }

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
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text("自动打标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("勾选后导入的书会按这些字段自动打上标签，一本书可以同时有多个标签。\n「系列」写入书籍信息，书架里同系列自动合并显示 N 卷，不再单独建书架", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
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
                        }, modifier = Modifier.size(40.dp))
                        Text(label, fontSize = 13.sp)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autoCatConfig.useNamespacePrefix,
                    onCheckedChange = {
                        autoCatConfig = autoCatConfig.copy(useNamespacePrefix = it)
                        autoCatConfig.save(ctx)
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text("标签带来源前缀（如 作者:张三 / 出版:讲谈社）", fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
            Text("自定义归类字段", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("填 ComicInfo.xml 里的标签名，比如 Translator、Inker、Colorist", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                modifier = Modifier.size(36.dp)
                            )
                            Text(fieldName, fontSize = 13.sp)
                            IconButton(onClick = {
                                autoCatConfig = autoCatConfig.copy(
                                    customFieldNames = autoCatConfig.customFieldNames - fieldName,
                                    enabledCustomFields = autoCatConfig.enabledCustomFields - fieldName
                                )
                                autoCatConfig.save(ctx)
                            }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "删除", modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        name.contains(',') || name.contains('，') -> fieldError = "不能包含逗号"  // 持久化按逗号join/split，含逗号会存取错乱
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

            Spacer(Modifier.height(16.dp))
            Text("重建标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
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

            Spacer(Modifier.height(16.dp))
            Text("迁移工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("把自动归类时代建出的书架整体转成同名标签（书全部挂上标签后删除书架），书架从此回归人工策展", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                migratePicked = emptySet()
                showMigrateDialog = true
            }) { Text("自动书架转标签…") }

            Spacer(Modifier.height(16.dp))
            Text("隐藏的书架", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (hiddenShelves.isEmpty()) {
                Text("没有隐藏的书架", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            hiddenShelves.forEach { shelf ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Book, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
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

    if (showMigrateDialog) {
        AlertDialog(
            onDismissRequest = { showMigrateDialog = false },
            title = { Text("自动书架转标签") },
            text = {
                Column {
                    Text("勾选要转换的书架（转换后书架删除，书全部挂上同名标签，之后可从标签重建书架）：", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        allShelvesCache.forEach { shelf ->
                            Row(Modifier.fillMaxWidth().clickable {
                                migratePicked = if (shelf.id in migratePicked) migratePicked - shelf.id else migratePicked + shelf.id
                            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = shelf.id in migratePicked, onCheckedChange = {
                                    migratePicked = if (it) migratePicked + shelf.id else migratePicked - shelf.id
                                })
                                Text(shelf.name, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val picked = migratePicked.toList()
                    if (picked.isNotEmpty()) {
                        showMigrateDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val pairs = picked.mapNotNull { id -> allShelvesCache.find { it.id == id }?.let { it.id to it.name } }
                                dao.migrateShelvesToTags(pairs)
                            }
                        }
                    }
                }) { Text("转换 ${migratePicked.size} 个") }
            },
            dismissButton = { TextButton(onClick = { showMigrateDialog = false }) { Text("取消") } }
        )
    }
}
