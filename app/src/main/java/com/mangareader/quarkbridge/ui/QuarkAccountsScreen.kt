package com.mangareader.quarkbridge.ui

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ehviewer.core.database.model.CloudAccountEntity
import com.mangareader.cloud.CloudAccountMigrator
import com.mangareader.cloud.CloudAccountRepository
import com.mangareader.cloud.ui.AccountListScaffold
import com.mangareader.quarkbridge.auth.QuarkCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun QuarkAccountsScreen(
    onBack: () -> Unit,
    onOpenBook: ((Long, String?, Int) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by CloudAccountRepository.listFlow("夸克").collectAsState(initial = emptyList())

    var showLogin by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        CloudAccountMigrator.ensureMigrated()
    }

    if (showLogin) {
        QuarkLoginScreen(
            onBack = { showLogin = false },
            onLoginSuccess = { credentials ->
                showLogin = false
                scope.launch(Dispatchers.IO) {
                    val id = CloudAccountRepository.addQuarkAccount(credentials.nickname.ifBlank { "夸克账号" }, credentials)
                    CloudAccountRepository.syncQuarkToLegacy(id)
                }
            },
        )
        return
    }

    if (browsing) {
        QuarkPanScreen(onBack = { browsing = false }, onOpenBook = onOpenBook)
        return
    }

    AccountListScaffold(
        title = "夸克网盘账号",
        accounts = accounts,
        onBack = onBack,
        onAdd = { showLogin = true },
        onBrowse = { account ->
            scope.launch(Dispatchers.IO) {
                CloudAccountRepository.switchActive("夸克", account.id)
                CloudAccountRepository.syncQuarkToLegacy(account.id)
                browsing = true
            }
        },
        onTest = { account ->
            scope.launch {
                busyId = account.id
                val ok = CloudAccountRepository.testQuark(account.id)
                Toast.makeText(ctx, if (ok) "凭证正常" else "凭证已失效", Toast.LENGTH_SHORT).show()
                busyId = null
            }
        },
        onDelete = { account ->
            scope.launch(Dispatchers.IO) { CloudAccountRepository.delete(account) }
        },
        onSwitchActive = { account ->
            scope.launch(Dispatchers.IO) {
                CloudAccountRepository.switchActive("夸克", account.id)
                CloudAccountRepository.syncQuarkToLegacy(account.id)
            }
        },
        onEditName = { id, name ->
            scope.launch(Dispatchers.IO) { CloudAccountRepository.updateDisplayName(id, name) }
        },
        busyId = busyId,
    )
}