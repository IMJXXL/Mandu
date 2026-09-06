package com.mangareader.webdavbridge

import com.ehviewer.core.database.dao.WebDavSourceDao
import com.ehviewer.core.database.LocalLibraryDatabase

/**
 * WebDAV DB 桥接（design.md 2.2.2 WebDavDbBridge）：与 SmbDbBridge 共用同一 MangaDatabase
 * 实例（MangaDatabase 继承 LocalLibraryDatabase，webDavSourceDao 经继承获得）。
 * App.onCreate 注入与 SmbDbBridge 同一实例。
 */
object WebDavDbBridge {
    @Volatile
    private var instance: LocalLibraryDatabase? = null

    /** 漫读主库注入点（与 SmbDbBridge 同一实例） */
    var mangaDatabase: com.mangareader.data.MangaDatabase?
        get() = instance as? com.mangareader.data.MangaDatabase
        set(value) {
            checkNotNull(value) { "WebDavDbBridge.mangaDatabase cannot be set to null" }
            instance = value
        }

    fun init(db: LocalLibraryDatabase) {
        instance = db
    }

    val localLibraryDb: LocalLibraryDatabase get() = instance!!

    val webdavSourceDao: WebDavSourceDao get() = localLibraryDb.webDavSourceDao()
}

/**
 * appCtx 桥接（design.md 2.2.2）：与 SmbAppContextBridge 完全共用 splitties 初始化，零二次实现。
 */
object WebDavAppContextBridge {
    fun init(context: android.content.Context) = com.mangareader.smbbridge.SmbAppContextBridge.init(context)

    val appCtx: android.content.Context get() = com.mangareader.smbbridge.SmbAppContextBridge.appCtx
}
