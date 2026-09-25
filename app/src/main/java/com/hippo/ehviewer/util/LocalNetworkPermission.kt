package com.hippo.ehviewer.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.IOException
import splitties.init.appCtx

/**
 * Android 17 (API 37) blocks LAN sockets (SMB, local WebDAV) unless the app
 * declares and is granted ACCESS_LOCAL_NETWORK. Missing permission surfaces as a TCP
 * TimeoutException rather than a permission error.
 */
object LocalNetworkPermission {
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    val isRequired: Boolean
        get() = Build.VERSION.SDK_INT >= 37

    fun isGranted(context: Context = appCtx): Boolean {
        if (!isRequired) return true
        return ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requireGranted(context: Context = appCtx) {
        if (!isGranted(context)) {
            throw IOException("ACCESS_LOCAL_NETWORK permission denied")
        }
    }
}

/**
 * 进入 SMB/WebDAV 相关界面时补授权：Android 17 起局域网 socket 需要 ACCESS_LOCAL_NETWORK
 * 运行时授权（清单已声明，但必须向用户请求）。仅在需要的系统版本且未授权时弹系统对话框，
 * 每次进界面至多弹一次；拒绝则后续 SMB 连接抛 requireGranted 的既定错误文案，下次进入再请求。
 */
@Composable
fun LocalNetworkPermissionEffect() {
    if (!LocalNetworkPermission.isRequired) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalNetworkPermission.isGranted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(LocalNetworkPermission.ACCESS_LOCAL_NETWORK)
    }
}