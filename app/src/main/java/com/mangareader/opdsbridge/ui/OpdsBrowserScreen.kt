package com.mangareader.opdsbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.opdsbridge.OpdsConstants
import com.mangareader.opdsbridge.api.OpdsApiClient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.mangareader.opdsbridge.OpdsBridges
import com.mangareader.opdsbridge.api.OpdsHttpClient
import com.mangareader.opdsbridge.cover.OpdsEntryLinkCache
import com.mangareader.opdsbridge.data.OpdsConnectionStore
import com.mangareader.opdsbridge.parser.OpdsEntry
import com.mangareader.opdsbridge.parser.OpdsFeed
import com.mangareader.opdsbridge.parser.OpdsFeedJsonParser
import com.mangareader.opdsbridge.parser.OpdsFeedParser
import com.mangareader.opdsbridge.parser.OpenSearchParser
import com.mangareader.opdsbridge.reader.OpdsBookRouter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsBrowserScreen(serverId: String, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { OpdsConnectionStore(ctx) }
    val connection = remember { store.getConnection(serverId) }

    if (connection == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("OPDS") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("服务器不存在")
            }
        }
        return
    }

    val client = remember { OpdsHttpClient.client(connection.allowSelfSignedCert) }
    val apiClient = remember { OpdsApiClient(connection, client) }

    var navStack by remember { mutableStateOf(ArrayDeque<String>().apply { addLast(connection.baseUrl) }) }
    var entries by remember { mutableStateOf<List<OpdsEntry>>(emptyList()) }
    var nextLink by remember { mutableStateOf<String?>(null) }
    var searchLink by remember { mutableStateOf<String?>(null) }
    var facets by remember { mutableStateOf<List<com.mangareader.opdsbridge.parser.OpdsFacet>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchTemplate by remember { mutableStateOf<String?>(null) }
    var openingBook by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }

    val currentUrl = navStack.last()

    suspend fun loadFeed(url: String, append: Boolean) {
        if (append) loadingMore = true else loading = true
        error = null
        try {
            val resp = withContext(Dispatchers.IO) { apiClient.fetchFeed(url) }
            val bytes = withContext(Dispatchers.IO) { resp.bodyAsBytesSafe() }
            val ct = resp.contentType()
            val feed = if (ct != null && ct.match(ContentType.Application.Json)) {
                OpdsFeedJsonParser.parse(bytes.toString(Charsets.UTF_8), url)
            } else {
                OpdsFeedParser.parse(ByteArrayInputStream(bytes), url)
            }
            for (entry in feed.entries) {
                OpdsEntryLinkCache.put(serverId, entry)
            }
            if (append) {
                entries = entries + feed.entries
            } else {
                entries = feed.entries
            }
            nextLink = feed.nextLink
            searchLink = feed.searchLink
            facets = feed.facets
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        } finally {
            if (append) loadingMore = false else loading = false
        }
    }

    LaunchedEffect(currentUrl) {
        loadFeed(currentUrl, append = false)
    }

    suspend fun loadSearchTemplate() {
        val sLink = searchLink ?: return
        try {
            val resp = withContext(Dispatchers.IO) { apiClient.fetchFeed(sLink) }
            val bytes = withContext(Dispatchers.IO) { resp.bodyAsBytesSafe() }
            searchTemplate = OpenSearchParser.parseTemplate(ByteArrayInputStream(bytes))
        } catch (_: Throwable) {
            searchTemplate = null
        }
    }

    BackHandler {
        when {
            openError != null -> openError = null
            searchActive -> { searchActive = false; searchQuery = ""; searchTemplate = null }
            navStack.size > 1 -> {
                navStack.removeLast()
                navStack = ArrayDeque(navStack)
            }
            else -> onBack()
        }
    }

    if (openingBook) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("正在下载...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    openError?.let { msg ->
        AlertDialog(
            onDismissRequest = { openError = null },
            title = { Text("打开失败") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { openError = null }) { Text("确定") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(connection.name.ifEmpty { "OPDS" }) },
                navigationIcon = { IconButton(onClick = {
                    if (searchActive) { searchActive = false; searchQuery = ""; searchTemplate = null }
                    else if (navStack.size > 1) { navStack.removeLast(); navStack = ArrayDeque(navStack) }
                    else onBack()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
                actions = {
                    if (searchLink != null) {
                        IconButton(onClick = {
                            searchActive = !searchActive
                            if (searchActive && searchTemplate == null) {
                                scope.launch { loadSearchTemplate() }
                            }
                        }) { Icon(Icons.Default.Search, "search") }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                val template = searchTemplate
                                if (template != null) {
                                    val searchUrl = OpenSearchParser.buildSearchUrl(template, searchQuery)
                                    navStack.addLast(searchUrl)
                                    navStack = ArrayDeque(navStack)
                                    searchActive = false
                                }
                            }) { Icon(Icons.Default.Search, "go") }
                        }
                    },
                )
            }

            if (facets.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(facets) { facet ->
                        FilterChip(
                            selected = facet.isActive,
                            onClick = {
                                navStack.addLast(facet.link)
                                navStack = ArrayDeque(navStack)
                            },
                            label = { Text(facet.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { scope.launch { loadFeed(currentUrl, append = false) } }) { Text("重试") }
                    }
                }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(listState) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .collect { lastVisible ->
                            val total = entries.size
                            if (nextLink != null && !loadingMore && !loading && lastVisible >= total - 3) {
                                val next = nextLink!!
                                if (next != currentUrl) {
                                    loadingMore = true
                                    val prevNext = nextLink
                                    nextLink = null
                                    try {
                                        val resp = withContext(Dispatchers.IO) { apiClient.fetchFeed(next) }
                                        val bytes = withContext(Dispatchers.IO) { resp.bodyAsBytesSafe() }
                                        val ct = resp.contentType()
                                        val feed = if (ct != null && ct.match(ContentType.Application.Json)) {
                                            OpdsFeedJsonParser.parse(bytes.toString(Charsets.UTF_8), next)
                                        } else {
                                            OpdsFeedParser.parse(ByteArrayInputStream(bytes), next)
                                        }
                                        for (entry in feed.entries) {
                                            OpdsEntryLinkCache.put(serverId, entry)
                                        }
                                        entries = entries + feed.entries
                                        nextLink = feed.nextLink
                                    } catch (_: Throwable) {
                                        nextLink = prevNext
                                    } finally {
                                        loadingMore = false
                                    }
                                }
                            }
                        }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    items(entries) { entry ->
                        OpdsEntryRow(
                            entry = entry,
                            serverId = serverId,
                            onClick = {
                                if (entry.isNavigation && entry.subsectionLink != null) {
                                    navStack.addLast(entry.subsectionLink!!)
                                    navStack = ArrayDeque(navStack)
                                } else if (entry.isAcquisition) {
                                    scope.launch {
                                        openingBook = true
                                        try {
                                            val bookPath = com.mangareader.opdsbridge.reader.OpdsPaths.bookPath(serverId, entry.id)
                                            val result = withContext(Dispatchers.IO) { OpdsBookRouter.openOpdsBook(bookPath) }
                                            when (result) {
                                                is OpdsBookRouter.OpenResult.Ready -> onOpenBook(result.bookId)
                                                is OpdsBookRouter.OpenResult.Failed -> openError = result.message
                                            }
                                        } finally {
                                            openingBook = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                    if (loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpdsEntryRow(
    entry: OpdsEntry,
    serverId: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverPath = "${OpdsConstants.COVER_SCHEME}$serverId:${entry.id}"
        if (entry.thumbnailLink != null) {
            AsyncImage(
                model = coverPath,
                contentDescription = null,
                modifier = Modifier.size(56.dp, 80.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.size(56.dp, 80.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (entry.isNavigation) Icons.Default.Folder else Icons.Default.MenuBook,
                    null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.summary.isNotEmpty()) {
                Text(
                    entry.summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (entry.isAcquisition) {
                Text(
                    entry.acquisitionLinks.joinToString(", ") { it.mimeType.substringAfter("/") } ,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private suspend fun HttpResponse.bodyAsBytesSafe(): ByteArray {
    return try {
        bodyAsBytes()
    } catch (t: Throwable) {
        ByteArray(0)
    }
}