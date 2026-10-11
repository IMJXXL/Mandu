package com.mangareader.backup.export

import android.content.Context
import android.net.Uri
import com.mangareader.backup.format.BackupPayload
import com.mangareader.backup.format.BackupContentSelection
import com.mangareader.backup.format.BACKUP_FORMAT_VERSION
import com.mangareader.backup.audit.BackupAuditEntry
import com.mangareader.backup.audit.BackupAuditLogger
import com.mangareader.backup.audit.BackupOperation
import com.mangareader.backup.audit.BackupOperationResult
import com.mangareader.data.MangaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

sealed class ExportResult {
    data class Success(val fileName: String, val fileSize: Long, val durationMs: Long, val fileUri: Uri) : ExportResult()
    data class Failure(val error: String, val message: String) : ExportResult()
}

object BackupExporter {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(
        context: Context,
        targetDirUri: Uri,
        contentSelection: BackupContentSelection,
        onProgress: (String) -> Unit = {}
    ): ExportResult {
        val startTime = System.currentTimeMillis()
        try {
            val dao = MangaDatabase.getDatabase(context).mangaDao()
            onProgress("正在读取数据")
            val progress = if (contentSelection.progress) ProgressExporter.export(dao) else null
            val settings = if (contentSelection.settings) SettingsExporter.export(context) else null
            val shelf = if (contentSelection.shelf) ShelfExporter.export(dao) else null
            val connections = if (contentSelection.connections) ConnectionsExporter.export(context, dao) else null

            onProgress("正在序列化")
            val payload = BackupPayload(
                formatVersion = BACKUP_FORMAT_VERSION,
                appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "",
                dbVersion = com.mangareader.data.MangaDatabase.SCHEMA_VERSION,
                createdAt = System.currentTimeMillis(),
                progress = progress,
                settings = settings,
                shelf = shelf,
                connections = connections,
            )
            val jsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            payload.checksum = BackupChecksumCalculator.calculate(jsonBytes)
            val finalJsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

            onProgress("正在压缩")
            val compressed = withContext(Dispatchers.IO) {
                val baos = java.io.ByteArrayOutputStream()
                GZIPOutputStream(baos).use { it.write(finalJsonBytes) }
                baos.toByteArray()
            }

            onProgress("正在写入文件")
            val fileName = BackupFileNameGenerator.generate(payload.createdAt)
            val written = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    targetDirUri,
                    android.provider.DocumentsContract.getTreeDocumentId(targetDirUri)
                )
                val fileUri = android.provider.DocumentsContract.createDocument(
                    resolver, docUri, "application/gzip", fileName
                ) ?: throw java.io.IOException("无法创建文件")
                resolver.openOutputStream(fileUri)?.use { it.write(compressed) }
                    ?: throw java.io.IOException("无法打开输出流")
                fileUri
            }

            val duration = System.currentTimeMillis() - startTime
            BackupAuditLogger.log(context, BackupAuditEntry(
                timestamp = payload.createdAt,
                operation = BackupOperation.EXPORT.name,
                result = BackupOperationResult.SUCCESS.name,
                fileSize = compressed.size.toLong(),
                durationMs = duration,
                details = fileName
            ))
            return ExportResult.Success(fileName, compressed.size.toLong(), duration, written)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            BackupAuditLogger.log(context, BackupAuditEntry(
                timestamp = System.currentTimeMillis(),
                operation = BackupOperation.EXPORT.name,
                result = BackupOperationResult.FAILURE.name,
                durationMs = duration,
                details = e.message ?: ""
            ))
            return ExportResult.Failure("EXPORT_FAILED", e.message ?: "未知错误")
        }
    }
}