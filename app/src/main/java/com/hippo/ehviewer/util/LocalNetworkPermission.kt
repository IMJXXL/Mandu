package com.hippo.ehviewer.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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