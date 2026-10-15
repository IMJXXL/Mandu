package com.mangareader.quarkbridge.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.mangareader.quarkbridge.QuarkBridges
import com.mangareader.quarkbridge.api.QuarkApiClient
import com.mangareader.quarkbridge.auth.QrState
import com.mangareader.quarkbridge.auth.QuarkLoginFlow
import com.mangareader.quarkbridge.auth.QuarkCredentials
import com.mangareader.quarkbridge.auth.QuarkSessionStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuarkLoginScreen(
    onBack: () -> Unit = {},
    onLoginSuccess: (QuarkCredentials) -> Unit = {},
) {
    BackHandler { onBack() }
    val context = QuarkBridges.context
    val sessionStore = remember { QuarkSessionStore(context) }
    val apiClient = remember { QuarkApiClient(sessionStore = sessionStore) }
    val loginFlow = remember { QuarkLoginFlow(apiClient, sessionStore) }
    val scope = rememberCoroutineScope()
    var qrState by remember { mutableStateOf<QrState>(QrState.Generating) }
    var remainingSeconds by remember { mutableStateOf(120) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (qrState is QrState.Generating) {
                val state = loginFlow.generateQrCode()
                qrState = state
                if (state is QrState.Waiting) {
                    remainingSeconds = 120
                    val result = loginFlow.pollUntilConfirmed(state.token, state.requestId) { pollState ->
                        qrState = pollState
                    }
                    qrState = result
                    if (result is QrState.Confirmed) {
                        onLoginSuccess(result.credentials)
                    }
                }
            }
        }
    }

    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds > 0 && qrState is QrState.Waiting) {
            kotlinx.coroutines.delay(1000)
            remainingSeconds--
            if (remainingSeconds == 0) {
                qrState = QrState.Expired
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加夸克账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Text(
            text = "夸克网盘登录",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (val state = qrState) {
            is QrState.Generating -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在生成二维码...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is QrState.Waiting -> {
                if (state.qrContentUrl.isNotEmpty()) {
                    val bitmap = remember(state.qrContentUrl) {
                        renderQrCode(state.qrContentUrl, 480)
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
                Text(
                    text = "请使用夸克App扫描二维码",
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${remainingSeconds}秒后过期",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is QrState.Scanned -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("请在手机上确认登录", color = MaterialTheme.colorScheme.onSurface)
            }
            is QrState.Confirmed -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("登录成功", color = MaterialTheme.colorScheme.primary)
            }
            is QrState.Expired -> {
                Text("二维码已过期", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    qrState = QrState.Generating
                    scope.launch(Dispatchers.IO) {
                        val newState = loginFlow.generateQrCode()
                        qrState = newState
                        if (newState is QrState.Waiting) {
                            remainingSeconds = 120
                            val result = loginFlow.pollUntilConfirmed(newState.token, newState.requestId) { pollState ->
                                qrState = pollState
                            }
                            qrState = result
                            if (result is QrState.Confirmed) {
                                onLoginSuccess(result.credentials)
                            }
                        }
                    }
                }) {
                    Text("重新扫描")
                }
            }
            is QrState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    qrState = QrState.Generating
                    scope.launch(Dispatchers.IO) {
                        val newState = loginFlow.generateQrCode()
                        qrState = newState
                        if (newState is QrState.Waiting) {
                            remainingSeconds = 120
                            val result = loginFlow.pollUntilConfirmed(newState.token, newState.requestId) { pollState ->
                                qrState = pollState
                            }
                            qrState = result
                            if (result is QrState.Confirmed) {
                                onLoginSuccess(result.credentials)
                            }
                        }
                    }
                }) {
                    Text("重试")
                }
            }
        }
    }
    }
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