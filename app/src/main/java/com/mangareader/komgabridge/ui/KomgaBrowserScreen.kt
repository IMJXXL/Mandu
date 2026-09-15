package com.mangareader.komgabridge.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangareader.komgabridge.KomgaBridges
import com.mangareader.komgabridge.api.KomgaApiClient
import com.mangareader.komgabridge.api.KomgaHttpClient
import com.mangareader.komgabridge.api.model.LibraryDto
import com.mangareader.komgabridge.api.model.PageableDto
import com.mangareader.komgabridge.api.model.SeriesDto
import com.mangareader.komgabridge.data.KomgaConnectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomgaBrowserScreen(serverId: String, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { KomgaConnectionStore(ctx) }
    val connection = remember { store.getConnection(serverId) }

    if (connection == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("Komga") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("服务器不存在")
            }
        }
        return
    }

    var libraries by remember { mutableStateOf<List<LibraryDto>?>(null) }
    var selectedLibrary by remember { mutableStateOf<LibraryDto?>(null) }
    var seriesPage by remember { mutableStateOf(0) }
    var seriesList by remember { mutableStateOf<MutableList<SeriesDto>>(mutableListOf()) }
    var seriesTotalPages by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSeries by remember { mutableStateOf<SeriesDto?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }

    val apiClient = remember {
        KomgaApiClient(connection, KomgaHttpClient.client(connection.allowSelfSignedCert))
    }

    selectedSeries?.let { series ->
        KomgaSeriesDetailScreen(
            seriesId = series.id,
            serverId = serverId,
            onBack = { selectedSeries = null },
            onOpenBook = onOpenBook,
        )
        return
    }

    BackHandler {
        if (searchActive) { searchActive = false; searchQuery = "" }
        else if (selectedLibrary != null) {
            selectedLibrary = null
            seriesList.clear()
            seriesPage = 0
        } else onBack()
    }

    LaunchedEffect(selectedLibrary, searchQuery) {
        if (selectedLibrary == null) {
            loading = true
            error = null
            try {
                libraries = withContext(Dispatchers.IO) { apiClient.getLibraries() }
            } catch (t: Throwable) {
                error = t.message ?: "failed"
            } finally {
                loading = false
            }
        } else {
            seriesList.clear()
            seriesPage = 0
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    if (searchQuery.isNotEmpty()) apiClient.searchSeries(searchQuery, selectedLibrary!!.id)
                    else apiClient.getSeries(libraryId = selectedLibrary!!.id, page = 0)
                }
                seriesList.addAll(result.content)
                seriesTotalPages = result.totalPages
            } catch (t: Throwable) {
                error = t.message ?: "failed"
            } finally {
                loading = false
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= seriesList.size - 3
        }.collect { shouldLoad ->
            if (shouldLoad && !loading && selectedLibrary != null && seriesPage + 1 < seriesTotalPages) {
                loading = true
                seriesPage++
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (searchQuery.isNotEmpty()) apiClient.searchSeries(searchQuery, selectedLibrary!!.id)
                        else apiClient.getSeries(libraryId = selectedLibrary!!.id, page = seriesPage)
                    }
                    seriesList.addAll(result.content)
                } catch (_: Throwable) {
                    seriesPage--
                } finally {
                    loading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            searchActive -> "搜索"
                            selectedLibrary != null -> selectedLibrary!!.name
                            else -> connection.name.ifEmpty { "Komga" }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (searchActive) { searchActive = false; searchQuery = "" }
                        else if (selectedLibrary != null) {
                            selectedLibrary = null
                            seriesList.clear()
                            seriesPage = 0
                        } else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
                },
                actions = {
                    if (selectedLibrary != null && !searchActive) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, "search")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (searchActive && selectedLibrary != null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索系列") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    singleLine = true,
                )
                SeriesListView(
                    seriesList = seriesList,
                    loading = loading,
                    error = error,
                    onSeriesClick = { selectedSeries = it },
                    listState = listState,
                    apiClient = apiClient,
                )
            }
            return@Scaffold
        }

        if (loading && seriesList.isEmpty() && libraries == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        error?.let { err ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        error = null
                        selectedLibrary = null
                        libraries = null
                    }) { Text("重试") }
                }
            }
            return@Scaffold
        }

        if (selectedLibrary == null) {
            val libs = libraries ?: emptyList()
            if (libs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("暂无书库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(libs) { lib ->
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { selectedLibrary = lib },
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(lib.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(20.dp).rotate(180f))
                            }
                        }
                    }
                }
            }
        } else {
            SeriesListView(
                seriesList = seriesList,
                loading = loading,
                error = null,
                onSeriesClick = { selectedSeries = it },
                listState = listState,
                apiClient = apiClient,
            )
        }
    }
}

@Composable
private fun SeriesListView(
    seriesList: List<SeriesDto>,
    loading: Boolean,
    error: String?,
    onSeriesClick: (SeriesDto) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    apiClient: KomgaApiClient,
) {
    if (seriesList.isEmpty() && !loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无系列", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(seriesList) { series ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { onSeriesClick(series) },
            ) {
                Row(Modifier.padding(8.dp)) {
                    KomgaSeriesCover(
                        apiClient = apiClient,
                        seriesId = series.id,
                        modifier = Modifier.size(80.dp, 120.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(series.metadata.title.ifEmpty { series.name }, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (series.booksCount > 0) {
                            Text("${series.booksCount} 卷", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
fun KomgaSeriesCover(
    apiClient: KomgaApiClient,
    seriesId: String,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(seriesId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(seriesId) { mutableStateOf(true) }

    LaunchedEffect(seriesId) {
        loading = true
        try {
            val bytes = withContext(Dispatchers.IO) {
                apiClient.downloadBytes(apiClient.seriesThumbnailUrl(seriesId))
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            bitmap = null
        } finally {
            loading = false
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("?", color = androidx.compose.ui.graphics.Color.Gray)
            }
        }
    }
}

