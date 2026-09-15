package com.mangareader.backup.`import`

import android.content.Context
import android.net.Uri
import com.mangareader.backup.format.BackupContentSelection
import com.mangareader.backup.format.ConflictStrategy
import com.mangareader.backup.snapshot.RescanSnapshotManager
import com.mangareader.backup.audit.BackupAuditEntry
import com.mangareader.backup.audit.BackupAuditLogger
import com.mangareader.backup.audit.BackupOperation
import com.mangareader.backup.audit.BackupOperationResult
import com.mangareader.data.MangaDatabase

sealed class ImportResult {
    data class Success(val importedCount: Int, val durationMs: Long) : ImportResult()
    data class PartialSuccess(val importedCount: Int, val failedCategories: List<String>, val durationMs: Long) : ImportResult()
    data class Failure(val error: String, val message: String) : ImportResult()
}

object BackupImporter {
    suspend fun validate(context: Context, fileUri: Uri): ValidationResult {
        return BackupValidator.validate(context, fileUri)
    }

    suspend fun `import`(
        context: Context,
        fileUri: Uri,
        contentSelection: BackupContentSelection,
        conflictStrategy: ConflictStrategy,
        onProgress: (String) -> Unit = {}
    ): ImportResult {
        val startTime = System.currentTimeMillis()
        try {
            onProgress("正在校验备份文件")
            val validationResult = BackupValidator.validate(context, fileUri)
            if (validationResult is ValidationResult.Invalid) {
                return ImportResult.Failure(validationResult.error, validationResult.message)
            }
            val payload = (validationResult as ValidationResult.Valid).payload
            val dao = MangaDatabase.getDatabase(context).mangaDao()

            onProgress("正在创建保护快照")
            RescanSnapshotManager.createSnapshot(dao)

            var totalImported = 0
            val failedCategories = mutableListOf<String>()

            if (contentSelection.progress && payload.progress != null) {
                try {
                    onProgress("正在恢复阅读进度")
                    totalImported += ProgressImporter.`import`(dao, payload.progress, conflictStrategy)
                } catch (e: Exception) {
                    failedCategories.add("阅读进度")
                }
            }

            if (contentSelection.settings && payload.settings != null) {
                try {
                    onProgress("正在恢复阅读设置")
                    SettingsImporter.`import`(context, payload.settings)
                    totalImported++
                } catch (e: Exception) {
                    failedCategories.add("阅读设置")
                }
            }

            if (contentSelection.shelf && payload.shelf != null) {
                try {
                    onProgress("正在恢复书架分类")
                    totalImported += ShelfImporter.`import`(dao, payload.shelf, conflictStrategy)
                } catch (e: Exception) {
                    failedCategories.add("书架分类")
                }
            }

            if (contentSelection.connections && payload.connections != null) {
                try {
                    onProgress("正在恢复连接配置")
                    totalImported += ConnectionsImporter.`import`(context, dao, payload.connections, conflictStrategy)
                } catch (e: Exception) {
                    failedCategories.add("连接配置")
                }
            }

            RescanSnapshotManager.discardSnapshot()
            val duration = System.currentTimeMillis() - startTime

            val result = if (failedCategories.isEmpty()) BackupOperationResult.SUCCESS else BackupOperationResult.PARTIAL
            BackupAuditLogger.log(context, BackupAuditEntry(
                timestamp = System.currentTimeMillis(),
                operation = BackupOperation.IMPORT.name,
                result = result.name,
                durationMs = duration,
                details = if (failedCategories.isEmpty()) "导入 $totalImported 条" else "失败: ${failedCategories.joinToString()}"
            ))

            return if (failedCategories.isEmpty()) {
                ImportResult.Success(totalImported, duration)
            } else {
                ImportResult.PartialSuccess(totalImported, failedCategories, duration)
            }
        } catch (e: Exception) {
            RescanSnapshotManager.discardSnapshot()
            val duration = System.currentTimeMillis() - startTime
            BackupAuditLogger.log(context, BackupAuditEntry(
                timestamp = System.currentTimeMillis(),
                operation = BackupOperation.IMPORT.name,
                result = BackupOperationResult.FAILURE.name,
                durationMs = duration,
                details = e.message ?: ""
            ))
            return ImportResult.Failure("IMPORT_FAILED", e.message ?: "未知错误")
        }
    }
}