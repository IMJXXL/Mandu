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

@Composable
fun TagPickerDialog(onDismiss: () -> Unit, dao: MangaDao, scope: CoroutineScope, bookIds: Set<Long>, onConfirm: () -> Unit) {
    var allTags by remember { mutableStateOf(listOf<Tag>()) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
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
}