package com.mangareader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.mangareader.data.Bookshelf
import com.mangareader.data.Tag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterPanelSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    bookshelves: List<Bookshelf>,
    tags: List<Tag>,
    selectedShelfIds: Set<Long>,
    selectedTagIds: Set<Long>,
    onShelfChange: (Set<Long>) -> Unit,
    onTagChange: (Set<Long>) -> Unit,
    onClearAll: () -> Unit
) {
    if (!show) return
    var tagQuery by remember { mutableStateOf("") }
    val filteredTags = if (tagQuery.isNotEmpty()) tags.filter { it.name.contains(tagQuery) } else tags
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onDismiss() })
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClearAll) { Text("一键清除") }
                }
                Spacer(Modifier.height(16.dp))
                Text("书架", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                bookshelves.forEach { shelf ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onShelfChange(if (shelf.id in selectedShelfIds) selectedShelfIds - shelf.id else selectedShelfIds + shelf.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = shelf.id in selectedShelfIds, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(shelf.name, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                Text("标签", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                if (tags.size > 8) {
                    OutlinedTextField(value = tagQuery, onValueChange = { tagQuery = it }, label = { Text("搜索标签") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                filteredTags.forEach { tag ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onTagChange(if (tag.id in selectedTagIds) selectedTagIds - tag.id else selectedTagIds + tag.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = tag.id in selectedTagIds, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(tag.color)))
                        Spacer(Modifier.width(6.dp))
                        Text(tag.name, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}