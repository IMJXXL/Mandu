package com.mangareader.backup.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupFileNameGenerator {
    fun generate(timestamp: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        return "mangareader_backup_${sdf.format(Date(timestamp))}.json.gz"
    }
}