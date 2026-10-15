package com.mangareader.bridge115.auth

import android.content.Context
import com.mangareader.bridge115.Constants115

class ModeStore115(context: Context) {

    private val prefs = context.getSharedPreferences(Constants115.PREFS_MODE, Context.MODE_PRIVATE)

    fun getMode(): String? {
        return prefs.getString(KEY_MODE, null)
    }

    fun setMode(mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
    }

    fun clearMode() {
        prefs.edit().remove(KEY_MODE).apply()
    }

    fun hasMode(): Boolean {
        return prefs.contains(KEY_MODE)
    }

    companion object {
        private const val KEY_MODE = "mode"
    }
}