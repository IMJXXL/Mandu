package com.mangareader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.MangaDao
import com.mangareader.data.TagWithCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 标签管理列表（design.md §五，反馈批三次迭代）：
 * - 搜索 + 三态排序（按数量/按名称/按手动拖动）
 * - 手动模式长按拖动排序（sortOrder 落库）
 * - 多选带勾选框（外部 TopBar 触发，长按也可进入）→ 底部合并到…/删除
 * - 布局：列表 / 网格（FlowRow 紧凑 chips，由父级布局开关传入）
 * - 隐藏标签分组（⋯菜单隐藏，恢复显示）；0 本孤儿按数量排序自然沉底
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagManageList(
    dao: MangaDao,
    scope: kotlinx.coroutines.CoroutineScope,
    multiSelect: Boolean,
    onMultiSelectChange: (Boolean) -> Unit,
    layoutGrid: Boolean,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    var tags by remember { mutableStateOf(listOf<TagWithCount>()) }
    var sortMode by remember { mutableStateOf(0) } // 0=数量 1=名称 2=手动
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var renameTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var renameText by remember { mutableStateOf("") }
    var colorTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var deleteTargets by remember { mutableStateOf<List<TagWithCount>>(emptyList()) }
    var showMergePicker by remember { mutableStateOf(false) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    val rowHeight = 64f

    LaunchedEffect(Unit) { dao.getAllTagsWithCounts().collect { tags = it } }

    val multiMode = multiSelect
    fun exitMulti() { onMultiSelectChange(false); selected = emptySet() }

    val shown = remember(tags, query, sortMode) {
        val base = tags.filter { !it.tag.isHidden && (query.isBlank() || it.tag.name.contains(query, ignoreCase = true)) }
        when (sortMode) {
            1 -> base.sortedBy { it.tag.name.lowercase() }
            2 -> base.sortedBy { it.tag.sortOrder }
            else -> base.sortedByDescending { it.bookCount }
        }
    }
    val hiddenShown = remember(tags, query) {
        tags.filter { it.tag.isHidden && (query.isBlank() || it.tag.name.contains(query, ignoreCase = true)) }
    }
    val dragEnabled = sortMode == 2 && query.isBlank() && !multiMode && !layoutGrid && shown.size > 1

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
                TextButton(onClick = { sortMode = (sortMode + 1) % 3 }) {
                    Text(if (sortMode == 0) "按数量" else if (sortMode == 1) "按名称" else "按手动", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (multiMode) {
                Text("已选 ${selected.size} 个标签，点选勾选，长按可追加", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
            } else if (dragEnabled) {
                Text("长按拖动排序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            if (shown.isEmpty()) {
                Text(if (tags.isEmpty()) "还没有标签。导入带 ComicInfo.xml 的书，或点右上角新建" else "没有匹配的标签",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (layoutGrid) {
                // ===== 网格：紧凑 chips，点按=多选勾选/动作菜单 =====
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    shown.forEach { twc ->
                        val id = twc.tag.id
                        var chipMenu by remember { mutableStateOf(false) }
                        Box {
                            FilterChip(
                                selected = id in selected,
                                onClick = {
                                    if (multiMode) selected = if (id in selected) selected - id else selected + id
                                    else chipMenu = true
                                },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TagDot(twc.tag.name, twc.tag.color)
                                        Spacer(Modifier.width(6.dp))
                                        Text(twc.tag.name, fontSize = 13.sp)
                                    }
                                }
                            )
                            DropdownMenu(expanded = chipMenu, onDismissRequest = { chipMenu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { chipMenu = false; renameTarget = twc; renameText = twc.tag.name })
                                DropdownMenuItem(text = { Text("改色") }, onClick = { chipMenu = false; colorTarget = twc })
                                DropdownMenuItem(text = { Text("隐藏") }, onClick = {
                                    chipMenu = false
                                    scope.launch { withContext(Dispatchers.IO) { dao.setTagHidden(id, true) } }
                                })
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = {
                                    chipMenu = false; deleteTargets = listOf(twc)
                                })
                            }
                        }
                    }
                }
            } else {
                // ===== 列表（手动模式可拖动） =====
                shown.forEachIndexed { index, twc ->
                    val id = twc.tag.id
                    val maxUp = index * rowHeight
                    val maxDown = (shown.size - 1 - index) * rowHeight
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (id in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        tonalElevation = if (id in selected) 0.dp else 1.dp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .graphicsLayer {
                                if (draggedIndex == index) {
                                    translationY = dragOffset.coerceIn(-maxUp, maxDown) * density
                                    shadowElevation = 8f
                                }
                            }
                            .pointerInput(index, shown.size, dragEnabled) {
                                if (dragEnabled) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggedIndex = index },
                                        onDragEnd = {
                                            if (draggedIndex >= 0 && draggedIndex < shown.size) {
                                                val finalIndex = (draggedIndex + (dragOffset / rowHeight).toInt()).coerceIn(0, shown.size - 1)
                                                if (finalIndex != draggedIndex) {
                                                    val mutable = shown.toMutableList()
                                                    val item = mutable.removeAt(draggedIndex)
                                                    mutable.add(finalIndex, item)
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            mutable.forEachIndexed { i, t -> dao.updateTagSort(t.tag.id, i + 1) }
                                                        }
                                                    }
                                                }
                                            }
                                            draggedIndex = -1; dragOffset = 0f
                                        },
                                        onDragCancel = { draggedIndex = -1; dragOffset = 0f },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset += dragAmount.y
                                        }
                                    )
                                }
                            }
                    ) {
                        Row(
                            Modifier.height(64.dp).padding(horizontal = 12.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (multiMode) selected = if (id in selected) selected - id else selected + id
                                    },
                                    onLongClick = if (multiMode || !dragEnabled) {
                                        { onMultiSelectChange(true); selected = selected + id }
                                    } else null
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (multiMode) {
                                Checkbox(checked = id in selected, onCheckedChange = { checked ->
                                    selected = if (checked) selected + id else selected - id
                                }, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(4.dp))
                            } else if (dragEnabled) {
                                Icon(Icons.Default.DragHandle, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                            }
                            TagDot(twc.tag.name, twc.tag.color, size = 20.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(twc.tag.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text("${twc.bookCount} 本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            var menuOpen by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.MoreVert, null)
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; renameTarget = twc; renameText = twc.tag.name })
                                    DropdownMenuItem(text = { Text("改色") }, onClick = { menuOpen = false; colorTarget = twc })
                                    DropdownMenuItem(text = { Text("隐藏") }, onClick = {
                                        menuOpen = false
                                        scope.launch { withContext(Dispatchers.IO) { dao.setTagHidden(id, true) } }
                                    })
                                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = {
                                        menuOpen = false; deleteTargets = listOf(twc)
                                    })
                                }
                            }
                        }
                    }
                }
            }

            if (hiddenShown.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("已隐藏的标签", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                hiddenShown.forEach { twc ->
                    val id = twc.tag.id
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TagDot(twc.tag.name, twc.tag.color, size = 20.dp, alpha = 0.5f)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(twc.tag.name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${twc.bookCount} 本 · 已隐藏", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { dao.setTagHidden(id, false) } }
                            }) { Text("恢复显示") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(72.dp))
        }

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

    renameTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名标签") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
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

    if (showMergePicker) {
        val candidates = tags.filter { it.tag.id !in selected && !it.tag.isHidden }
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
