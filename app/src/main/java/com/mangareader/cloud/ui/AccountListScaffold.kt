package com.mangareader.cloud.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.CloudAccountEntity
import com.mangareader.ui.CONTENT_MAX_WIDTH_DP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScaffold(
    title: String,
    accounts: List<CloudAccountEntity>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: (CloudAccountEntity) -> Unit,
    onTest: (CloudAccountEntity) -> Unit,
    onDelete: (CloudAccountEntity) -> Unit,
    onSwitchActive: (CloudAccountEntity) -> Unit,
    onEditName: (Long, String) -> Unit,
    busyId: Long? = null,
    extraInfo: ((CloudAccountEntity) -> String?)? = null,
) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<CloudAccountEntity?>(null) }
    var editingName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<CloudAccountEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "添加账号") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (accounts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有账号，点击右上角 + 添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP)) {
                    items(accounts, key = { it.id }) { account ->
                        val busy = busyId == account.id
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clickable { if (!account.isActive) onSwitchActive(account) }
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            account.displayName,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        val extra = extraInfo?.invoke(account)
                                        if (extra != null) {
                                            Text(extra, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (account.isActive) {
                                        Text(
                                            "使用中",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                val statusColor: Color = when {
                                    account.lastError != null -> MaterialTheme.colorScheme.error
                                    account.lastOkAt != null -> Color(0xFF3A9D5D)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val statusText = when {
                                    account.lastError != null -> "● 错误：${account.lastError.take(60)}"
                                    account.lastOkAt != null -> "● 正常 · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(account.lastOkAt))}"
                                    else -> "● ${account.loginStatus}"
                                }
                                Text(statusText, fontSize = 12.sp, color = statusColor)
                                Spacer(Modifier.height(10.dp))
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = { onBrowse(account) },
                                    ) { Text("浏览") }
                                    TextButton(
                                        enabled = !busy,
                                        onClick = { onTest(account) },
                                    ) { Text(if (busy) "测试中…" else "测试") }
                                    TextButton(onClick = { editing = account; editingName = account.displayName }) {
                                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("编辑")
                                    }
                                    TextButton(onClick = { deleting = account }) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("删除")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { account ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑账号名称") },
            text = {
                OutlinedTextField(
                    value = editingName,
                    onValueChange = { editingName = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = editingName.trim()
                    if (name.isEmpty()) {
                        Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val target = account
                    editing = null
                    scope.launch(Dispatchers.IO + NonCancellable) {
                        onEditName(target.id, name)
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }

    deleting?.let { account ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除账号") },
            text = { Text("将删除「${account.displayName}」的登录凭证和配置。确认删除？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = account
                    deleting = null
                    scope.launch(Dispatchers.IO + NonCancellable) {
                        onDelete(target)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}