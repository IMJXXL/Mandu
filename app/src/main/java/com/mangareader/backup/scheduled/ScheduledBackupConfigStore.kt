package com.mangareader.backup.scheduled

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ScheduledBackupConfigStore {
    private const val PREFS = "backup_scheduled"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(context: Context): ScheduledBackupConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("config", null) ?: return ScheduledBackupConfig()
        return try { json.decodeFromString<ScheduledBackupConfig>(raw) } catch (_: Exception) { ScheduledBackupConfig() }
    }

    fun save(context: Context, config: ScheduledBackupConfig) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("config", json.encodeToString(config)).apply()
    }
}