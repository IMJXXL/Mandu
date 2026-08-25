package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences

class SearchHistory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val maxHistory = 20

    fun add(query: String) {
        if (query.isBlank()) return
        val history = getHistory().toMutableList()
        history.remove(query)
        history.add(0, query)
        if (history.size > maxHistory) history.removeAt(history.size - 1)
        prefs.edit().putStringSet("history", history.toSet()).apply()
    }

    fun getHistory(): List<String> {
        return prefs.getStringSet("history", emptySet())?.toList() ?: emptyList()
    }

    fun remove(query: String) {
        val history = getHistory().toMutableList()
        history.remove(query)
        prefs.edit().putStringSet("history", history.toSet()).apply()
    }

    fun clear() {
        prefs.edit().remove("history").apply()
    }
}
