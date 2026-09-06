package com.mangareader.smbbridge

import android.content.Context

/**
 * appCtx 桥接（tasks 1.5.3）：拷入的参考项目代码通过 `splitties.init.appCtx` 访问全局 Context
 * （23 处 import），splitties-init-support 已通过 InitializationProvider 在 Application.onCreate
 * 之前自动完成初始化，无需手动 install。
 *
 * init() 在 App.onCreate 中显式调用，作为胶水层初始化锚点并缓存 applicationContext；
 * [appCtx] 为胶水层自身的等价访问点。
 */
object SmbAppContextBridge {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val appCtx: Context
        get() = appContext ?: splitties.init.appCtx
}
