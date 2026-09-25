package com.mangareader.komgabridge.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.naturalCompare
import com.mangareader.coil.CoverAnimGateDecoder
import com.mangareader.data.Book
import com.mangareader.komgabridge.KomgaBridges
import com.mangareader.ui.LayoutColumnsResolver
import com.mangareader.viewer.CoverData
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
    val ctx = LocalContext.current
    val configuration = LocalConfiguration.current
    var books by remember { mutableStateOf<List<Book>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortAsc by remember { mutableStateOf(true) }

    val portraitCols by Settings.thumbColumnsPortrait.valueFlow().collectAsState(initial = Settings.thumbColumnsPortrait.value)
    val landscapeCols by Settings.thumbColumnsLandscape.valueFlow().collectAsState(initial = Settings.thumbColumnsLandscape.value)
    val resolvedCols = LayoutColumnsResolver.currentValue(configuration.orientation, portraitCols, landscapeCols)
    val gridCols = LayoutColumnsResolver.toGridCols(resolvedCols)

    LaunchedEffect(serverId, seriesId) {
        try {
            books = withContext(Dispatchers.IO) {
                KomgaBridges.database.mangaDao().getKomgaSeriesVisibleBooks(serverId, seriesId)
            }
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        }
    }

    val continueBook = books
        ?.filter { it.readPages > 0 || (it.lastReadAt != null && it.lastReadAt > 0) }
        ?.filter { it.totalPages == 0 || it.readPages < it.totalPages }
        ?.maxByOrNull { it.lastReadAt ?: 0L }

    val sorted = remember(books, sortAsc) {
        val base = books?.sortedWith { a, b -> naturalCompare(a.title, b.title) } ?: emptyList()
        if (sortAsc) base else base.reversed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(books?.firstOrNull()?.seriesName ?: "系列") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
                actions = {
                    if (continueBook != null) {
                        IconButton(onClick = { onOpenBook(continueBook.id) }) {
                            Icon(Icons.Default.PlayArrow, "继续阅读", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        }
    ) { padding ->
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            books == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            sorted.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("本系列暂无可见卷", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${sorted.size} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { sortAsc = !sortAsc }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (sortAsc) "正序" else "倒序", fontSize = 12.sp)
                    }
                }
                LazyVerticalGrid(
                    columns = gridCols,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(sorted) { b ->
                        val progress = if (b.totalPages > 0) b.readPages.toFloat() / b.totalPages else 0f
                        val isFinished = b.totalPages > 0 && b.readPages >= b.totalPages
                        val isReading = !isFinished && (b.readPages > 0 || (b.lastReadAt != null && b.lastReadAt > 0))
                        val statusText = when {
                            isFinished -> "已读完"
                            isReading -> "阅读中"
                            else -> "未读"
                        }
                        val statusColor = when {
                            isFinished -> Color(0xFF81C784)
                            isReading -> Color(0xFFFFB74D)
                            else -> Color(0xFFE57373)
                        }
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpenBook(b.id) }.shadow(4.dp, RoundedCornerShape(12.dp))) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                val displayCover = b.displayCoverPath
                                if (displayCover != null) {
                                    AsyncImage(
                                        model = remember(displayCover) {
                                            ImageRequest.Builder(ctx)
                                                .data(CoverData(displayCover, dynOn = false))
                                                .decoderFactory(CoverAnimGateDecoder)
                                                .build()
                                        },
                                        contentDescription = b.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
                                }
                                Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
                                Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = statusColor.copy(alpha = 0.9f)) {
                                    Text(statusText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
                                }
                            }
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Text(b.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}
