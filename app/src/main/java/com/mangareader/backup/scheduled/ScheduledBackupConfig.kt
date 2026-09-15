package com.mangareader.backup.scheduled

import kotlinx.serialization.Serializable

@Serializable
data class ScheduledBackupConfig(
    val enabled: Boolean = false,
    val frequency: BackupFrequency = BackupFrequency.DAILY,
    val webdavSourceId: Long = -1L,
    val localRetentionCount: Int = 7,
    val remotePath: String = "/MangaReaderBackups",
    val lastBackupAt: Long = 0L,
)

enum class BackupFrequency { DAILY, WEEKLY }