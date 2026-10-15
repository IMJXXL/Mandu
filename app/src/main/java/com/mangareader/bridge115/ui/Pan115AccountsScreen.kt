package com.mangareader.bridge115.ui

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ehviewer.core.database.model.CloudAccountEntity
import com.mangareader.cloud.CloudAccountMigrator
import com.mangareader.cloud.CloudAccountRepository
import com.mangareader.cloud.ui.AccountListScaffold
import com.mangareader.bridge115.auth.Session115Official
import com.mangareader.bridge115.auth.Session115Web
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun Pan115AccountsScreen(
    onBack: () -> Unit,
    onOpenBook: ((Long, String?, Int) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by CloudAccountRepository.listFlow("115").collectAsState(initial = emptyList())

    var showLogin by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        CloudAccountMigrator.ensureMigrated()
    }

    if (showLogin) {
        Login115Screen(
            onBack = { showLogin = false },
            onLoginSuccess = { mode, official, web ->
                showLogin = false
                scope.launch(Dispatchers.IO) {
                    val displayName = if (mode == "official") official?.nickname?.ifBlank { "115账号" } ?: "115账号" else "115账号"
                    val id = CloudAccountRepository.add115Account(displayName, mode, official, web)
                    CloudAccountRepository.sync115ToLegacy(id)
                }
            },
        )
        return
    }

    if (browsing) {
        Pan115Screen(onBack = { browsing = false }, onOpenBook = onOpenBook)
        return
    }

    AccountListScaffold(
        title = "115网盘账号",
        accounts = accounts,
        onBack = onBack,
        onAdd = { showLogin = true },
        onBrowse = { account ->
            scope.launch(Dispatchers.IO) {
                CloudAccountRepository.switchActive("115", account.id)
                CloudAccountRepository.sync115ToLegacy(account.id)
                browsing = true
            }
        },
        onTest = { account ->
            scope.launch {
                busyId = account.id
                val ok = CloudAccountRepository.test115(account.id)
                Toast.makeText(ctx, if (ok) "凭证正常" else "凭证已失效", Toast.LENGTH_SHORT).show()
                busyId = null
            }
        },
        onDelete = { account ->
            scope.launch(Dispatchers.IO) { CloudAccountRepository.delete(account) }
        },
        onSwitchActive = { account ->
            scope.launch(Dispatchers.IO) {
                CloudAccountRepository.switchActive("115", account.id)
                CloudAccountRepository.sync115ToLegacy(account.id)
            }
        },
        onEditName = { id, name ->
            scope.launch(Dispatchers.IO) { CloudAccountRepository.updateDisplayName(id, name) }
        },
        busyId = busyId,
        extraInfo = { account ->
            if (account.mode115 == "official") "官方模式" else if (account.mode115 == "web") "Web模式" else null
        },
    )
}