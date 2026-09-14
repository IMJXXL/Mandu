package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.MangaDao
import com.mangareader.data.Tag
import com.mangareader.data.TagLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPickerDialog(onDismiss: () -> Unit, dao: MangaDao, scope: CoroutineScope, bookIds: Set<Long>, onConfirm: () -> Unit) {
    var allTags by remember { mutableStateOf(listOf<Tag>()) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf("#FF7C8CF8") }
    val palette = listOf(0xFF7C8CF8, 0xFFE57373, 0xFFFFB74D, 0xFF81C784, 0xFF4DD0E1, 0xFFBA68C8, 0xFFF06292, 0xFFA1887F, 0xFF90A4AE)
    LaunchedEffect(Unit) {
        allTags = withContext(Dispatchers.IO) { dao.getAllTagsList() }
        val existing = withContext(Dispatchers.IO) { dao.getTagsForBooks(bookIds.toList()) }
        selected = if (bookIds.size == 1) existing.map { it.tagId }.toSet() else existing.groupBy { it.tagId }.filter { it.value.size == bookIds.size }.keys
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bookIds.size == 1) "编辑标签" else "添加标签") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                allTags.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (t.id in selected) selected - t.id else selected + t.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = t.id in selected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.color)))
                        Spacer(Modifier.width(6.dp))
                        Text(t.name, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showCreate = true }) { Text("+ 新建标签") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    if (bookIds.size == 1) {
                        dao.removeAllTagsFromBook(bookIds.first())
                        selected.forEach { dao.insertTagLink(TagLink(tagId = it, bookId = bookIds.first())) }
                    } else {
                        val existing = dao.getTagsForBooks(bookIds.toList()).groupBy { it.bookId }
                        bookIds.forEach { bid ->
                            val cur = existing[bid]?.map { it.tagId }?.toSet() ?: emptySet()
                            (selected - cur).forEach { dao.insertTagLink(TagLink(tagId = it, bookId = bid)) }
                            (cur - selected).forEach { dao.deleteTagLink(TagLink(tagId = it, bookId = bid)) }
                        }
                    }
                    onConfirm()
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank() && parsedColor != null && allTags.none { it.name == newName.trim() }) { scope.launch(Dispatchers.IO) { val newId = dao.insertTag(Tag(name = newName.trim(), color = parsedColor.toLong() and 0xFFFFFFFFL)); allTags = dao.getAllTagsList(); selected = selected + newId; showCreate = false; newName = ""; newColor = "#FF7C8CF8" } } }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } })
    }
}
