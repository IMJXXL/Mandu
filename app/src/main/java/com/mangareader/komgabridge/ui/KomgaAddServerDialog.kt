package com.mangareader.komgabridge.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mangareader.komgabridge.KomgaScanner
import com.mangareader.komgabridge.api.KomgaApiClient
import com.mangareader.komgabridge.api.KomgaHttpClient
import com.mangareader.komgabridge.data.KomgaAuthType
import com.mangareader.komgabridge.data.KomgaConnection
import com.mangareader.komgabridge.data.KomgaConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomgaAddServerDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    initial: KomgaConnection? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { KomgaConnectionStore(ctx) }

    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var authType by remember { mutableStateOf(initial?.authType ?: KomgaAuthType.API_KEY) }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var allowSelfSigned by remember { mutableStateOf(initial?.allowSelfSignedCert ?: false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val canSave = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial != null) "编辑服务器" else "添加服务器") },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
                actions = {
                    TextButton(
                        onClick = {
                            if (!canSave || saving) return@TextButton
                            saving = true
                            scope.launch {
                                try {
                                    val connId = withContext(Dispatchers.IO) {
                                        val conn = KomgaConnection(
                                            id = initial?.id ?: UUID.randomUUID().toString(),
                                            name = name.trim(),
                                            baseUrl = baseUrl.trim().trimEnd('/'),
                                            authType = authType,
                                            apiKey = apiKey.trim(),
                                            username = username.trim(),
                                            password = password,
                                            allowSelfSignedCert = allowSelfSigned,
                                        )
                                        store.saveConnection(conn)
                                        conn.id
                                    }
                                    Toast.makeText(ctx, "已保存，开始扫描入库", Toast.LENGTH_SHORT).show()
                                    onSaved()
                                    CoroutineScope(Dispatchers.IO).launch {
                                        KomgaScanner.scanKomgaServer(connId)
                                    }
                                } catch (t: Throwable) {
                                    Toast.makeText(ctx, t.message ?: "保存失败", Toast.LENGTH_SHORT).show()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = canSave && !saving,
                    ) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; testResult = null },
                label = { Text("服务器地址") },
                placeholder = { Text("https://komga.example.com") },
                isError = baseUrl.isNotEmpty() && !canSave,
                supportingText = { if (baseUrl.isNotEmpty() && !canSave) Text("地址需以 http:// 或 https:// 开头") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Text("认证方式", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = authType == KomgaAuthType.API_KEY,
                    onClick = { authType = KomgaAuthType.API_KEY },
                    label = { Text("API Key") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = authType == KomgaAuthType.BASIC,
                    onClick = { authType = KomgaAuthType.BASIC },
                    label = { Text("用户名密码") },
                )
            }
            if (authType == KomgaAuthType.API_KEY) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testResult = null },
                    label = { Text("API 密钥") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            } else {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; testResult = null },
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; testResult = null },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = allowSelfSigned, onCheckedChange = { allowSelfSigned = it })
                Spacer(Modifier.width(8.dp))
                Text("允许自签名证书")
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (testing || !canSave) return@Button
                    testing = true
                    testResult = null
                    scope.launch {
                        try {
                            val conn = KomgaConnection(
                                id = initial?.id ?: "test",
                                name = name.trim(),
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                authType = authType,
                                apiKey = apiKey.trim(),
                                username = username.trim(),
                                password = password,
                                allowSelfSignedCert = allowSelfSigned,
                            )
                            val client = KomgaHttpClient.client(allowSelfSigned)
                            val api = KomgaApiClient(conn, client)
                            val result = withContext(Dispatchers.IO) { api.testConnection() }
                            result.fold(
                                onSuccess = { testResult = "OK" },
                                onFailure = { testResult = it.message ?: "failed" },
                            )
                            // ★ P7：client 进程内共享，不再 close
                        } catch (t: Throwable) {
                            testResult = t.message ?: "failed"
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = canSave && !testing,
            ) { Text(if (testing) "测试中…" else "测试连接") }
            testResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    result,
                    color = if (result == "OK") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}