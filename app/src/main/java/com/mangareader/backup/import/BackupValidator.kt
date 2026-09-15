package com.mangareader.backup.`import`

import android.content.Context
import android.net.Uri
import com.mangareader.backup.format.BackupPayload
import com.mangareader.backup.format.BACKUP_FORMAT_VERSION
import com.mangareader.backup.export.BackupChecksumCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

sealed class ValidationResult {
    data class Valid(val payload: BackupPayload, val fileSize: Long) : ValidationResult()
    data class Invalid(val error: String, val message: String) : ValidationResult()
}

object BackupValidator {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun validate(context: Context, fileUri: Uri): ValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val rawBytes = resolver.openInputStream(fileUri)?.use { ins ->
                    ByteArrayOutputStream().use { baos ->
                        GZIPInputStream(ins).use { gz -> gz.copyTo(baos) }
                        baos.toByteArray()
                    }
                } ?: return@withContext ValidationResult.Invalid("FILE_CORRUPTED", "无法读取文件")

                val payload = json.decodeFromString<BackupPayload>(String(rawBytes, Charsets.UTF_8))

                val recomputedChecksum = BackupChecksumCalculator.calculate(
                    json.encodeToString(payload.copy(checksum = "")).toByteArray(Charsets.UTF_8)
                )
                if (payload.checksum.isNotEmpty() && recomputedChecksum != payload.checksum) {
                    return@withContext ValidationResult.Invalid("CHECKSUM_MISMATCH", "备份文件校验失败，可能已损坏")
                }

                if (payload.formatVersion > BACKUP_FORMAT_VERSION) {
                    return@withContext ValidationResult.Invalid("VERSION_INCOMPATIBLE", "备份文件版本(v${payload.formatVersion})高于当前支持版本(v$BACKUP_FORMAT_VERSION)，请升级应用")
                }

                ValidationResult.Valid(payload, rawBytes.size.toLong())
            } catch (e: Exception) {
                ValidationResult.Invalid("FILE_CORRUPTED", "文件解析失败: ${e.message}")
            }
        }
    }
}