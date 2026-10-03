package com.mangareader.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import com.mangareader.data.Bookshelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShelfManageScreen(onBack: () -> Unit) {

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
    // ★ 书架拖动排序（2026-10-03，mihon 同款 sh.calvin.reorderable 方案）：
    //   ReorderableItem + animateItem——拖动时其余书架实时动画让位、列表边缘自动滚动；
    //   旧实现（graphicsLayer 平移+松手才跳位）废弃。dragList = 拖动期快照，DB 全表落盘。
    val listState = rememberLazyListState()
    val dragList = remember { mutableStateListOf<Bookshelf>() }
    val canDragState = remember { mutableStateOf(true) } // 搜索过滤时禁拖（过滤序≠全序，拖了会错位）；值随 shelfQuery 刷新
    // ★ 闪退修复（2026-10-03 实测"拖动闪退"）：库回调的 from/to.index 是整个 LazyColumn 的
    //   绝对索引——本列表前面还有搜索框、提示文字两个静态 item{}（0/1），第一个书架的索引是 2。
    //   直接拿 from.index 当 dragList 下标会越界 IndexOutOfBoundsException（书架少时必崩）。
    //   改按 key 映射回 dragList 下标，头部 item 增减都不受影响。
    val saveOrderMutex = remember { Mutex() }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        if (!canDragState.value) return@rememberReorderableLazyListState
        val f = dragList.indexOfFirst { it.id == from.key }
        val t = dragList.indexOfFirst { it.id == to.key }
        if (f < 0 || t < 0 || f == t) return@rememberReorderableLazyListState
        dragList.add(t, dragList.removeAt(f))
        // 快照在主线程先取好，IO 侧用互斥串行落盘——防止连续让位时多个全表重写并发互相踩
        val snapshot = dragList.toList()
        scope.launch(Dispatchers.IO) {
            saveOrderMutex.withLock {
                snapshot.forEachIndexed { i, s -> dao.updateBookshelfSort(s.id, i) }
            }
        }
    }
    LaunchedEffect(bookshelves) {
        if (!reorderableState.isAnyItemDragging) {
            dragList.clear()
            dragList.addAll(bookshelves)
        }
    }
    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedShelves by remember { mutableStateOf(setOf<Long>()) }
    var showSettings by remember { mutableStateOf(false) }
    var showMergePicker by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var manageTab by remember { mutableStateOf(0) }
    var showCreateTag by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
    var createTagColor by remember { mutableStateOf<Long?>(null) }
    var showTagColorPicker by remember { mutableStateOf(false) }
    var shelfQuery by remember { mutableStateOf("") }
    var tagMultiSelect by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var manageLayout by remember { mutableStateOf(prefs.getInt("manage_layout", 0)) }
    LaunchedEffect(shelfQuery) { canDragState.value = shelfQuery.isBlank() }

    BackHandler {
        if (showSettings) showSettings = false
        else if (isMultiSelect) { isMultiSelect = false; selectedShelves = emptySet() }
        else if (tagMultiSelect) tagMultiSelect = false
        else onBack()
    }

    LaunchedEffect(Unit) { dao.getAllBookshelves().collect { bookshelves = it } }

    Scaffold(
        topBar = {
            if (isMultiSelect) TopAppBar(
                title = { Text("已选 ${selectedShelves.size} 个") },
                navigationIcon = { IconButton(onClick = { isMultiSelect = false; selectedShelves = emptySet() }) { Icon(Icons.Default.Close, "取消选择") } },
                actions = {
                    IconButton(onClick = { selectedShelves = if (selectedShelves.isEmpty()) bookshelves.map { it.id }.toSet() else emptySet() }) {
                        Icon(Icons.Default.SelectAll, if (selectedShelves.isEmpty()) "全选" else "取消全选")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) else TopAppBar(
                title = { Text("书架管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = {
                        manageLayout = 1 - manageLayout
                        prefs.edit().putInt("manage_layout", manageLayout).apply()
                    }) { Icon(if (manageLayout == 1) Icons.Default.ViewList else Icons.Default.GridView, if (manageLayout == 1) "列表" else "网格") }
                    if (manageTab == 0) {
                        IconButton(onClick = { isMultiSelect = true }) { Icon(Icons.Default.Edit, "多选") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
                        IconButton(onClick = { showCreateShelf = true }) { Icon(Icons.Default.Add, "新建书架") }
                    } else {
                        IconButton(onClick = { tagMultiSelect = true }) { Icon(Icons.Default.Edit, "多选") }
                        IconButton(onClick = { showCreateTag = true }) { Icon(Icons.Default.Add, "新建标签") }
                    }
                }
            )
        },
        bottomBar = {
            if (isMultiSelect && selectedShelves.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { showMergePicker = true }, enabled = selectedShelves.size >= 2) { Text("合并") }
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.setBookshelvesHiddenBatch(selectedShelves.toList(), true) }
                                isMultiSelect = false; selectedShelves = emptySet()
                            }
                        }) { Text("隐藏") }
                        TextButton(onClick = { showBatchDeleteConfirm = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = manageTab) {
                Tab(selected = manageTab == 0, onClick = { manageTab = 0; tagMultiSelect = false }) { Text("书架", Modifier.padding(vertical = 12.dp), fontSize = 15.sp) }
                Tab(selected = manageTab == 1, onClick = { manageTab = 1; isMultiSelect = false; selectedShelves = emptySet() }) { Text("标签", Modifier.padding(vertical = 12.dp), fontSize = 15.sp) }
            }
            if (manageTab == 0) {
            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
            item {
            OutlinedTextField(
                value = shelfQuery,
                onValueChange = { shelfQuery = it },
                placeholder = { Text("搜索书架…", fontSize = 14.sp) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            }
            item {
            Text(if (canDragState.value) "长按拖动排序" else "搜索中（拖动排序暂停）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 显示列表：拖动期读快照 dragList（onMove 实时换位 + animateItem 动画），搜索时过滤
            val visibleShelfList = if (shelfQuery.isBlank()) dragList.toList() else dragList.filter { it.name.contains(shelfQuery, ignoreCase = true) }
            if (visibleShelfList.isEmpty()) {
                item {
                    Text(if (bookshelves.isEmpty()) "还没有书架" else "没有匹配的书架", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (manageLayout == 1) {
                item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    visibleShelfList.forEach { shelf ->
                        var chipMenu by remember { mutableStateOf(false) }
                        Box {
                            FilterChip(selected = shelf.id in selectedShelves, onClick = {
                                if (isMultiSelect) selectedShelves = if (shelf.id in selectedShelves) selectedShelves - shelf.id else selectedShelves + shelf.id
                                else chipMenu = true
                            }, label = { Text(shelf.name, fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Default.Book, null, Modifier.size(15.dp)) })
                            DropdownMenu(expanded = chipMenu, onDismissRequest = { chipMenu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { chipMenu = false; editShelf = shelf; editShelfName = shelf.name })
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { chipMenu = false; showDeleteShelf = shelf })
                            }
                        }
                    }
                }
                }
            } else items(visibleShelfList, key = { it.id }) { shelf ->
                ReorderableItem(reorderableState, shelf.id) { dragging ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (canDragState.value) Modifier.longPressDraggableHandle() else Modifier)
                                .clickable { if (isMultiSelect) selectedShelves = if (shelf.id in selectedShelves) selectedShelves - shelf.id else selectedShelves + shelf.id }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isMultiSelect) {
                                Checkbox(checked = shelf.id in selectedShelves, onCheckedChange = { selectedShelves = if (it) selectedShelves + shelf.id else selectedShelves - shelf.id })
                                Spacer(Modifier.width(4.dp))
                            }
                            if (!isMultiSelect) {
                                Icon(Icons.Default.DragHandle, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.Default.Book, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(shelf.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (!isMultiSelect) {
                                IconButton(onClick = { editShelf = shelf; editShelfName = shelf.name }) { Icon(Icons.Default.Edit, "编辑", modifier = Modifier.size(20.dp)) }
                                IconButton(onClick = { showDeleteShelf = shelf }) { Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            }
            } else {
                TagManageList(dao = dao, scope = scope, multiSelect = tagMultiSelect, onMultiSelectChange = { tagMultiSelect = it }, layoutGrid = manageLayout == 1)
            }
        }
    }

    if (showDeleteShelf != null) {
        val s = showDeleteShelf!!
        AlertDialog(onDismissRequest = { showDeleteShelf = null }, title = { Text("删除书架？") },
            text = { Text("书架内的漫画不受影响，仅移除该分组。\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { dao.deleteBookshelf(s) }; showDeleteShelf = null }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteShelf = null }) { Text("取消") } })
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(onDismissRequest = { showBatchDeleteConfirm = false }, title = { Text("删除 ${selectedShelves.size} 个书架？") },
            text = { Text("书架内的漫画不受影响，仅移除分组。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { dao.deleteBookshelvesBatch(selectedShelves.toList()) }
                        isMultiSelect = false; selectedShelves = emptySet()
                        showBatchDeleteConfirm = false
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("取消") } })
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

    if (showMergePicker) {
        MergeTargetPickerDialog(
            allShelves = bookshelves,
            excludeIds = selectedShelves,
            onConfirm = { target ->
                showMergePicker = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val targetId = when (target) {
                            is MergeTarget.Existing -> target.shelfId
                            is MergeTarget.New -> {
                                val maxOrder = bookshelves.maxOfOrNull { it.sortOrder } ?: 0
                                dao.insertBookshelf(Bookshelf(name = target.name, sortOrder = maxOrder + 1))
                            }
                        }
                        dao.mergeBookshelves(targetId, selectedShelves.toList())
                    }
                    isMultiSelect = false; selectedShelves = emptySet()
                }
            },
            onDismiss = { showMergePicker = false }
        )
    }

    if (showSettings) {
        ShelfManageSettingsScreen(onBack = { showSettings = false })
    }

    if (showCreateTag) {
        AlertDialog(onDismissRequest = { showCreateTag = false; createTagColor = null }, title = { Text("新建标签") },
            text = {
                Column {
                    OutlinedTextField(value = newTagName, onValueChange = { newTagName = it }, label = { Text("标签名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TagDot(newTagName.ifBlank { "标签" }, createTagColor ?: TagColors.AUTO_SENTINEL, size = 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (createTagColor == null) "颜色：按名字自动配色" else "颜色：自定义", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showTagColorPicker = true }) { Text("选颜色") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTagName.isNotBlank()) {
                        val color = createTagColor
                        val name = newTagName.trim()
                        scope.launch {
                            val dup = withContext(Dispatchers.IO) { dao.findTagByName(name) != null }
                            if (dup) {
                                android.widget.Toast.makeText(ctx, "已有同名标签，换个名字或直接使用它", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                withContext(Dispatchers.IO) {
                                    val id = dao.ensureTag(name)
                                    if (id != -1L && color != null) dao.setTagColor(id, color)
                                }
                                newTagName = ""
                                createTagColor = null
                                showCreateTag = false
                            }
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateTag = false; newTagName = ""; createTagColor = null }) { Text("取消") } })
    }

    if (showTagColorPicker) {
        TagColorPickerSheet(
            visible = true,
            tagName = newTagName.ifBlank { "新标签" },
            currentColor = createTagColor ?: TagColors.AUTO_SENTINEL,
            darkTheme = isSystemInDarkTheme(),
            onPick = { createTagColor = it },
            onDismiss = { showTagColorPicker = false },
        )
    }
}
