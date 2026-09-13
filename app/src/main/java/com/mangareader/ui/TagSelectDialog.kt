package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

@Composable
fun TagSelectDialog(
    onDismiss: () -> Unit,
    dao: MangaDao,
    scope: CoroutineScope,
    bookIds: Set<Long>,
    onConfirm: () -> Unit = {}
) {
    var allTags by remember { mutableStateOf(listOf<Tag>()) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val isSingle = bookIds.size == 1

    LaunchedEffect(Unit) {
        val tags = withContext(Dispatchers.IO) { dao.getAllTagsList() }
        allTags = tags
        if (bookIds.isNotEmpty()) {
            val bookTags = withContext(Dispatchers.IO) { dao.getTagsForBooks(bookIds.toList()) }
            if (isSingle) {
                selected = bookTags.map { it.tagId }.toSet()
            } else {
                val grouped = bookTags.groupBy { it.tagId }
                selected = grouped.filter { it.value.size == bookIds.size }.keys
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSingle) "编辑标签" else "添加标签") },
        text = {
            Column {
                if (allTags.isEmpty()) {
                    Text("暂无标签，请先在管理中创建")
                } else {
                    allTags.forEach { tag ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = tag.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + tag.id else selected - tag.id
                                }
                            )
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(tag.color)))
                            Spacer(Modifier.width(8.dp))
                            Text(tag.name, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    if (isSingle) {
                        val bid = bookIds.first()
                        dao.removeAllTagsFromBook(bid)
                        selected.forEach { tid -> dao.insertTagLink(TagLink(tagId = tid, bookId = bid)) }
                    } else {
                        val existing = dao.getTagsForBooks(bookIds.toList())
                        val existingByBook = existing.groupBy { it.bookId }
                        for (bid in bookIds) {
                            val cur = existingByBook[bid]?.map { it.tagId }?.toSet() ?: emptySet()
                            val toAdd = selected - cur
                            val toRemove = cur - selected
                            toRemove.forEach { tid -> dao.deleteTagLinkByIds(tid, bid) }
                            toAdd.forEach { tid -> dao.insertTagLink(TagLink(tagId = tid, bookId = bid)) }
                        }
                    }
                    withContext(Dispatchers.Main) { onConfirm(); onDismiss() }
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}