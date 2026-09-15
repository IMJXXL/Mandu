package com.mangareader.backup.export

import android.content.Context
import com.mangareader.backup.format.ConnectionsPayload
import com.mangareader.backup.format.SmbSourceDto
import com.mangareader.backup.format.WebDavSourceDto
import com.mangareader.backup.format.LibraryRootDto
import com.mangareader.backup.format.ImportedFolderDto
import com.mangareader.data.MangaDao
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.mangareader.komgabridge.data.KomgaConnectionStore

object ConnectionsExporter {
    suspend fun export(context: Context, dao: MangaDao): ConnectionsPayload {
        val smbSources = dao.exportAllSmbSources().map {
            SmbSourceDto(it.id, it.displayName, it.host, it.port, it.easytierHost, it.share, it.pathPrefix, it.username, it.domain, it.addedAt, it.lastOkAt ?: 0, it.lastError)
        }
        val smbPasswords = SmbPasswordStore.exportAllEncrypted()
        val webdavSources = dao.exportAllWebDavSources().map {
            WebDavSourceDto(it.id, it.displayName, it.baseUrl, it.easytierHost, it.pathPrefix, it.username, it.addedAt, it.lastOkAt ?: 0, it.lastError)
        }
        val webdavPasswords = WebDavPasswordStore.exportAllEncrypted()
        val komgaStore = KomgaConnectionStore(context)
        val komgaConnectionRaw = komgaStore.getRawConnections()
        val komgaActiveConnectionId = komgaStore.getRawActiveConnectionId()
        val libraryRoots = dao.exportAllLibraryRoots().map {
            LibraryRootDto(it.id, it.treeUri, it.displayName, it.addedAt, it.role, it.accessMode)
        }
        val importedFolders = dao.exportAllImportedFolders().map {
            ImportedFolderDto(it.id, it.treeUri, it.displayName, it.lastScanAt)
        }
        return ConnectionsPayload(smbSources, smbPasswords, webdavSources, webdavPasswords, komgaConnectionRaw, komgaActiveConnectionId, libraryRoots, importedFolders)
    }
}