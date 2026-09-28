package com.mangareader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.TagWithCount

/**
 * 标签选择器底部弹层（design.md §四 / mockup_picker_sheet.png）：
 * - 筛选模式（默认）：点选即时生效，"完成"只是收起
 * - 添加模式（addMode，P4 批量打标签复用）：点选攒 pending，"完成"经 onApply 提交
 * - AND/OR 切换、搜索、热门 Top12、按前缀分组全量
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(
    visible: Boolean,
    allTags: List<TagWithCount>,
    selectedIds: Set<Long>,
    andLogic: Boolean,
    addMode: Boolean = false,
    onToggle: (Long) -> Unit,
    onApply: (Set<Long>) -> Unit = {},
    onLogicChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var query by remember(visible) { mutableStateOf("") }
    var pending by remember(visible) { mutableStateOf(selectedIds) }

    fun toggle(id: Long) {
        if (addMode) {
            pending = if (id in pending) pending - id else pending + id
        } else {
            onToggle(id)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(if (addMode) "添加标签" else "按标签筛选", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (!addMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("匹配", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(selected = andLogic, onClick = { onLogicChange(true) }, label = { Text("满足全部", fontSize = 13.sp) })
                    FilterChip(selected = !andLogic, onClick = { onLogicChange(false) }, label = { Text("满足任一", fontSize = 13.sp) })
                }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索标签…", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            val q = query.trim()
            val filtered = if (q.isEmpty()) allTags else allTags.filter { it.tag.name.contains(q, ignoreCase = true) }
            if (filtered.isEmpty()) {
                Text("没有匹配的标签", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                val groups = remember(filtered) { groupTags(filtered) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (q.isEmpty()) {
                        item {
                            Text("热门", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            val hot = allTags.sortedByDescending { it.bookCount }.take(12)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                hot.forEach { twc ->
                                    TagPickChip(twc, twc.tag.id in (if (addMode) pending else selectedIds)) { toggle(twc.tag.id) }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    groups.forEach { (header, list) ->
                        item {
                            Text(header, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                        }
                        // 两列分块行：组内懒加载，大组不再一次组合全部 chips（审查问题4）
                        list.chunked(2).forEach { pair ->
                            item {
                                Row(Modifier.fillMaxWidth()) {
                                    pair.forEach { twc ->
                                        Box(Modifier.weight(1f)) {
                                            TagPickChip(twc, twc.tag.id in (if (addMode) pending else selectedIds)) { toggle(twc.tag.id) }
                                        }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    onClear()
                    if (addMode) pending = emptySet()
                    query = ""
                }) { Text("清除全部", fontSize = 14.sp) }
                Button(onClick = {
                    if (addMode) onApply(pending)
                    onDismiss()
                }) { Text(if (addMode) "添加" else "完成") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TagPickChip(twc: TagWithCount, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagDot(twc.tag.name, twc.tag.color)
                Spacer(Modifier.width(6.dp))
                Text(twc.tag.name, fontSize = 13.sp)
            }
        }
    )
}

/** 按前缀分组：通用（无冒号）在前，作者:/出版:/自定义字段名 分组（design.md §四） */
internal fun groupTags(tags: List<TagWithCount>): List<Pair<String, List<TagWithCount>>> {
    val groups = LinkedHashMap<String, MutableList<TagWithCount>>()
    for (t in tags) {
        val n = t.tag.name
        val g = when {
            !n.contains(':') -> "通用"
            else -> n.substringBefore(':').ifBlank { "通用" }
        }
        groups.getOrPut(g) { mutableListOf() }.add(t)
    }
    return groups.entries.sortedBy { if (it.key == "通用") "" else it.key }.map { it.key to it.value }
}
