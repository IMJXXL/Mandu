package com.mangareader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.viewer.NaturalComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReaderItemGrid(
    items: List<ArchiveEngine.Item>,
    engine: ArchiveEngine,
    tap: (ArchiveEngine.Item) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var sortAsc by remember { mutableStateOf(true) }
    var cols by remember { mutableIntStateOf(prefs.getInt("item_grid_cols", 3)) }
    LaunchedEffect(Unit) {
        val saved = prefs.getInt("item_grid_cols", 3)
        if (saved != cols) cols = saved
    }
    val sorted = remember(items, sortAsc) {
        val base = items.sortedWith(compareByDescending<ArchiveEngine.Item> { it.dir }.thenComparing({ it.name }, NaturalComparator))
        if (sortAsc) base else base.reversed()
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${sorted.size} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { sortAsc = !sortAsc }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (sortAsc) "正序" else "倒序", fontSize = 12.sp)
                }
                TextButton(onClick = { cols = when(cols) { 2 -> 3; 3 -> 4; else -> 2 }; prefs.edit().putInt("item_grid_cols", cols).apply() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.GridView, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${cols}列", fontSize = 12.sp)
                }
            }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(cols), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            gridItemsIndexed(sorted, key = { i, e -> "${e.auth}|${e.doc}_$i" }) { _, e -> ReaderItemCard(e, engine, { tap(e) }) }
        }
    }
}

@Composable
private fun ReaderItemCard(item: ArchiveEngine.Item, engine: ArchiveEngine, tap: () -> Unit) {
    var cover by remember(item.doc, item.subPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.doc, item.subPath) {
        cover = null
        withContext(Dispatchers.IO) { try { cover = engine.loadCoverForItem(item) } catch (_: Exception) { cover = null } }
    }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = tap).shadow(4.dp, RoundedCornerShape(12.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
            cover?.let { Image(bitmap = it.asImageBitmap(), contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
            val badgeColor = if (item.dir) Color(0xFFFFB74D) else Color(0xFF7C8CF8)
            Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = badgeColor.copy(alpha = 0.9f)) {
                Text(if (item.dir) "文件夹" else "压缩包", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
            }
        }
        Box(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(item.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFFCCCCCC))
        }
    }
}
