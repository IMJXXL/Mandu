package com.mangareader.komgabridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.Book
import com.mangareader.komgabridge.KomgaBridges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomgaSeriesVolumesScreen(
    serverId: String,
    seriesId: String,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    BackHandler { onBack() }
    var books by remember { mutableStateOf<List<Book>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId, seriesId) {
        try {
            books = withContext(Dispatchers.IO) {
                KomgaBridges.database.mangaDao().getKomgaSeriesVisibleBooks(serverId, seriesId)
            }
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(books?.firstOrNull()?.seriesName ?: "系列") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
            )
        }
    ) { padding ->
        val list = books
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("本系列暂无可见卷", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list) { b ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("共 ${b.totalPages} 页", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (b.readPages > 0 && b.totalPages > 0) {
                                        if (b.readPages >= b.totalPages) {
                                            Text("已读完", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                        } else {
                                            Text("读到 ${b.readPages}/${b.totalPages}", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                androidx.compose.material3.TextButton(onClick = { onOpenBook(b.id) }) { Text("阅读") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}