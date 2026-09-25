package com.mangareader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 取证 Provider（2026-09-25 无日志闪退专项）：ContentProvider 按 manifest 声明顺序初始化，
 * 且**先于 Application.onCreate**——声明在第一位即可在一切业务代码之前留下启动标记。
 * 若进程死在标记之前，说明崩溃发生在类加载/更早阶段（极端 case）。
 */
class DiagProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        DiagTracer.mark(ctx, "diag-provider: created (earliest app code, before Application.onCreate)")
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
