package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.Bookshelf
import com.mangareader.data.MangaDao
import com.mangareader.data.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val tagPalette = listOf(
    0xFF7C8CF8, 0xFFE57373, 0xFFFFB74D, 0xFF81C784,
    0xFF4DD0E1, 0xFFBA68C8, 0xFFF06292, 0xFFA1887F,
    0xFF90A4AE, 0xFFD32F2F, 0xFF388E3C, 0xFFFBC02D
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryManageSheet(
    onDismiss: () -> Unit,
    dao: MangaDao,
    scope: CoroutineScope,
    bookshelves: List<Bookshelf>,
    onBookshelfChange: () -> Unit,
    sort: SortMode,
    onSortChange: (SortMode) -> Unit,
    sortAsc: Boolean,
    onSortAscChange: (Boolean) -> Unit,
    isTablet: Boolean,
    manualCols: Int?,
    onColsChange: (Int?) -> Unit,
    onOpenHistory: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetMaxWidth = 600.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        TabRow(selectedTabIndex = tab) {
            listOf("书架", "标签", "显示").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> ShelfManageTab(dao, scope, bookshelves, onBookshelfChange)
            1 -> TagManageTab(dao, scope)
            2 -> DisplayManageTab(sort, onSortChange, sortAsc, onSortAscChange, isTablet, manualCols, onColsChange, onOpenHistory)
        }
    }
}

@Composable
private fun ShelfManageTab(
    dao: MangaDao,
    scope: CoroutineScope,
    bookshelves: List<Bookshelf>,
    onBookshelfChange: () -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editingShelf by remember { mutableStateOf<Bookshelf?>(null) }
    var editName by remember { mutableStateOf("") }
    var deletingShelf by remember { mutableStateOf<Bookshelf?>(null) }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(bookshelves) { shelf ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Book, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(shelf.name, Modifier.weight(1f), fontSize = 15.sp)
                    IconButton(onClick = { editingShelf = shelf; editName = shelf.name }) {
                        Icon(Icons.Default.Edit, "编辑", Modifier.size(18.dp))
                    }
                    IconButton(onClick = { deletingShelf = shelf }) {
                        Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { showCreate = true; newName = "" }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建书架")
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建书架") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("名称") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            dao.insertBookshelf(Bookshelf(name = newName.trim()))
                            withContext(Dispatchers.Main) { showCreate = false; onBookshelfChange() }
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } }
        )
    }

    editingShelf?.let { shelf ->
        AlertDialog(
            onDismissRequest = { editingShelf = null },
            title = { Text("重命名书架") },
            text = { OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("名称") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (editName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            dao.updateBookshelf(shelf.copy(name = editName.trim()))
                            withContext(Dispatchers.Main) { editingShelf = null; onBookshelfChange() }
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingShelf = null }) { Text("取消") } }
        )
    }

    deletingShelf?.let { shelf ->
        AlertDialog(
            onDismissRequest = { deletingShelf = null },
            title = { Text("删除书架") },
            text = { Text("删除「${shelf.name}」？书架内的书不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        dao.deleteBookshelf(shelf)
                        withContext(Dispatchers.Main) { deletingShelf = null; onBookshelfChange() }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingShelf = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TagManageTab(dao: MangaDao, scope: CoroutineScope) {
    var tags by remember { mutableStateOf(listOf<Tag>()) }
    LaunchedEffect(Unit) { dao.getAllTags().collect { tags = it } }

    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(tagPalette[0]) }
    var editingTag by remember { mutableStateOf<Tag?>(null) }
    var editName by remember { mutableStateOf("") }
    var editColor by remember { mutableStateOf(tagPalette[0]) }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }
    var nameError by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tags) { tag ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(tag.color)))
                    Spacer(Modifier.width(12.dp))
                    Text(tag.name, Modifier.weight(1f), fontSize = 15.sp)
                    IconButton(onClick = { editingTag = tag; editName = tag.name; editColor = tag.color; nameError = false }) {
                        Icon(Icons.Default.Edit, "编辑", Modifier.size(18.dp))
                    }
                    IconButton(onClick = { deletingTag = tag }) {
                        Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { showCreate = true; newName = ""; newColor = tagPalette[0]; nameError = false }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建标签")
            }
        }
    }

    if (showCreate) {
        TagEditDialog(
            title = "新建标签",
            name = newName,
            onNameChange = { newName = it; nameError = false },
            color = newColor,
            onColorChange = { newColor = it },
            nameError = nameError,
            onConfirm = {
                if (newName.isBlank()) { nameError = true; return@TagEditDialog }
                if (tags.any { it.name == newName.trim() }) { nameError = true; return@TagEditDialog }
                scope.launch(Dispatchers.IO) {
                    dao.insertTag(Tag(name = newName.trim(), color = newColor))
                    withContext(Dispatchers.Main) { showCreate = false }
                }
            },
            onDismiss = { showCreate = false }
        )
    }

    editingTag?.let { tag ->
        TagEditDialog(
            title = "编辑标签",
            name = editName,
            onNameChange = { editName = it; nameError = false },
            color = editColor,
            onColorChange = { editColor = it },
            nameError = nameError,
            onConfirm = {
                if (editName.isBlank()) { nameError = true; return@TagEditDialog }
                if (tags.any { it.id != tag.id && it.name == editName.trim() }) { nameError = true; return@TagEditDialog }
                scope.launch(Dispatchers.IO) {
                    dao.insertTag(tag.copy(name = editName.trim(), color = editColor))
                    withContext(Dispatchers.Main) { editingTag = null }
                }
            },
            onDismiss = { editingTag = null }
        )
    }

    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text("删除标签") },
            text = { Text("删除标签「${tag.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        dao.deleteTag(tag)
                        withContext(Dispatchers.Main) { deletingTag = null }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingTag = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TagEditDialog(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    color: Long,
    onColorChange: (Long) -> Unit,
    nameError: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("名称") },
                    isError = nameError,
                    supportingText = if (nameError) { { Text("名称为空或已存在") } } else null,
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Text("颜色", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tagPalette.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable { onColorChange(c) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (c == color) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DisplayManageTab(
    sort: SortMode,
    onSortChange: (SortMode) -> Unit,
    sortAsc: Boolean,
    onSortAscChange: (Boolean) -> Unit,
    isTablet: Boolean,
    manualCols: Int?,
    onColsChange: (Int?) -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("排序方式", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        SortMode.entries.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { onSortChange(s) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sort == s, onClick = { onSortChange(s) })
                Spacer(Modifier.width(8.dp))
                Text(s.label, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("升序排列", Modifier.weight(1f), fontSize = 14.sp)
            Switch(checked = sortAsc, onCheckedChange = { onSortAscChange(it) })
        }
        Spacer(Modifier.height(8.dp))
        Text("布局列数", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        val colsCandidates = if (isTablet) listOf(1, 3, 4, 6) else listOf(1, 2, 3, 4, 5)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colsCandidates.forEach { c ->
                FilterChip(
                    selected = manualCols == c,
                    onClick = { onColsChange(if (manualCols == c) null else c) },
                    label = { Text(if (c == 1) "列表" else "$c 列") }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenHistory) {
            Icon(Icons.Default.History, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("阅读历史")
        }
    }
}