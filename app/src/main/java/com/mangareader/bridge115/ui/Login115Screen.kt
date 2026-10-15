package com.mangareader.bridge115.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.mangareader.bridge115.Bridges115
import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.auth.Login115Flow
import com.mangareader.bridge115.auth.QrPayload115
import com.mangareader.bridge115.auth.Session115Official
import com.mangareader.bridge115.auth.Session115Web
import com.mangareader.bridge115.auth.SessionStore115
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class LoginState {
    object Idle : LoginState()
    object Generating : LoginState()
    data class Waiting(val payload: QrPayload115) : LoginState()
    object Scanned : LoginState()
    object Confirmed : LoginState()
    object Expired : LoginState()
    data class Failed(val message: String) : LoginState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Login115Screen(
    onBack: () -> Unit = {},
    onLoginSuccess: (String, Session115Official?, Session115Web?) -> Unit = { _, _, _ -> },
) {
    BackHandler { onBack() }
    val sessionStore = remember { SessionStore115(Bridges115.context) }
    val loginFlow = remember { Login115Flow(sessionStore) }
    val scope = rememberCoroutineScope()
    var selectedMode by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<LoginState>(LoginState.Idle) }
    var remainingSeconds by remember { mutableStateOf(120) }
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }

    val mode = selectedMode
    val isOfficial = mode == Constants115.MODE_OFFICIAL

    LaunchedEffect(selectedMode) {
        if (mode != null && state is LoginState.Idle) {
            state = LoginState.Generating
            if (isOfficial && appId.isEmpty()) {
                state = LoginState.Failed("请输入 APP ID")
                return@LaunchedEffect
            }
            scope.launch(Dispatchers.IO) {
                try {
                    val payload = if (isOfficial) {
                        loginFlow.qrStartOfficial(appId)
                    } else {
                        loginFlow.webQrStart()
                    }
                    state = LoginState.Waiting(payload)
                    remainingSeconds = 120
                    pollLoop(loginFlow, mode, payload, scope) { newState ->
                        state = newState
                        if (newState is LoginState.Confirmed) {
                            val official = if (isOfficial) sessionStore.loadOfficial() else null
                            val web = if (!isOfficial) sessionStore.loadWeb() else null
                            onLoginSuccess(mode, official, web)
                        }
                    }
                } catch (e: Exception) {
                    state = LoginState.Failed(e.message ?: "未知错误")
                }
            }
        }
    }

    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds > 0 && state is LoginState.Waiting) {
            delay(1000)
            remainingSeconds--
            if (remainingSeconds == 0) {
                state = LoginState.Expired
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加115账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        if (mode == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("选择登录模式", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = { selectedMode = Constants115.MODE_OFFICIAL; state = LoginState.Idle }) {
                    Text("官方模式")
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { selectedMode = Constants115.MODE_WEB; state = LoginState.Idle }) {
                    Text("Web模式")
                }
            }
        } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isOfficial) "115 网盘登录（官方）" else "115 网盘登录（非官方）",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!isOfficial) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("非官方", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isOfficial) {
            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it },
                label = { Text("APP ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                label = { Text("APP Secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (val s = state) {
            is LoginState.Idle, is LoginState.Generating -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在生成二维码...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is LoginState.Waiting -> {
                if (s.payload.qrcode.isNotEmpty()) {
                    val bitmap = remember(s.payload.qrcode) {
                        renderQrCode(s.payload.qrcode, 480)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(240.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("请使用 115 App 扫描二维码", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                Text("${remainingSeconds}秒后过期", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is LoginState.Scanned -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("请在手机上确认登录", color = MaterialTheme.colorScheme.onSurface)
            }
            is LoginState.Confirmed -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("登录成功", color = MaterialTheme.colorScheme.primary)
            }
            is LoginState.Expired -> {
                Text("二维码已过期", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { state = LoginState.Idle }) { Text("重新扫描") }
            }
            is LoginState.Failed -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { state = LoginState.Idle }) { Text("重试") }
            }
        }
    }
    }
    }
}

private suspend fun pollLoop(
    loginFlow: Login115Flow,
    mode: String,
    payload: QrPayload115,
    scope: kotlinx.coroutines.CoroutineScope,
    onState: (LoginState) -> Unit
) {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < Constants115.QR_TIMEOUT_MS) {
        try {
            if (mode == Constants115.MODE_OFFICIAL) {
                val result = loginFlow.qrPollOfficial(payload.uid, payload.time, payload.sign)
                when (result.status) {
                    2 -> {
                        onState(LoginState.Confirmed)
                        return
                    }
                    -1, -2 -> {
                        onState(LoginState.Failed(if (result.status == -1) "二维码已过期" else "已取消"))
                        return
                    }
                    1 -> onState(LoginState.Scanned)
                }
            } else {
                val status = loginFlow.webQrPoll(payload.uid, payload.time, payload.sign)
                when (status) {
                    2 -> {
                        loginFlow.webQrCookie(payload.uid, "alipaymini")
                        onState(LoginState.Confirmed)
                        return
                    }
                    -1, -2 -> {
                        onState(LoginState.Failed(if (status == -1) "二维码已过期" else "已取消"))
                        return
                    }
                    1 -> onState(LoginState.Scanned)
                }
            }
        } catch (e: Exception) {
            onState(LoginState.Failed(e.message ?: "网络错误"))
            return
        }
        delay(Constants115.QR_POLL_INTERVAL_MS)
    }
    onState(LoginState.Expired)
}

private fun renderQrCode(content: String, sizePx: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }
}