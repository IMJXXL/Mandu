package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.Bookshelf
import com.mangareader.data.MangaDao
import com.mangareader.data.ShelfSortMode
import com.mangareader.data.Tag
import com.mangareader.data.TagLink
import com.mangareader.data.TagSortMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun sortBookshelves(list: List<Bookshelf>, mode: ShelfSortMode): List<Bookshelf> = when (mode) {
    ShelfSortMode.BY_NAME -> list.sortedBy { it.name }
    ShelfSortMode.BY_CREATED -> list.sortedByDescending { it.createdAt }
    ShelfSortMode.MANUAL -> list.sortedBy { it.sortOrder }
}

private fun sortTags(list: List<Tag>, mode: TagSortMode): List<Tag> = when (mode) {
    TagSortMode.BY_NAME -> list.sortedBy { it.name }
    TagSortMode.BY_CREATED -> list.sortedBy { it.id }
    TagSortMode.MANUAL -> list.sortedBy { it.sortOrder }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfTagManageScreen(onBack: () -> Unit, dao: MangaDao, scope: CoroutineScope) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("书架与标签管理") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp)) {
                    ShelfManageSection(dao, scope)
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(16.dp))
                    TagManageSection(dao, scope)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShelfManageSection(dao: MangaDao, scope: CoroutineScope) {
    var bookshelves by remember { mutableStateOf(listOf<Bookshelf>()) }
    var sortMode by remember { mutableStateOf(ShelfSortMode.BY_CREATED) }
    var editShelf by remember { mutableStateOf<Bookshelf?>(null) }
    var editName by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf<Bookshelf?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { dao.getAllBookshelves().collect { bookshelves = sortBookshelves(it, sortMode) } }
    val filtered = if (query.isNotEmpty()) bookshelves.filter { it.name.contains(query) } else bookshelves
    Text("书架", fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索书架") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfSortMode.entries.forEach { m ->
            FilterChip(selected = sortMode == m, onClick = { sortMode = m; scope.launch { withContext(Dispatchers.IO) { bookshelves.forEach { dao.updateBookshelfSortMode(it.id, m.name) } } } }, label = { Text(m.label, fontSize = 12.sp) })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (filtered.isEmpty()) {
        Text("还没有书架", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filtered.forEachIndexed { index, shelf ->
                Surface(
                    onClick = { editShelf = shelf; editName = shelf.name },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.graphicsLayer { if (draggedIndex == index) { translationX = dragOffsetX; translationY = dragOffsetY; shadowElevation = 8f } }.pointerInput(index, filtered.size) {
                        if (sortMode == ShelfSortMode.MANUAL) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedIndex = index },
                                onDragEnd = {
                                    if (draggedIndex >= 0 && draggedIndex < bookshelves.size) {
                                        val targetIndex = (draggedIndex + (dragOffsetX / 120f).toInt() + (dragOffsetY / 48f).toInt() * 3).coerceIn(0, bookshelves.size - 1)
                                        if (targetIndex != draggedIndex) {
                                            val mutable = bookshelves.toMutableList()
                                            val item = mutable.removeAt(draggedIndex)
                                            mutable.add(targetIndex.coerceAtMost(mutable.size), item)
                                            bookshelves = mutable
                                            scope.launch { withContext(Dispatchers.IO) { mutable.forEachIndexed { i, s -> dao.updateBookshelfSort(s.id, i) } } }
                                        }
                                    }
                                    draggedIndex = -1; dragOffsetX = 0f; dragOffsetY = 0f
                                },
                                onDragCancel = { draggedIndex = -1; dragOffsetX = 0f; dragOffsetY = 0f },
                                onDrag = { change, dragAmount -> change.consume(); dragOffsetX += dragAmount.x; dragOffsetY += dragAmount.y }
                            )
                        }
                    }
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (sortMode == ShelfSortMode.MANUAL) { Icon(Icons.Default.DragHandle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)) }
                        Icon(Icons.Default.Book, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(shelf.name, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { showCreate = true }) { Text("+ 新建书架", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
    if (editShelf != null) {
        AlertDialog(onDismissRequest = { editShelf = null }, title = { Text("编辑书架") },
            text = { Column { OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); TextButton(onClick = { showDelete = editShelf }) { Text("删除书架", color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(onClick = { if (editName.isNotBlank()) { scope.launch { withContext(Dispatchers.IO) { dao.updateBookshelf(editShelf!!.copy(name = editName.trim())) }; editShelf = null } } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editShelf = null }) { Text("取消") } })
    }
    if (showDelete != null) {
        AlertDialog(onDismissRequest = { showDelete = null }, title = { Text("删除书架") }, text = { Text("删除书架「${showDelete!!.name}」？书架内的漫画不受影响。") },
            confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { dao.deleteBookshelf(showDelete!!) }; showDelete = null; editShelf = null } }) { Text("确定", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDelete = null }) { Text("取消") } })
    }
    if (showCreate) {
        AlertDialog(onDismissRequest = { showCreate = false }, title = { Text("新建书架") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("书架名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { scope.launch { withContext(Dispatchers.IO) { val maxOrder = bookshelves.maxOfOrNull { it.sortOrder } ?: 0; dao.insertBookshelf(Bookshelf(name = newName.trim(), sortOrder = maxOrder + 1)) }; newName = ""; showCreate = false } } }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showCreate = false; newName = "" }) { Text("取消") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagManageSection(dao: MangaDao, scope: CoroutineScope) {
    var tags by remember { mutableStateOf(listOf<Tag>()) }
    var sortMode by remember { mutableStateOf(TagSortMode.BY_CREATED) }
    var editTag by remember { mutableStateOf<Tag?>(null) }
    var editName by remember { mutableStateOf("") }
    var editColor by remember { mutableStateOf("#FF7C8CF8") }
    var showDelete by remember { mutableStateOf<Tag?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf("#FF7C8CF8") }
    var query by remember { mutableStateOf("") }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val palette = listOf(0xFF7C8CF8, 0xFFE57373, 0xFFFFB74D, 0xFF81C784, 0xFF4DD0E1, 0xFFBA68C8, 0xFFF06292, 0xFFA1887F, 0xFF90A4AE)
    LaunchedEffect(Unit) { dao.getAllTags().collect { tags = sortTags(it, sortMode) } }
    val filtered = if (query.isNotEmpty()) tags.filter { it.name.contains(query) } else tags
    Text("标签", fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索标签") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TagSortMode.entries.forEach { m ->
            FilterChip(selected = sortMode == m, onClick = { sortMode = m; scope.launch { withContext(Dispatchers.IO) { tags.forEach { dao.updateTagSortMode(it.id, m.name) } } } }, label = { Text(m.label, fontSize = 12.sp) })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (filtered.isEmpty()) {
        Text("还没有标签", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filtered.forEachIndexed { index, tag ->
                Surface(
                    onClick = { editTag = tag; editName = tag.name; editColor = String.format("#%08X", tag.color) },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.graphicsLayer { if (draggedIndex == index) { translationX = dragOffsetX; translationY = dragOffsetY; shadowElevation = 8f } }.pointerInput(index, filtered.size) {
                        if (sortMode == TagSortMode.MANUAL) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedIndex = index },
                                onDragEnd = {
                                    if (draggedIndex >= 0 && draggedIndex < tags.size) {
                                        val targetIndex = (draggedIndex + (dragOffsetX / 120f).toInt() + (dragOffsetY / 48f).toInt() * 3).coerceIn(0, tags.size - 1)
                                        if (targetIndex != draggedIndex) {
                                            val mutable = tags.toMutableList()
                                            val item = mutable.removeAt(draggedIndex)
                                            mutable.add(targetIndex.coerceAtMost(mutable.size), item)
                                            tags = mutable
                                            scope.launch { withContext(Dispatchers.IO) { mutable.forEachIndexed { i, t -> dao.updateTagSort(t.id, i) } } }
                                        }
                                    }
                                    draggedIndex = -1; dragOffsetX = 0f; dragOffsetY = 0f
                                },
                                onDragCancel = { draggedIndex = -1; dragOffsetX = 0f; dragOffsetY = 0f },
                                onDrag = { change, dragAmount -> change.consume(); dragOffsetX += dragAmount.x; dragOffsetY += dragAmount.y }
                            )
                        }
                    }
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (sortMode == TagSortMode.MANUAL) { Icon(Icons.Default.DragHandle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)) }
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(tag.color)))
                        Spacer(Modifier.width(6.dp))
                        Text(tag.name, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { showCreate = true }) { Text("+ 新建标签", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
    if (editTag != null) {
        val parsedColor = runCatching { android.graphics.Color.parseColor(editColor) }.getOrNull()
        AlertDialog(onDismissRequest = { editTag = null }, title = { Text("编辑标签") },
            text = {
                Column {
                    OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = editColor, onValueChange = { editColor = it }, label = { Text("颜色(hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (editColor.isNotEmpty() && parsedColor == null) Text("色值非法", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        palette.forEach { c -> Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).clickable { editColor = String.format("#%08X", c) }) }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showDelete = editTag }) { Text("删除标签", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { if (editName.isNotBlank() && parsedColor != null) { scope.launch { withContext(Dispatchers.IO) { dao.updateTag(editTag!!.copy(name = editName.trim(), color = parsedColor.toLong() and 0xFFFFFFFFL)) }; editTag = null } } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editTag = null }) { Text("取消") } })
    }
    if (showDelete != null) {
        AlertDialog(onDismissRequest = { showDelete = null }, title = { Text("删除标签") }, text = { Text("删除标签「${showDelete!!.name}」？已打标签的书不受影响。") },
            confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { dao.deleteTag(showDelete!!) }; showDelete = null; editTag = null } }) { Text("确定", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDelete = null }) { Text("取消") } })
    }
    if (showCreate) {
        val parsedColor = runCatching { android.graphics.Color.parseColor(newColor) }.getOrNull()
        AlertDialog(onDismissRequest = { showCreate = false }, title = { Text("新建标签") },
            text = {
                Column {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newColor, onValueChange = { newColor = it }, label = { Text("颜色(hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (newColor.isNotEmpty() && parsedColor == null) Text("色值非法", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        palette.forEach { c -> Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).clickable { newColor = String.format("#%08X", c) }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank() && parsedColor != null) { scope.launch { withContext(Dispatchers.IO) { dao.insertTag(Tag(name = newName.trim(), color = parsedColor.toLong() and 0xFFFFFFFFL)) }; newName = ""; newColor = "#FF7C8CF8"; showCreate = false } } }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showCreate = false; newName = ""; newColor = "#FF7C8CF8" }) { Text("取消") } })
    }
}