package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mangareader.data.Bookshelf
import com.mangareader.data.ShelfSortMode
import com.mangareader.data.Tag
import com.mangareader.data.TagSortMode
import kotlin.math.roundToInt

private fun sortBookshelves(list: List<Bookshelf>, mode: ShelfSortMode): List<Bookshelf> = when (mode) {
    ShelfSortMode.BY_NAME -> list.sortedBy { it.name }
    ShelfSortMode.BY_CREATED -> list.sortedByDescending { it.createdAt }
    ShelfSortMode.MANUAL -> list.sortedBy { it.sortOrder }
}

private fun sortTags(list: List<Tag>, mode: TagSortMode): List<Tag> = when (mode) {
    TagSortMode.BY_NAME -> list.sortedBy { it.name }
    TagSortMode.BY_CREATED -> list.sortedBy { it.id }
    TagSortMode.MANUAL -> list.sortedBy { it.sortOrder }
}

@OptIn(ExperimentalLayoutApi::class)
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
    onClearAll: () -> Unit,
    anchorBounds: Rect
) {
    if (!show) return
    var query by remember { mutableStateOf("") }
    val filteredShelves = if (query.isNotEmpty()) bookshelves.filter { it.name.contains(query) } else bookshelves
    val filteredTags = if (query.isNotEmpty()) tags.filter { it.name.contains(query) } else tags
    val shelfMode = runCatching { ShelfSortMode.valueOf(bookshelves.firstOrNull()?.sortMode ?: "BY_CREATED") }.getOrDefault(ShelfSortMode.BY_CREATED)
    val tagMode = runCatching { TagSortMode.valueOf(tags.firstOrNull()?.sortMode ?: "BY_CREATED") }.getOrDefault(TagSortMode.BY_CREATED)
    val sortedShelves = sortBookshelves(filteredShelves, shelfMode)
    val sortedTags = sortTags(filteredTags, tagMode)
    val density = LocalDensity.current
    val endPad = with(density) { (LocalConfiguration.current.screenWidthDp.dp.toPx() - anchorBounds.right).roundToInt().toDp() } + 4.dp
    val topPad = with(density) { anchorBounds.bottom.roundToInt().toDp() } + 8.dp
    Popup(alignment = Alignment.TopStart, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize().padding(top = topPad, end = endPad, bottom = 8.dp)) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxHeight().width(320.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("筛选", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onClearAll) { Text("一键清除") }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索书架与标签") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(16.dp))
                        Text("书架", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sortedShelves.forEach { shelf ->
                                FilterChip(selected = shelf.id in selectedShelfIds, onClick = { onShelfChange(if (shelf.id in selectedShelfIds) selectedShelfIds - shelf.id else selectedShelfIds + shelf.id) }, label = { Text(shelf.name, fontSize = 13.sp) }, leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Book, null, Modifier.size(14.dp)) })
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("标签", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sortedTags.forEach { tag ->
                                FilterChip(selected = tag.id in selectedTagIds, onClick = { onTagChange(if (tag.id in selectedTagIds) selectedTagIds - tag.id else selectedTagIds + tag.id) }, label = { Text(tag.name, fontSize = 13.sp) }, leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(tag.color))) })
                            }
                        }
                    }
                }
            }
        }

}
