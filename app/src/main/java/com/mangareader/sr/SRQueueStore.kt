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
    // ★ F4.4 代际戳：每次入队刷新。服务收尾移除条目前比对代际，
    //   "运行中被重新入队"的新条目（代际更新）不会被误删
    val enqueuedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bookId", bookId)
        put("bookTitle", bookTitle)
        put("path", path)
        put("chapterKey", chapterKey)
        put("totalPages", totalPages)
        put("processedPages", processedPages)
        put("enqueuedAt", enqueuedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): SRQueueItem = SRQueueItem(
            bookId = json.getLong("bookId"),
            bookTitle = json.getString("bookTitle"),
            path = json.getString("path"),
            chapterKey = json.getString("chapterKey"),
            totalPages = json.getInt("totalPages"),
            processedPages = json.optInt("processedPages", 0),
            enqueuedAt = json.optLong("enqueuedAt", 0),
        )

        /** 章节稳定键：一本书的某阅读路径 */
        fun chapterKey(bookId: Long, path: String): String =
            "b${bookId}_" + Integer.toHexString(path.hashCode())
    }
}

class SRQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("sr_queue", Context.MODE_PRIVATE)

    // ★ F4.3：load→改→save 读写串行化锁——原实现各调用方无锁 load/save 互相覆盖，
    //   apply() 异步落盘进一步拉宽窗口。所有"读改写"请走 mutate()。
    private val lock = Any()

    fun load(): List<SRQueueItem> = synchronized(lock) { loadLocked() }

    fun save(items: List<SRQueueItem>) = synchronized(lock) { saveLocked(items) }

    /** ★ F4.3：原子读改写。transform 里不要再调用 load/save（会死锁，锁不可重入语义按最小用例保证） */
    fun mutate(transform: (List<SRQueueItem>) -> List<SRQueueItem>): List<SRQueueItem> =
        synchronized(lock) {
            val next = transform(loadLocked())
            saveLocked(next)
            next
        }

    private fun loadLocked(): List<SRQueueItem> {
        return prefs.all.mapNotNull { (_, v) ->
            try {
                SRQueueItem.fromJson(JSONObject(v as String))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun saveLocked(items: List<SRQueueItem>) {
        val editor = prefs.edit()
        editor.clear()
        items.forEach { item ->
            editor.putString(item.chapterKey, item.toJson().toString())
        }
        // ★ F4.3：锁内 commit（同步落盘），杜绝 apply 异步写与后续读的乱序窗口（调用方都在 IO 线程）
        editor.commit()
    }

    fun clear() = synchronized(lock) { prefs.edit().clear().commit() }
}
