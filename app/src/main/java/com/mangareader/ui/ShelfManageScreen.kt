package com.mangareader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import com.mangareader.data.Bookshelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfManageScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val dao = remember { app.db.mangaDao() }
    val scope = rememberCoroutineScope()
    var bookshelves by remember { mutableStateOf(listOf<Bookshelf>()) }
    var showDeleteShelf by remember { mutableStateOf<Bookshelf?>(null) }
    var editShelf by remember { mutableStateOf<Bookshelf?>(null) }
    var editShelfName by remember { mutableStateOf("") }
    var showCreateShelf by remember { mutableStateOf(false) }
    var newShelfName by remember { mutableStateOf("") }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val shelfDensity = LocalDensity.current.density
    val shelfItemHeight = 52f

    LaunchedEffect(Unit) { dao.getAllBookshelves().collect { bookshelves = it } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { showCreateShelf = true }) { Text("+ 新建", color = MaterialTheme.colorScheme.primary) }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("长按拖动排序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (bookshelves.isEmpty()) Text("还没有书架", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            bookshelves.forEachIndexed { index, shelf ->
                val maxUp = index * shelfItemHeight
                val maxDown = (bookshelves.size - 1 - index) * shelfItemHeight
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .graphicsLayer {
                            if (draggedIndex == index) {
                                translationY = dragOffset.coerceIn(-maxUp, maxDown) * shelfDensity
                                shadowElevation = 8f
                            }
                        }
                        .pointerInput(index, bookshelves.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedIndex = index },
                                onDragEnd = {
                                    if (draggedIndex >= 0 && draggedIndex < bookshelves.size) {
                                        val finalIndex = (draggedIndex + (dragOffset / shelfItemHeight).toInt()).coerceIn(0, bookshelves.size - 1)
                                        if (finalIndex != draggedIndex) {
                                            val mutable = bookshelves.toMutableList()
                                            val item = mutable.removeAt(draggedIndex)
                                            mutable.add(finalIndex, item)
                                            bookshelves = mutable
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    mutable.forEachIndexed { i, s -> dao.updateBookshelfSort(s.id, i) }
                                                }
                                            }
                                        }
                                    }
                                    draggedIndex = -1; dragOffset = 0f
                                },
                                onDragCancel = { draggedIndex = -1; dragOffset = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val maxUp2 = draggedIndex * shelfItemHeight
                                    val maxDown2 = (bookshelves.size - 1 - draggedIndex) * shelfItemHeight
                                    dragOffset = (dragOffset + dragAmount.y).coerceIn(-maxUp2, maxDown2)
                                }
                            )
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = if (draggedIndex == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DragHandle, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Book, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(shelf.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editShelf = shelf; editShelfName = shelf.name }) { Icon(Icons.Default.Edit, "编辑", modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { showDeleteShelf = shelf }) { Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (showDeleteShelf != null) {
        val s = showDeleteShelf!!
        AlertDialog(onDismissRequest = { showDeleteShelf = null }, title = { Text("删除书架") },
            text = { Text("删除书架「${s.name}」？书架内的漫画不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { dao.deleteBookshelf(s) }; showDeleteShelf = null }
                }) { Text("确定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteShelf = null }) { Text("取消") } })
    }

    if (editShelf != null) {
        AlertDialog(onDismissRequest = { editShelf = null }, title = { Text("编辑书架名称") },
            text = { OutlinedTextField(value = editShelfName, onValueChange = { editShelfName = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (editShelfName.isNotBlank()) {
                        scope.launch { withContext(Dispatchers.IO) { dao.updateBookshelf(editShelf!!.copy(name = editShelfName.trim())) }; editShelf = null }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editShelf = null }) { Text("取消") } })
    }

    if (showCreateShelf) {
        AlertDialog(onDismissRequest = { showCreateShelf = false }, title = { Text("新建书架") },
            text = { OutlinedTextField(value = newShelfName, onValueChange = { newShelfName = it }, label = { Text("书架名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (newShelfName.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val maxOrder = bookshelves.maxOfOrNull { it.sortOrder } ?: 0
                                dao.insertBookshelf(Bookshelf(name = newShelfName.trim(), sortOrder = maxOrder + 1))
                            }
                            newShelfName = ""
                            showCreateShelf = false
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateShelf = false; newShelfName = "" }) { Text("取消") } })
    }
}