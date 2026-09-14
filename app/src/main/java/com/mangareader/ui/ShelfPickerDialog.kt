package com.mangareader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.Bookshelf
import com.mangareader.data.BookshelfItem
import com.mangareader.data.MangaDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShelfPickerDialog(onDismiss: () -> Unit, dao: MangaDao, scope: CoroutineScope, bookIds: Set<Long>, onConfirm: () -> Unit) {
    var allShelves by remember { mutableStateOf(listOf<Bookshelf>()) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        allShelves = withContext(Dispatchers.IO) { dao.getAllBookshelvesList() }
        val existing = withContext(Dispatchers.IO) { bookIds.flatMap { bid -> dao.getShelvesForBook(bid).map { it.bookshelfId } } }
        selected = if (bookIds.size == 1) existing.toSet() else existing.groupBy { it }.filter { it.value.size == bookIds.size }.keys
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到书架") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                allShelves.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (s.id in selected) selected - s.id else selected + s.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = s.id in selected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.name, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showCreate = true }) { Text("+ 新建书架") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    bookIds.forEach { bid ->
                        val cur = dao.getShelvesForBook(bid).map { it.bookshelfId }.toSet()
                        (selected - cur).forEach { dao.insertBookshelfItem(BookshelfItem(bookshelfId = it, bookId = bid)) }
                        (cur - selected).forEach { dao.removeBookFromShelf(it, bid) }
                    }
                    onConfirm()
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (showCreate) {
        AlertDialog(onDismissRequest = { showCreate = false }, title = { Text("新建书架") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { scope.launch(Dispatchers.IO) { val newId = dao.insertBookshelf(Bookshelf(name = newName.trim())); allShelves = dao.getAllBookshelvesList(); selected = selected + newId; showCreate = false; newName = "" } } }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } })
    }
}