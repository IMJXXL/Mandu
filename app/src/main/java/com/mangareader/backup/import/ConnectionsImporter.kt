package com.mangareader.backup.`import`

import android.content.Context
import com.mangareader.backup.format.ConnectionsPayload
import com.mangareader.backup.format.ConflictStrategy
import com.mangareader.data.MangaDao
import com.mangareader.data.Folder
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.database.model.LibraryRootEntity
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.mangareader.komgabridge.data.KomgaConnectionStore

object ConnectionsImporter {
    suspend fun `import`(context: Context, dao: MangaDao, payload: ConnectionsPayload, strategy: ConflictStrategy): Int {
        var count = 0
        if (payload.smbSources.isNotEmpty()) {
            val items = payload.smbSources.map {
                SmbSourceEntity(it.id, it.displayName, it.host, it.port, it.easyTierHost ?: "", it.share, it.pathPrefix ?: "", it.username ?: "", it.domain ?: "", it.addedAt, if (it.lastOkAt > 0) it.lastOkAt else null, it.lastError)
            }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllSmbSources(); dao.insertSmbSourcesReplace(items) }
                ConflictStrategy.SKIP -> dao.insertSmbSourcesIgnore(items)
                ConflictStrategy.MERGE -> dao.insertSmbSourcesReplace(items)
            }
            count += items.size
        }
        if (payload.smbPasswords.isNotEmpty()) {
            SmbPasswordStore.importAllEncrypted(payload.smbPasswords)
        }
        if (payload.webdavSources.isNotEmpty()) {
            val items = payload.webdavSources.map {
                WebDavSourceEntity(it.id, it.displayName, it.baseUrl, it.easyTierHost ?: "", it.pathPrefix ?: "", it.username ?: "", it.addedAt, if (it.lastOkAt > 0) it.lastOkAt else null, it.lastError)
            }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllWebDavSources(); dao.insertWebDavSourcesReplace(items) }
                ConflictStrategy.SKIP -> dao.insertWebDavSourcesIgnore(items)
                ConflictStrategy.MERGE -> dao.insertWebDavSourcesReplace(items)
            }
            count += items.size
        }
        if (payload.webdavPasswords.isNotEmpty()) {
            WebDavPasswordStore.importAllEncrypted(payload.webdavPasswords)
        }
        if (payload.komgaConnectionRaw != null) {
            val store = KomgaConnectionStore(context)
            store.setRawConnections(payload.komgaConnectionRaw)
            if (payload.komgaActiveConnectionId != null) {
                store.setRawActiveConnectionId(payload.komgaActiveConnectionId)
            }
            count++
        }
        if (payload.libraryRoots.isNotEmpty()) {
            val items = payload.libraryRoots.map {
                LibraryRootEntity(it.id, it.treeUri, it.displayName, it.addedAt, it.role, it.accessMode)
            }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllLibraryRoots(); dao.insertLibraryRootsReplace(items) }
                ConflictStrategy.SKIP -> dao.insertLibraryRootsIgnore(items)
                ConflictStrategy.MERGE -> dao.insertLibraryRootsReplace(items)
            }
            count += items.size
        }
        if (payload.importedFolders.isNotEmpty()) {
            val items = payload.importedFolders.map { Folder(it.id, it.treeUri, it.displayName, it.lastScanAt) }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllImportedFolders(); dao.insertImportedFoldersReplace(items) }
                ConflictStrategy.SKIP -> dao.insertImportedFoldersIgnore(items)
                ConflictStrategy.MERGE -> dao.insertImportedFoldersReplace(items)
            }
            count += items.size
        }
        return count
    }
}