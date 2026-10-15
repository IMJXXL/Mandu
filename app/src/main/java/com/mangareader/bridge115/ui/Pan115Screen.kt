package com.mangareader.bridge115.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mangareader.bridge115.Bridges115
import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.Api115Client
import com.mangareader.bridge115.api.Web115Client
import com.mangareader.bridge115.auth.Login115Flow
import com.mangareader.bridge115.auth.ModeStore115
import com.mangareader.bridge115.auth.SessionStore115
import com.mangareader.bridge115.browser.DirService115
import com.mangareader.bridge115.data.CacheManager115
import com.mangareader.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Pan115Screen(
    onBack: () -> Unit,
    onOpenBook: ((Long, String?, Int) -> Unit)? = null,
) {
    val context = Bridges115.context
    val sessionStore = remember { SessionStore115(context) }
    val modeStore = remember { ModeStore115(context) }
    val cacheManager = remember { CacheManager115(context) }
    val loginFlow = remember { Login115Flow(sessionStore) }
    val dao = remember { Bridges115.database.mangaDao() }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(modeStore.getMode()) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var loginChecking by remember { mutableStateOf(false) }

    val apiClient = remember { Api115Client(sessionStore = sessionStore, loginFlow = loginFlow) }
    val webClient = remember { Web115Client(sessionStore = sessionStore) }
    val dirService = remember { DirService115(apiClient = apiClient, webClient = webClient, cache = cacheManager) }

    LaunchedEffect(mode) {
        if (mode != null) {
            isLoggedIn = if (mode == Constants115.MODE_OFFICIAL) sessionStore.isValidOfficial() else sessionStore.isValidWeb()
            if (isLoggedIn && mode == Constants115.MODE_OFFICIAL) {
                loginChecking = true
                val name = apiClient.getAccountInfo()
                if (name == null) {
                    sessionStore.clearOfficial()
                    isLoggedIn = false
                } else if (name.isNotEmpty()) {
                    sessionStore.updateOfficialNickname(name)
                }
                loginChecking = false
            }
        }
    }

    when {
        mode == null -> {
            ModeSelect115Screen(onModeSelected = { selectedMode ->
                modeStore.setMode(selectedMode)
                mode = selectedMode
            })
        }
        !isLoggedIn -> {
            Login115Screen(
                loginFlow = loginFlow,
                mode = mode!!,
                onLoginSuccess = { isLoggedIn = true }
            )
        }
        else -> {
            val currentMode = mode!!
            val source = if (currentMode == Constants115.MODE_OFFICIAL) "115_official" else "115_web"
            Browser115Screen(
                dirService = dirService,
                mode = currentMode,
                onOpenBook = { fid, pickCode, name ->
                    val path115 = "115:$currentMode:$pickCode:$name"
                    scope.launch {
                        val bookId = withContext(Dispatchers.IO) {
                            val existing = dao.getBookByPath(path115)
                            existing?.id ?: dao.insertBookWithStableId(Book(
                                title = name,
                                filePath = path115,
                                source = source,
                                isHidden = true,
                                coverPath = "cv:115:$currentMode:$pickCode",
                            ))
                        }
                        onOpenBook?.invoke(bookId, path115, -1)
                    }
                },
                onAddToShelf = { fid, pickCode, name ->
                    val path115 = "115:$currentMode:$pickCode:$name"
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val existing = dao.getBookByPath(path115)
                            if (existing != null) {
                                dao.setHidden(existing.id, false)
                            } else {
                                dao.insertBookWithStableId(Book(
                                    title = name,
                                    filePath = path115,
                                    source = source,
                                    isHidden = false,
                                    coverPath = "cv:115:$currentMode:$pickCode",
                                ))
                            }
                        }
                        android.widget.Toast.makeText(context, "已加入书架", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onBack = onBack
            )
        }
    }
}