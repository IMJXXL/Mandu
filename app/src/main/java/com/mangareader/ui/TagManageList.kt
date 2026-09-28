package com.mangareader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.MangaDao
import com.mangareader.data.TagWithCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 标签管理列表（design.md §五 / mockup_manage.png）：
 * 搜索 + 排序（数量/名称）+ 列表（色点/名称/挂书数）+ ⋯菜单（重命名/改色/转手动/删除）
 * 长按多选 → 底部栏合并到…/删除。0 本标签按数量排序自然沉底（孤儿标签治理）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagManageList(dao: MangaDao, scope: kotlinx.coroutines.CoroutineScope) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    var tags by remember { mutableStateOf(listOf<TagWithCount>()) }
    var sortByName by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var multiMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var renameTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var renameText by remember { mutableStateOf("") }
    var colorTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var deleteTargets by remember { mutableStateOf<List<TagWithCount>>(emptyList()) }
    var showMergePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { dao.getTagsWithCounts().collect { tags = it } }

    val shown = remember(tags, query, sortByName) {
        val base = if (query.isBlank()) tags else tags.filter { it.tag.name.contains(query, ignoreCase = true) }
        if (sortByName) base.sortedBy { it.tag.name.lowercase() } else base.sortedByDescending { it.bookCount }
    }

    fun exitMulti() { multiMode = false; selected = emptySet() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索标签…", fontSize = 14.sp) },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, null, Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { sortByName = !sortByName }) {
                    Text(if (sortByName) "按名称" else "按数量", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (multiMode) {
                Text("已选 ${selected.size} 个标签，长按可追加选择", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
            }
            if (shown.isEmpty()) {
                Text(if (tags.isEmpty()) "还没有标签。导入带 ComicInfo.xml 的书，或在书的详情页手动添加" else "没有匹配的标签",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.forEach { twc ->
                val id = twc.tag.id
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (id in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    tonalElevation = if (id in selected) 0.dp else 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .combinedClickable(
                            onClick = {
                                if (multiMode) selected = if (id in selected) selected - id else selected + id
                            },
                            onLongClick = { multiMode = true; selected = selected + id }
                        )
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TagDot(twc.tag.name, twc.tag.color, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(twc.tag.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("${twc.bookCount} 本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, null)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; renameTarget = twc; renameText = twc.tag.name })
                                DropdownMenuItem(text = { Text("改色") }, onClick = { menuOpen = false; colorTarget = twc })
                                DropdownMenuItem(text = { Text("转手动（重扫免疫）") }, onClick = {
                                    menuOpen = false
                                    scope.launch { withContext(Dispatchers.IO) { dao.markTagManual(id) } }
                                })
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = {
                                    menuOpen = false; deleteTargets = listOf(twc)
                                })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(72.dp))
        }

        // 多选底部栏
        if (multiMode && selected.isNotEmpty()) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { showMergePicker = true }, enabled = selected.size >= 2) { Text("合并到…") }
                    TextButton(onClick = {
                        deleteTargets = shown.filter { it.tag.id in selected }
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { exitMulti() }) { Text("取消") }
                }
            }
        }
    }

    // 重命名
    renameTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名标签") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotEmpty()) {
                        scope.launch { withContext(Dispatchers.IO) { dao.renameTag(t.tag.id, newName) } }
                    }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    // 删除确认
    if (deleteTargets.isNotEmpty()) {
        val total = deleteTargets.sumOf { it.bookCount }
        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = { Text("删除标签") },
            text = { Text("确定删除 ${deleteTargets.size} 个标签？$total 本书将移除这些标签（书籍本身不受影响）。") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = deleteTargets.map { it.tag.id }
                    scope.launch {
                        withContext(Dispatchers.IO) { dao.deleteTagsByIds(ids) }
                        deleteTargets = emptyList(); exitMulti()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = emptyList() }) { Text("取消") } }
        )
    }

    // 合并目标选择
    if (showMergePicker) {
        val candidates = tags.filter { it.tag.id !in selected }
        AlertDialog(
            onDismissRequest = { showMergePicker = false },
            title = { Text("合并到哪个标签？") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) Text("没有其他标签可作为目标", fontSize = 13.sp)
                    candidates.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val src = selected.toList()
                            showMergePicker = false
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.mergeTags(t.tag.id, src) }
                                exitMulti()
                            }
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TagDot(t.tag.name, t.tag.color, size = 16.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(t.tag.name, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMergePicker = false }) { Text("取消") } }
        )
    }

    // 改色
    colorTarget?.let { t ->
        TagColorPickerSheet(
            visible = true,
            tagName = t.tag.name,
            currentColor = t.tag.color,
            darkTheme = dark,
            onPick = { color ->
                scope.launch { withContext(Dispatchers.IO) { dao.setTagColor(t.tag.id, color) } }
            },
            onDismiss = { colorTarget = null }
        )
    }
}
