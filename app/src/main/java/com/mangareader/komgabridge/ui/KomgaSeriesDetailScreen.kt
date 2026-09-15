package com.mangareader.komgabridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.komgabridge.KomgaBridges
import com.mangareader.komgabridge.api.KomgaApiClient
import com.mangareader.komgabridge.api.KomgaHttpClient
import com.mangareader.komgabridge.api.model.BookDto
import com.mangareader.komgabridge.api.model.SeriesDto
import com.mangareader.komgabridge.data.KomgaConnectionStore
import com.mangareader.komgabridge.reader.KomgaPaths
import com.mangareader.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomgaSeriesDetailScreen(
    seriesId: String,
    serverId: String,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { KomgaConnectionStore(ctx) }
    val connection = remember { store.getConnection(serverId) }

    if (connection == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("系列详情") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("服务器不存在") }
        }
        return
    }

    val apiClient = remember {
        KomgaApiClient(connection, KomgaHttpClient.client(connection.allowSelfSignedCert))
    }

    var series by remember { mutableStateOf<SeriesDto?>(null) }
    var books by remember { mutableStateOf<List<BookDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(seriesId) {
        loading = true
        error = null
        try {
            val s = withContext(Dispatchers.IO) { apiClient.getSeriesDetail(seriesId) }
            series = s
            val result = withContext(Dispatchers.IO) { apiClient.getSeriesBooks(seriesId, page = 0, size = 200) }
            books = result.content
            withContext(Dispatchers.IO) {
                val dao = KomgaBridges.database.mangaDao()
                var inserted = 0
                var skippedEpub = 0
                for (book in result.content) {
                    if (book.media.mediaProfile == "EPUB" && book.media.pagesCount <= 1) {
                        skippedEpub++
                        continue
                    }
                    val filePath = KomgaPaths.bookPath(serverId, book.id)
                    val existing = dao.getKomgaBookByPath(filePath)
                    val coverPath = "cv:komga:${serverId}:${book.id}"
                    if (existing == null) {
                        val title = book.metadata.title.ifEmpty { book.name }
                        val newId = dao.insertBook(Book(
                            title = title,
                            filePath = filePath,
                            source = "komga",
                            totalPages = book.media.pagesCount,
                            coverPath = coverPath,
                        ))
                        android.util.Log.i("KomgaShelf", "insert id=$newId title=$title path=$filePath cover=$coverPath")
                        if (newId > 0) inserted++
                    } else if (existing.coverPath != coverPath) {
                        dao.updateBookCoverPath(existing.id, coverPath)
                    }
                }
                android.util.Log.i("KomgaShelf", "done total=${result.content.size} inserted=$inserted skippedEpub=$skippedEpub server=$serverId")
            }
        } catch (t: Throwable) {
            error = t.message ?: "failed"
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(series?.metadata?.title?.ifEmpty { series?.name ?: "" } ?: "系列详情") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        error?.let { err ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onBack) { Text("返回") }
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                val s = series ?: return@item
                Column(Modifier.padding(16.dp)) {
                    Text(s.metadata.title.ifEmpty { s.name }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (s.metadata.summary.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.metadata.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.metadata.authors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.metadata.authors.joinToString(", ") { it.name }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.metadata.status.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("状态: ${s.metadata.status}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.metadata.tags.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("标签: ${s.metadata.tags.joinToString(", ")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
            items(books) { book ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable {
                        scope.launch {
                            try {
                                val bookId = withContext(Dispatchers.IO) {
                                    val filePath = KomgaPaths.bookPath(serverId, book.id)
                                    val dao = KomgaBridges.database.mangaDao()
                                    val existing = dao.getKomgaBookByPath(filePath)
                                    if (existing != null) existing.id
                                    else {
                                        val title = book.metadata.title.ifEmpty { book.name }
                                        dao.insertBook(Book(
                                            title = title,
                                            filePath = filePath,
                                            source = "komga",
                                            totalPages = book.media.pagesCount,
                                            coverPath = "cv:komga:${serverId}:${book.id}",
                                        ))
                                    }
                                }
                                onOpenBook(bookId)
                            } catch (t: Throwable) {
                                android.widget.Toast.makeText(ctx, t.message ?: "打开失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(book.metadata.title.ifEmpty { book.name }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("第 ${book.number} 卷 - ${book.media.pagesCount} 页", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            book.readProgress?.let { p ->
                                if (p.completed) {
                                    Text("已读完", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                } else if (p.page > 0) {
                                    Text("读到 ${p.page}/${book.media.pagesCount}", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}