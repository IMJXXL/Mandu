# -*- coding: utf-8 -*-
"""用户反馈批：入口常显+去转手动+新建标签带选色+书架搜索+主题补容器色去粉"""
import re

# ===== 1) 主题：所有 scheme 补 surfaceContainerLow/Medium（中性灰，弹层背景不再漏默认紫粉） =====
p = 'app/src/main/java/com/mangareader/ui/Theme.kt'
s = open(p, encoding='utf-8').read()
lines = s.split('\n')
out = []
mode = None
has_low = False
for ln in lines:
    if 'lightColorScheme(' in ln:
        mode = 'light'; has_low = False
    elif 'darkColorScheme(' in ln:
        mode = 'dark'; has_low = False
    if 'surfaceContainerLow' in ln:
        has_low = True
    out.append(ln)
    if 'surfaceContainerHighest' in ln and not has_low and mode:
        indent = ln[:len(ln) - len(ln.lstrip())]
        if mode == 'light':
            out.append(indent + 'surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF7F7F7), surfaceContainerMedium = androidx.compose.ui.graphics.Color(0xFFEFEFEF),')
        else:
            out.append(indent + 'surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF191919), surfaceContainerMedium = androidx.compose.ui.graphics.Color(0xFF242424),')
open(p, 'w', encoding='utf-8').write('\n'.join(out))
print('theme container colors added:', out.count('surfaceContainerLow ='))

# ===== 2) TagFilterRow：入口常显（只要有标签） =====
p = 'app/src/main/java/com/mangareader/ui/TagFilterRow.kt'
s = open(p, encoding='utf-8').read()
old = '''    val byId = remember(allTags) { allTags.associateBy { it.tag.id } }
    val selected = selectedIds.mapNotNull { byId[it] }
    val recent = recentIds.mapNotNull { byId[it] }.filter { it.tag.id !in selectedIds }.take(5)
    if (selected.isEmpty() && recent.isEmpty()) return'''
new = '''    val byId = remember(allTags) { allTags.associateBy { it.tag.id } }
    val selected = selectedIds.mapNotNull { byId[it] }
    val recent = recentIds.mapNotNull { byId[it] }.filter { it.tag.id !in selectedIds }.take(5)
    // 库里有标签就常显入口（否则首次用户找不到筛选在哪），完全无标签才隐藏整行
    if (allTags.isEmpty()) return'''
assert old in s, 'filterrow anchor'
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print('TagFilterRow entry always visible')

# ===== 3) 去掉"转手动"（概念困惑，重建本就只清自动标签） =====
p = 'app/src/main/java/com/mangareader/ui/Library.kt'
s = open(p, encoding='utf-8').read()
old = '''                        TextButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { xmlTagList.forEach { dao.markTagManual(it.tag.id) } }; refreshBookTags() }
                        }) { Text("全部转为手动（重扫免疫）", fontSize = 11.sp) }
'''
assert old in s, 'detail manual btn'
s = s.replace(old, '', 1)
open(p, 'w', encoding='utf-8').write(s)

p = 'app/src/main/java/com/mangareader/ui/TagManageList.kt'
s = open(p, encoding='utf-8').read()
old = '''                                DropdownMenuItem(text = { Text("转手动（重扫免疫）") }, onClick = {
                                    menuOpen = false
                                    scope.launch { withContext(Dispatchers.IO) { dao.markTagManual(id) } }
                                })
'''
assert old in s, 'manage manual item'
s = s.replace(old, '', 1)
open(p, 'w', encoding='utf-8').write(s)
print('manual-convert removed')

# ===== 4) 新建标签带选色 =====
p = 'app/src/main/java/com/mangareader/ui/ShelfManageScreen.kt'
s = open(p, encoding='utf-8').read()

old = '''    var showCreateTag by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
'''
new = '''    var showCreateTag by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
    var createTagColor by remember { mutableStateOf<Long?>(null) }
    var showTagColorPicker by remember { mutableStateOf(false) }
    var shelfQuery by remember { mutableStateOf("") }
'''
assert old in s, 'create tag state anchor'
s = s.replace(old, new, 1)

# 替换新建标签对话框（带颜色行）+ 拾色器
old2 = '''    if (showCreateTag) {
        AlertDialog(onDismissRequest = { showCreateTag = false }, title = { Text("新建标签") },
            text = { OutlinedTextField(value = newTagName, onValueChange = { newTagName = it }, label = { Text("标签名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (newTagName.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { dao.ensureTag(newTagName.trim()) }
                            newTagName = ""
                            showCreateTag = false
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateTag = false; newTagName = "" }) { Text("取消") } })
    }'''
new2 = '''    if (showCreateTag) {
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
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val id = dao.ensureTag(newTagName.trim())
                                if (id != -1L && color != null) dao.setTagColor(id, color)
                            }
                            newTagName = ""
                            createTagColor = null
                            showCreateTag = false
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
    }'''
assert old2 in s, 'create tag dialog anchor'
s = s.replace(old2, new2, 1)

# 5) 书架 Tab 搜索
old3 = '''            Text("长按拖动排序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (bookshelves.isEmpty()) Text("还没有书架", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            bookshelves.forEachIndexed { index, shelf ->'''
new3 = '''            OutlinedTextField(
                value = shelfQuery,
                onValueChange = { shelfQuery = it },
                placeholder = { Text("搜索书架…", fontSize = 14.sp) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(if (shelfQuery.isBlank()) "长按拖动排序" else "搜索中（拖动排序暂停）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val visibleShelfList = if (shelfQuery.isBlank()) bookshelves else bookshelves.filter { it.name.contains(shelfQuery, ignoreCase = true) }
            if (visibleShelfList.isEmpty()) Text(if (bookshelves.isEmpty()) "还没有书架" else "没有匹配的书架", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            visibleShelfList.forEachIndexed { index, shelf ->'''
assert old3 in s, 'shelf search anchor'
s = s.replace(old3, new3, 1)

# 拖动在搜索时禁用（keys 与条件）
old4 = '''                        .pointerInput(index, bookshelves.size, isMultiSelect) {
                            if (!isMultiSelect) {'''
new4 = '''                        .pointerInput(index, bookshelves.size, isMultiSelect, shelfQuery) {
                            if (!isMultiSelect && shelfQuery.isBlank()) {'''
assert old4 in s, 'drag guard anchor'
s = s.replace(old4, new4, 1)

# import isSystemInDarkTheme
s = s.replace('import androidx.compose.ui.platform.LocalContext\n',
              'import androidx.compose.foundation.isSystemInDarkTheme\nimport androidx.compose.ui.platform.LocalContext\n', 1)
open(p, 'w', encoding='utf-8').write(s)
print('shelf search + create tag color done')
