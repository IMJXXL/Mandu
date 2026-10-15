package com.mangareader.quarkbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mangareader.R
import com.mangareader.quarkbridge.QuarkBridges
import com.mangareader.quarkbridge.auth.QuarkSessionStore
import com.mangareader.baidubridge.BaiduBridges
import com.mangareader.baidubridge.auth.BaiduSessionStore
import com.mangareader.bridge115.Bridges115
import com.mangareader.bridge115.auth.SessionStore115
import com.mangareader.bridge115.auth.ModeStore115
import com.mangareader.bridge115.Constants115

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CloudDriveScreen(
    onBack: () -> Unit,
    onOpenBook: ((Long, String?, Int) -> Unit)? = null,
) {
    val context = QuarkBridges.context
    val sessionStore = remember { QuarkSessionStore(context) }
    var quarkNickname by remember { mutableStateOf<String?>(null) }
    var quarkLoggedIn by remember { mutableStateOf(sessionStore.isValid()) }

    LaunchedEffect(Unit) {
        if (quarkLoggedIn) {
            quarkNickname = sessionStore.load()?.nickname
        }
    }

    var showQuarkPan by remember { mutableStateOf(false) }

    if (showQuarkPan) {
        QuarkPanScreen(onBack = { showQuarkPan = false }, onOpenBook = onOpenBook)
        return
    }

    val baiduSessionStore = remember { BaiduSessionStore(BaiduBridges.context) }
    var baiduLoggedIn by remember { mutableStateOf(baiduSessionStore.isValid()) }
    var baiduNickname by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (baiduLoggedIn) {
            baiduNickname = baiduSessionStore.load()?.nickname
        }
    }
    var showBaiduPan by remember { mutableStateOf(false) }
    if (showBaiduPan) {
        com.mangareader.baidubridge.ui.BaiduPanScreen(onBack = { showBaiduPan = false }, onOpenBook = onOpenBook)
        return
    }

    val session115 = remember { SessionStore115(Bridges115.context) }
    val modeStore115 = remember { ModeStore115(Bridges115.context) }
    var mode115 by remember { mutableStateOf(modeStore115.getMode()) }
    var loggedIn115 by remember { mutableStateOf(
        (mode115 == Constants115.MODE_OFFICIAL && session115.isValidOfficial()) ||
        (mode115 == Constants115.MODE_WEB && session115.isValidWeb())
    ) }
    var show115Pan by remember { mutableStateOf(false) }
    if (show115Pan) {
        com.mangareader.bridge115.ui.Pan115Screen(onBack = { show115Pan = false }, onOpenBook = onOpenBook)
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
                modifier = Modifier.fillMaxWidth().clickable { showQuarkPan = true },
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
                            if (quarkLoggedIn && quarkNickname != null) "已登录 · $quarkNickname" else "点击登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clickable { showBaiduPan = true },
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
                            if (baiduLoggedIn && baiduNickname != null) "已登录 · $baiduNickname" else "点击登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clickable { show115Pan = true },
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
                            if (loggedIn115) "已登录" else "点击登录",
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