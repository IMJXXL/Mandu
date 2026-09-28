package com.mangareader.autocat

import android.content.Context

data class AutoCategorizeConfig(
    val enableSeries: Boolean = false,
    val enableWriter: Boolean = false,
    val enableGenre: Boolean = false,
    val enablePublisher: Boolean = false,
    val customFieldNames: List<String> = emptyList(),
    val enabledCustomFields: Set<String> = emptySet(),
) {
    companion object {
        private const val PREFS_NAME = "manga_reader"
        private const val KEY_ENABLE_SERIES = "autocat_enable_series"
        private const val KEY_ENABLE_WRITER = "autocat_enable_writer"
        private const val KEY_ENABLE_GENRE = "autocat_enable_genre"
        private const val KEY_ENABLE_PUBLISHER = "autocat_enable_publisher"
        private const val KEY_CUSTOM_NAMES = "autocat_custom_names"
        private const val KEY_CUSTOM_ENABLED = "autocat_custom_enabled"

        fun load(ctx: Context): AutoCategorizeConfig {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AutoCategorizeConfig(
                enableSeries = prefs.getBoolean(KEY_ENABLE_SERIES, false),
                enableWriter = prefs.getBoolean(KEY_ENABLE_WRITER, false),
                enableGenre = prefs.getBoolean(KEY_ENABLE_GENRE, false),
                enablePublisher = prefs.getBoolean(KEY_ENABLE_PUBLISHER, false),
                customFieldNames = prefs.getString(KEY_CUSTOM_NAMES, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                enabledCustomFields = prefs.getString(KEY_CUSTOM_ENABLED, "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
            )
        }
    }

    fun save(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLE_SERIES, enableSeries)
            putBoolean(KEY_ENABLE_WRITER, enableWriter)
            putBoolean(KEY_ENABLE_GENRE, enableGenre)
            putBoolean(KEY_ENABLE_PUBLISHER, enablePublisher)
            putString(KEY_CUSTOM_NAMES, customFieldNames.joinToString(","))
            putString(KEY_CUSTOM_ENABLED, enabledCustomFields.joinToString(","))
            apply()
        }
    }
}