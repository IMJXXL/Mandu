package com.mangareader.backup.scheduled

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mangareader.backup.export.BackupExporter
import com.mangareader.backup.export.ExportResult
import com.mangareader.backup.format.BackupContentSelection
import com.mangareader.backup.transfer.WebDavBackupTransfer
import com.mangareader.data.MangaDatabase
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import java.io.File

class ScheduledBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = ScheduledBackupConfigStore.load(applicationContext)
        if (!config.enabled) return Result.success()

        try {
            val treeUriStr = com.hippo.ehviewer.Settings.backupDirUri.value
            if (treeUriStr.isNullOrEmpty()) {
                return Result.failure()
            }

            val exportResult = BackupExporter.export(
                applicationContext,
                android.net.Uri.parse(treeUriStr),
                BackupContentSelection(),
            )
            if (exportResult is ExportResult.Failure) {
                return Result.failure()
            }
            val success = exportResult as ExportResult.Success

            if (config.webdavSourceId > 0) {
                val dao = MangaDatabase.getDatabase(applicationContext).mangaDao()
                val webdavSource = dao.exportAllWebDavSources().find { it.id == config.webdavSourceId }
                if (webdavSource != null) {
                    val password = WebDavPasswordStore.get(webdavSource.id) ?: ""
                    val tempFile = File(applicationContext.cacheDir, "backup_upload_tmp.json.gz")
                    try {
                        applicationContext.contentResolver.openInputStream(success.fileUri)?.use { input ->
                            java.io.FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        } ?: return Result.failure()
                        val remotePath = "${config.remotePath.trimEnd('/')}/${success.fileName}"
                        WebDavBackupTransfer.upload(webdavSource, password, tempFile, remotePath)
                    } finally {
                        tempFile.delete()
                    }
                }
            }

            ScheduledBackupConfigStore.save(applicationContext, config.copy(lastBackupAt = System.currentTimeMillis()))
            return Result.success()
        } catch (_: Exception) {
            return Result.failure()
        }
    }
}
