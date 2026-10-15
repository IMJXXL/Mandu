package com.mangareader.quarkbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mangareader.data.Book
import com.mangareader.quarkbridge.QuarkBridges
import com.mangareader.quarkbridge.api.QuarkApiClient
import com.mangareader.quarkbridge.auth.QuarkLoginFlow
import com.mangareader.quarkbridge.auth.QuarkSessionStore
import com.mangareader.quarkbridge.browser.QuarkDirService
import com.mangareader.quarkbridge.data.QuarkCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun QuarkPanScreen(
    onBack: () -> Unit,
    onOpenBook: ((Long, String?, Int) -> Unit)? = null,
) {
    val context = QuarkBridges.context
    val sessionStore = remember { QuarkSessionStore(context) }
    val cacheManager = remember { QuarkCacheManager(context) }
    val apiClient = remember { QuarkApiClient(sessionStore = sessionStore) }
    val loginFlow = remember { QuarkLoginFlow(apiClient, sessionStore) }
    val dirService = remember { QuarkDirService(apiClient, cacheManager) }
    val scope = rememberCoroutineScope()
    val dao = remember { QuarkBridges.database.mangaDao() }

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
        QuarkBrowserScreen(
            dirService = dirService,
            onOpenBook = { fid, name ->
                val quarkPath = "quark:$fid:$name"
                scope.launch {
                    val bookId = withContext(Dispatchers.IO) {
                        val existing = dao.getBookByPath(quarkPath)
                        existing?.id ?: dao.insertBookWithStableId(Book(
                            title = name,
                            filePath = quarkPath,
                            source = "quark",
                            isHidden = true,
                            coverPath = "cv:quark:$fid",
                        ))
                    }
                    onOpenBook?.invoke(bookId, quarkPath, -1)
                }
            },
            onAddToShelf = { fid, name ->
                val quarkPath = "quark:$fid:$name"
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val existing = dao.getBookByPath(quarkPath)
                        if (existing != null) {
                            dao.setHidden(existing.id, false)
                        } else {
                            dao.insertBookWithStableId(Book(
                                title = name,
                                filePath = quarkPath,
                            source = "quark",
                            isHidden = false,
                            coverPath = "cv:quark:$fid",
                            ))
                        }
                    }
                    android.widget.Toast.makeText(context, "已加入书架", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onBack = onBack
        )
    } else {
        QuarkLoginScreen(
            loginFlow = loginFlow,
            onLoginSuccess = { isLoggedIn = true }
        )
    }
}