package com.mangareader.backup.scheduled

import java.io.File

object LocalBackupRetentionCleaner {
    fun clean(localDir: File, retentionCount: Int) {
        if (!localDir.exists()) return
        val backups = localDir.listFiles { f -> f.name.startsWith("mangareader_backup_") && f.name.endsWith(".json.gz") }
            ?.sortedByDescending { it.lastModified() } ?: return
        if (retentionCount <= 0) {
            backups.forEach { it.delete() }
            return
        }
        if (backups.size > retentionCount) {
            backups.drop(retentionCount).forEach { it.delete() }
        }
    }
}