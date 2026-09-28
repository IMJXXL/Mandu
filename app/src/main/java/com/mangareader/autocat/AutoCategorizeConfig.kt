package com.mangareader.autocat

import android.content.Context

data class AutoCategorizeConfig(
    val enableSeries: Boolean = false,
    val enableWriter: Boolean = false,
    val enableGenre: Boolean = false,
    val enablePublisher: Boolean = false,
) {
    companion object {
        private const val PREFS_NAME = "manga_reader"
        private const val KEY_ENABLE_SERIES = "autocat_enable_series"
        private const val KEY_ENABLE_WRITER = "autocat_enable_writer"
        private const val KEY_ENABLE_GENRE = "autocat_enable_genre"
        private const val KEY_ENABLE_PUBLISHER = "autocat_enable_publisher"

        fun load(ctx: Context): AutoCategorizeConfig {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AutoCategorizeConfig(
                enableSeries = prefs.getBoolean(KEY_ENABLE_SERIES, false),
                enableWriter = prefs.getBoolean(KEY_ENABLE_WRITER, false),
                enableGenre = prefs.getBoolean(KEY_ENABLE_GENRE, false),
                enablePublisher = prefs.getBoolean(KEY_ENABLE_PUBLISHER, false),
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
            apply()
        }
    }
}