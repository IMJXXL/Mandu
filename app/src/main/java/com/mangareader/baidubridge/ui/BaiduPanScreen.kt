package com.mangareader.baidubridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mangareader.baidubridge.BaiduBridges
import com.mangareader.baidubridge.api.BaiduApiClient
import com.mangareader.baidubridge.auth.BaiduLoginFlow
import com.mangareader.baidubridge.auth.BaiduSessionStore
import com.mangareader.baidubridge.browser.BaiduDirService
import com.mangareader.baidubridge.data.BaiduCacheManager
import com.mangareader.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BaiduPanScreen(
    onBack: () -> Unit,
    onOpenBook: ((Long, String?, Int) -> Unit)? = null,
) {
    val context = BaiduBridges.context
    val sessionStore = remember { BaiduSessionStore(context) }
    val cacheManager = remember { BaiduCacheManager(context) }
    val loginFlow = remember { BaiduLoginFlow(sessionStore) }
    val apiClient = remember { BaiduApiClient(sessionStore = sessionStore, loginFlow = loginFlow) }
    val dirService = remember { BaiduDirService(apiClient, cacheManager) }
    val scope = rememberCoroutineScope()
    val dao = remember { BaiduBridges.database.mangaDao() }

    var isLoggedIn by remember { mutableStateOf(sessionStore.isValid()) }
    var loginChecking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isLoggedIn) {
            loginChecking = true
            val accountInfo = apiClient.getAccountInfo()
            if (accountInfo == null) {
                sessionStore.clear()
                isLoggedIn = false
            } else if (accountInfo.nickname.isNotEmpty()) {
                sessionStore.updateNickname(accountInfo.nickname)
            }
            loginChecking = false
        }
    }

    if (isLoggedIn) {
        BaiduBrowserScreen(
            dirService = dirService,
            onOpenBook = { path, name ->
                val baiduPath = "baidu:$path:$name"
                scope.launch {
                    val bookId = withContext(Dispatchers.IO) {
                        val existing = dao.getBookByPath(baiduPath)
                        existing?.id ?: dao.insertBookWithStableId(Book(
                            title = name,
                            filePath = baiduPath,
                            source = "baidu",
                            isHidden = true,
                            coverPath = "cv:baidu:$path",
                        ))
                    }
                    onOpenBook?.invoke(bookId, baiduPath, -1)
                }
            },
            onAddToShelf = { path, name ->
                val baiduPath = "baidu:$path:$name"
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val existing = dao.getBookByPath(baiduPath)
                        if (existing != null) {
                            dao.setHidden(existing.id, false)
                        } else {
                            dao.insertBookWithStableId(Book(
                                title = name,
                                filePath = baiduPath,
                                source = "baidu",
                                isHidden = false,
                                coverPath = "cv:baidu:$path",
                            ))
                        }
                    }
                    android.widget.Toast.makeText(context, "已加入书架", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onBack = onBack
        )
    } else {
        BaiduLoginScreen(
            loginFlow = loginFlow,
            onLoginSuccess = { isLoggedIn = true }
        )
    }
}