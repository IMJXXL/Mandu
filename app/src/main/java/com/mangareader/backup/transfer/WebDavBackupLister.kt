package com.mangareader.backup.transfer

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.RemoteFileEntry

data class RemoteBackupEntry(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val remotePath: String,
)

object WebDavBackupLister {
    suspend fun listBackups(
        source: WebDavSourceEntity,
        password: String,
        remoteDir: String,
    ): List<RemoteBackupEntry> {
        val files = WebDavClient.listAllFiles(source, password, remoteDir)
        return files
            .filter { it.name.endsWith(".json.gz") }
            .map { RemoteBackupEntry(it.name, it.size, it.lastModified, "${remoteDir.trimEnd('/')}/${it.name}") }
            .sortedByDescending { it.lastModified }
    }
}