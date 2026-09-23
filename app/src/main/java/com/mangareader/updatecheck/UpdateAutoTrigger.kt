package com.mangareader.updatecheck

import android.content.Context
import com.mangareader.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UpdateAutoTrigger {
    fun maybeCheck(ctx: Context) {
        if (!UpdateCheckConfig.shouldAutoCheck(ctx)) return
        UpdateCheckConfig.setLastCheckTime(ctx, System.currentTimeMillis())
        (ctx.applicationContext as? App)?.appScope?.launch(Dispatchers.IO) {
            val info = UpdateChecker.checkForUpdate(ctx)
            if (info != null) {
                UpdateState.pendingUpdate = info
            }
        }
    }
}

object UpdateState {
    @Volatile
    var pendingUpdate: UpdateInfo? = null
}