package com.mangareader.sr

import android.content.Context
import org.json.JSONObject

/**
 * 批量超分任务队列（JSON/SharedPreferences 持久化，重启可恢复，断点续做）。
 * 漫读的"章节" = 一本书里的一个 progress 路径，key = chapterKey(bookId, path)。
 */
data class SRQueueItem(
    val bookId: Long,
    val bookTitle: String,
    val path: String,
    val chapterKey: String,
    val totalPages: Int,
    val processedPages: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bookId", bookId)
        put("bookTitle", bookTitle)
        put("path", path)
        put("chapterKey", chapterKey)
        put("totalPages", totalPages)
        put("processedPages", processedPages)
    }

    companion object {
        fun fromJson(json: JSONObject): SRQueueItem = SRQueueItem(
            bookId = json.getLong("bookId"),
            bookTitle = json.getString("bookTitle"),
            path = json.getString("path"),
            chapterKey = json.getString("chapterKey"),
            totalPages = json.getInt("totalPages"),
            processedPages = json.optInt("processedPages", 0),
        )

        /** 章节稳定键：一本书的某阅读路径 */
        fun chapterKey(bookId: Long, path: String): String =
            "b${bookId}_" + Integer.toHexString(path.hashCode())
    }
}

class SRQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("sr_queue", Context.MODE_PRIVATE)

    fun load(): List<SRQueueItem> {
        return prefs.all.mapNotNull { (_, v) ->
            try {
                SRQueueItem.fromJson(JSONObject(v as String))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun save(items: List<SRQueueItem>) {
        val editor = prefs.edit()
        editor.clear()
        items.forEach { item ->
            editor.putString(item.chapterKey, item.toJson().toString())
        }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
