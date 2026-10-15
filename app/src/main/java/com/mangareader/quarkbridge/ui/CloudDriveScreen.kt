package com.mangareader.quarkbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mangareader.R
import com.mangareader.cloud.CloudAccountMigrator
import com.mangareader.cloud.CloudAccountRepository


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CloudDriveScreen(
    onBack: () -> Unit,
    onOpenBook: ((Long, String?, Int) -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        CloudAccountMigrator.ensureMigrated()
    }

    val quarkAccounts by CloudAccountRepository.listFlow("夸克").collectAsState(initial = emptyList())
    val baiduAccounts by CloudAccountRepository.listFlow("百度").collectAsState(initial = emptyList())
    val accounts115 by CloudAccountRepository.listFlow("115").collectAsState(initial = emptyList())

    var showQuarkAccounts by remember { mutableStateOf(false) }
    var showBaiduAccounts by remember { mutableStateOf(false) }
    var show115Accounts by remember { mutableStateOf(false) }

    if (showQuarkAccounts) {
        QuarkAccountsScreen(onBack = { showQuarkAccounts = false }, onOpenBook = onOpenBook)
        return
    }
    if (showBaiduAccounts) {
        com.mangareader.baidubridge.ui.BaiduAccountsScreen(onBack = { showBaiduAccounts = false }, onOpenBook = onOpenBook)
        return
    }
    if (show115Accounts) {
        com.mangareader.bridge115.ui.Pan115AccountsScreen(onBack = { show115Accounts = false }, onOpenBook = onOpenBook)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网盘") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { showQuarkAccounts = true },
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_quark),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("夸克网盘", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (quarkAccounts.isEmpty()) "点击管理账号" else "${quarkAccounts.size} 个账号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clickable { showBaiduAccounts = true },
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_baidu_pan),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("百度网盘", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (baiduAccounts.isEmpty()) "点击管理账号" else "${baiduAccounts.size} 个账号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clickable { show115Accounts = true },
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_115_pan),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("115网盘", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (accounts115.isEmpty()) "点击管理账号" else "${accounts115.size} 个账号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
        }
    }
}