package com.mangareader.smbbridge

import android.os.Build

/**
 * SMB 版本守卫（tasks 1.5.2 / 任务组7）：SMB 功能仅 Android 12+（API >= 31）可用，
 * 低版本隐藏/禁用入口。WebDAV 无此守卫（API 26+ 即可用）。
 */
object SmbVersionGuard {
    const val MIN_API = 31

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_API

    /** API 达标则执行 [block] 并返回其结果，否则返回 null（调用方按 Blocked 处理）。 */
    inline fun <T> runIfSupported(block: () -> T): T? = if (isSupported()) block() else null
}
