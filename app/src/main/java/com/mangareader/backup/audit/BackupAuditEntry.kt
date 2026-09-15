package com.mangareader.backup.audit

import kotlinx.serialization.Serializable

@Serializable
data class BackupAuditEntry(
    val timestamp: Long,
    val operation: String,
    val result: String,
    val fileSize: Long = 0,
    val durationMs: Long = 0,
    val details: String = "",
)

enum class BackupOperation { EXPORT, IMPORT, WEBDAV_UPLOAD, WEBDAV_DOWNLOAD, SCHEDULED_BACKUP, SCHEDULE_CONFIG_CHANGE }
enum class BackupOperationResult { SUCCESS, FAILURE, PARTIAL }