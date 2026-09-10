package com.mangareader.smbbridge

import android.os.Build

/**
 * SMB 版本守卫：SMB 功能在 Android 8.0+（API >= 26）可用。
 * smbj 为纯 Java 库，无 Android API 硬限制；原先限 31 是保守策略，现已放开。
 * WebDAV 同样 API 26+ 可用。
 */
object SmbVersionGuard {
    const val MIN_API = 26

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_API

    /** API 达标则执行 [block] 并返回其结果，否则返回 null（调用方按 Blocked 处理）。 */
    inline fun <T> runIfSupported(block: () -> T): T? = if (isSupported()) block() else null
}
