package com.mangareader.ui

import android.content.Context
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
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
            Text("自动归类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("勾选后导入的书会按这些字段自动分到对应书架，一本书可以同时进多个书架", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoCatConfig.enableSeries, onCheckedChange = { autoCatConfig = autoCatConfig.copy(enableSeries = it); autoCatConfig.save(ctx) })
                Spacer(Modifier.width(4.dp))
                Text("系列")
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoCatConfig.enableWriter, onCheckedChange = { autoCatConfig = autoCatConfig.copy(enableWriter = it); autoCatConfig.save(ctx) })
                Spacer(Modifier.width(4.dp))
                Text("作者")
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoCatConfig.enableGenre, onCheckedChange = { autoCatConfig = autoCatConfig.copy(enableGenre = it); autoCatConfig.save(ctx) })
                Spacer(Modifier.width(4.dp))
                Text("类型")
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoCatConfig.enablePublisher, onCheckedChange = { autoCatConfig = autoCatConfig.copy(enablePublisher = it); autoCatConfig.save(ctx) })
                Spacer(Modifier.width(4.dp))
                Text("出版社")
            }

            Spacer(Modifier.height(16.dp))
            Text("自定义归类字段", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("填 ComicInfo.xml 里的标签名，比如 Translator、Inker、Colorist", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            autoCatConfig.customFieldNames.forEach { fieldName ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = fieldName in autoCatConfig.enabledCustomFields,
                        onCheckedChange = {
                            val newEnabled = if (it) autoCatConfig.enabledCustomFields + fieldName else autoCatConfig.enabledCustomFields - fieldName
                            autoCatConfig = autoCatConfig.copy(enabledCustomFields = newEnabled)
                            autoCatConfig.save(ctx)
                        }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(fieldName, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        autoCatConfig = autoCatConfig.copy(
                            customFieldNames = autoCatConfig.customFieldNames - fieldName,
                            enabledCustomFields = autoCatConfig.enabledCustomFields - fieldName
                        )
                        autoCatConfig.save(ctx)
                    }) { Icon(Icons.Default.Close, "删除", modifier = Modifier.size(18.dp)) }
                }
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
                    when {
                        name.isBlank() -> fieldError = "不能为空"
                        name in autoCatConfig.customFieldNames -> fieldError = "已存在"
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
}
