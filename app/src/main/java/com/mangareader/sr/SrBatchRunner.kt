package com.mangareader.sr

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mangareader.data.MangaDatabase
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 批量超分队列入口。队列持久化在 SRQueueStore（JSON），执行由前台服务 SrBatchService 承担。
 * 选书规则：把一本书的所有"已读章节路径"（reading_progress）入队；从未读过的书退化为书根路径一卷。
 * 全部操作异步（Room 查询与 listPages 是 IO，不允许发生在主线程）。
 */
object SrBatchRunner {
    private const val TAG = "SrBatchRunner"

    /** 批量入队一本书；完成后在指定回调给出入队章节数（回调在主线程外触发，自行切线程做 UI） */
    fun enqueueBook(context: Context, bookId: Long, bookTitle: String, onDone: (Int) -> Unit = {}) {
        enqueueBooks(context, listOf(bookId to bookTitle), onDone)
    }

    /** 批量入队多本书；完成回调参数为新增章节总数 */
    fun enqueueBooks(context: Context, books: List<Pair<Long, String>>, onDone: (Int) -> Unit = {}) {
        if (books.isEmpty()) {
            onDone(0)
            return
        }
        if (!SrCore.initialized) SrCore.init(context)
        val appCtx = context.applicationContext
        SrCore.scope.launch(Dispatchers.IO) {
            var total = 0
            books.forEach { (id, title) -> total += enqueueBookInternal(appCtx, id, title) }
            if (total > 0) startService(appCtx)
            onDone(total)
        }
    }

    /** IO 协程内执行：解析章节列表 → 去重入队 */
    private suspend fun enqueueBookInternal(context: Context, bookId: Long, bookTitle: String): Int {
        return try {
            val dao = MangaDatabase.getDatabase(context).mangaDao()
            val book = dao.getBookById(bookId) ?: return 0
            val progressPaths = try {
                dao.getAllProgressForBook(bookId).map { it.path }
            } catch (_: Exception) {
                emptyList()
            }
            val paths = progressPaths.ifEmpty { listOf(book.filePath) }

            val current = SrCore.queueStore.load().toMutableList()
            val newItems = mutableListOf<SRQueueItem>()
            val probe = ArchiveEngine(context.applicationContext)
            paths.forEach { path ->
                val key = SRQueueItem.chapterKey(bookId, path)
                val pageCount = try {
                    probe.listPages(path).size
                } catch (_: Exception) {
                    0
                }
                val fresh = SRQueueItem(bookId, bookTitle, path, key, pageCount)
                // 重新入队 = 从头重做：队列里已有同章旧项时整体替换（processedPages 归零），
                // 否则残留的旧进度会让用户看到"从中间开始"。
                val oldIdx = current.indexOfFirst { it.chapterKey == key }
                if (oldIdx >= 0) current[oldIdx] = fresh else current.add(fresh)
                newItems.add(fresh)
            }
            if (newItems.isNotEmpty()) {
                SrCore.queueStore.save(current)
            }
            newItems.size
        } catch (e: Exception) {
            Log.e(TAG, "enqueue book $bookId failed", e)
            0
        }
    }

    fun remove(context: Context, chapterKey: String) {
        val items = SrCore.queueStore.load().filterNot { it.chapterKey == chapterKey }
        SrCore.queueStore.save(items)
    }

    fun clear(context: Context) {
        SrCore.queueStore.clear()
        try {
            context.stopService(Intent(context, SrBatchService::class.java))
        } catch (_: Exception) {}
    }

    fun startService(context: Context) {
        try {
            val intent = Intent(context, SrBatchService::class.java)
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "start service failed", e)
        }
    }
}
