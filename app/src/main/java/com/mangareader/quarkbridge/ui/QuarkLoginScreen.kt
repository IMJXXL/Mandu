package com.mangareader.quarkbridge.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.mangareader.quarkbridge.QuarkConstants
import com.mangareader.quarkbridge.auth.QrState
import com.mangareader.quarkbridge.auth.QuarkLoginFlow
import kotlinx.coroutines.launch

@Composable
fun QuarkLoginScreen(
    loginFlow: QuarkLoginFlow,
    onLoginSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var qrState by remember { mutableStateOf<QrState>(QrState.Generating) }
    var remainingSeconds by remember { mutableStateOf(120) }
    var token by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (qrState is QrState.Generating) {
            val state = loginFlow.generateQrCode()
            qrState = state
            if (state is QrState.Waiting) {
                token = state.token
                remainingSeconds = 120
                val result = loginFlow.pollUntilConfirmed(state.token) { pollState ->
                    qrState = pollState
                }
                qrState = result
                if (result is QrState.Confirmed) {
                    onLoginSuccess()
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

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
                if (state.qrImageBytes.isNotEmpty()) {
                    val bitmap = remember(state.qrImageBytes) {
                        BitmapFactory.decodeByteArray(state.qrImageBytes, 0, state.qrImageBytes.size)
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
                    scope.launch {
                        val newState = loginFlow.generateQrCode()
                        qrState = newState
                        if (newState is QrState.Waiting) {
                            token = newState.token
                            remainingSeconds = 120
                            val result = loginFlow.pollUntilConfirmed(newState.token) { pollState ->
                                qrState = pollState
                            }
                            qrState = result
                            if (result is QrState.Confirmed) {
                                onLoginSuccess()
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
                    scope.launch {
                        val newState = loginFlow.generateQrCode()
                        qrState = newState
                        if (newState is QrState.Waiting) {
                            token = newState.token
                            remainingSeconds = 120
                            val result = loginFlow.pollUntilConfirmed(newState.token) { pollState ->
                                qrState = pollState
                            }
                            qrState = result
                            if (result is QrState.Confirmed) {
                                onLoginSuccess()
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