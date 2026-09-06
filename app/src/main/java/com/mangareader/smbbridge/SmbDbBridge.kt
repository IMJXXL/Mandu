package com.mangareader.smbbridge

import com.ehviewer.core.database.LocalLibraryDatabase
import com.ehviewer.core.database.dao.SmbSourceDao
import com.mangareader.data.MangaDatabase

/**
 * DB 桥接（tasks 1.5.1/1.5.4）：拷入的参考项目代码通过 `LocalLibrary.kt` 的
 * `localLibraryDb` 访问库实例（SmbRepository/WebDavRepository 同样经由它取 DAO），
 * 本桥接将其指向漫读唯一的 MangaDatabase（MangaDatabase 继承 LocalLibraryDatabase，
 * 不创建独立的 local_library.db）。
 */
object SmbDbBridge {
    @Volatile
    private var instance: LocalLibraryDatabase? = null

    /** 漫读主库注入点，在 App.onCreate 调用（与 localLibraryDb 为同一实例）。 */
    var mangaDatabase: MangaDatabase?
        get() = instance as? MangaDatabase
        set(value) {
            checkNotNull(value) { "SmbDbBridge.mangaDatabase cannot be set to null" }
            instance = value
        }

    fun init(db: LocalLibraryDatabase) {
        instance = db
    }

    val localLibraryDb: LocalLibraryDatabase get() = instance!!

    val smbSourceDao: SmbSourceDao get() = localLibraryDb.smbSourceDao()
}
