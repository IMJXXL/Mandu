package com.mangareader.backup.audit

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object BackupAuditLogger {
    private const val PREFS = "backup_audit"
    private const val KEY_NEXT_INDEX = "nextIndex"
    private const val MAX_ENTRIES = 100
    private const val KEY_ENTRY_PREFIX = "entry_"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun log(context: Context, entry: BackupAuditEntry) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nextIndex = prefs.getInt(KEY_NEXT_INDEX, 0)
        prefs.edit()
            .putString(KEY_ENTRY_PREFIX + nextIndex, json.encodeToString(entry))
            .putInt(KEY_NEXT_INDEX, (nextIndex + 1) % MAX_ENTRIES)
            .apply()
    }

    fun recent(context: Context, limit: Int = 100): List<BackupAuditEntry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nextIndex = prefs.getInt(KEY_NEXT_INDEX, 0)
        val result = mutableListOf<BackupAuditEntry>()
        for (i in 0 until MAX_ENTRIES) {
            val idx = (nextIndex - 1 - i + MAX_ENTRIES) % MAX_ENTRIES
            val raw = prefs.getString(KEY_ENTRY_PREFIX + idx, null) ?: continue
            try { result.add(json.decodeFromString<BackupAuditEntry>(raw)) } catch (_: Exception) {}
            if (result.size >= limit) break
        }
        return result
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}