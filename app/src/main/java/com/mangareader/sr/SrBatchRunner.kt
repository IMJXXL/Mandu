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
 * 入队范围两档：默认"读过的章"（reading_progress 路径，从未读退化为书根路径一卷）；
 * 整本模式枚举书根下全部章节（2026-10-02，用户诉求"多选几章放着超分/自动下一章"）。
 * 全部操作异步（Room 查询与 listPages 是 IO，不允许发生在主线程）。
 */
object SrBatchRunner {
    private const val TAG = "SrBatchRunner"

    /** 入队结果：章数+总页数（Toast 预期管理用） */
    data class EnqueueResult(val chapters: Int, val pages: Int) {
        val isEmpty: Boolean get() = chapters <= 0
    }

    /** 批量入队一本书；完成后在指定回调给出入队结果（回调在主线程外触发，自行切线程做 UI） */
    fun enqueueBook(
        context: Context, bookId: Long, bookTitle: String,
        includeUnread: Boolean = false, onDone: (EnqueueResult) -> Unit = {},
    ) = enqueueBooks(context, listOf(bookId to bookTitle), includeUnread, onDone)

    /** 批量入队多本书；完成回调参数为新增章节与页数 */
    fun enqueueBooks(
        context: Context, books: List<Pair<Long, String>>,
        includeUnread: Boolean = false, onDone: (EnqueueResult) -> Unit = {},
    ) {
        if (books.isEmpty()) {
            onDone(EnqueueResult(0, 0))
            return
        }
        if (!SrCore.initialized) SrCore.init(context)
        val appCtx = context.applicationContext
        SrCore.scope.launch(Dispatchers.IO) {
            var chapters = 0
            var pages = 0
            books.forEach { (id, title) ->
                val r = enqueueBookInternal(appCtx, id, title, includeUnread)
                chapters += r.first; pages += r.second
            }
            if (chapters > 0) startService(appCtx)
            onDone(EnqueueResult(chapters, pages))
        }
    }

    /** IO 协程内执行：解析章节列表 → 去重入队。返回 (新增章数, 总页数) */
    private suspend fun enqueueBookInternal(
        context: Context, bookId: Long, bookTitle: String, includeUnread: Boolean,
    ): Pair<Int, Int> {
        return try {
            val dao = MangaDatabase.getDatabase(context).mangaDao()
            val book = dao.getBookById(bookId) ?: return 0 to 0
            val progressPaths = try {
                dao.getAllProgressForBook(bookId).map { it.path }
            } catch (_: Exception) {
                emptyList()
            }
            val paths = when {
                // ★ 整本模式（2026-10-02）：枚举书根下全部章节路径；枚举为空（单文件书/
                //   无子章）退化为书根本身一卷。与读过的章合并去重（chapterKey 为准）。
                includeUnread -> {
                    val probe = ArchiveEngine(context.applicationContext)
                    val enumerated = chapterPaths(probe, book.filePath)
                    (enumerated + book.filePath + progressPaths).distinct()
                }
                else -> progressPaths.ifEmpty { listOf(book.filePath) }
            }

            val probe = ArchiveEngine(context.applicationContext)
            val built = paths.map { path ->
                val key = SRQueueItem.chapterKey(bookId, path)
                val pageCount = try {
                    probe.listPages(path).size
                } catch (_: Exception) {
                    0
                }
                // ★ F4.4：入队打代际戳（重启续跑不改写已有条目的代际——只有显式重新入队才刷新）
                SRQueueItem(bookId, bookTitle, path, key, pageCount, enqueuedAt = System.currentTimeMillis())
            }
            // 重新入队 = 从头重做：队列里已有同章旧项时整体替换（processedPages 归零），
            // 否则残留的旧进度会让用户看到"从中间开始"。★ F4.3：经 store 锁内原子读改写。
            var addedPages = 0
            if (built.isNotEmpty()) {
                SrCore.queueStore.mutate { current ->
                    val next = current.toMutableList()
                    built.forEach { fresh ->
                        val oldIdx = next.indexOfFirst { it.chapterKey == fresh.chapterKey }
                        if (oldIdx >= 0) next[oldIdx] = fresh else {
                            next.add(fresh)
                            addedPages += fresh.totalPages
                        }
                    }
                    next
                }
            }
            built.size to addedPages
        } catch (e: Exception) {
            Log.e(TAG, "enqueue book $bookId failed", e)
            0 to 0
        }
    }

    /**
     * ★ 整本入队的章节枚举（2026-10-02）：书根下的子目录/子压缩包=章。
     *  路径构造与 Reader.openItem 严格同构（dir=`d:auth|tree|doc`、子压缩包=
     *  `a:auth|tree|doc|ext`、zip 内=`z:archive//sub/`）——chapterKey 必须与阅读会话
     *  同源，否则批量产物目录与显示链对不上。子项是否含页由 listPages 判定（IO）。
     */
    private fun chapterPaths(engine: ArchiveEngine, rootPath: String): List<String> {
        val candidates = LinkedHashSet<String>()
        for (item in engine.children(rootPath)) {
            if (item.auth == "smb" || item.auth == "webdav") continue
            val childPath = when {
                rootPath.startsWith("z:") -> {
                    val inner = rootPath.removePrefix("z:")
                    val sep = inner.indexOf("//")
                    if (sep < 0) null
                    else if (!item.dir) null // zip 内嵌套文件章 v1 不枚举（阅读入口也不直接开）
                    else {
                        val archivePath = inner.substring(0, sep)
                        val sub = inner.substring(sep + 2)
                        val base = if (sub.isEmpty()) "" else if (sub.endsWith('/')) sub else "$sub/"
                        "z:${archivePath}//${base}${item.name}/"
                    }
                }
                item.dir -> "d:${item.auth}|${item.tree}|${item.doc}"
                item.name.contains('.') ->
                    "a:${item.auth}|${item.tree}|${item.doc}|${item.name.substringAfterLast('.').lowercase()}"
                else -> null
            } ?: continue
            candidates.add(childPath)
        }
        // 过滤空章（空目录/无图压缩包）；逐项 listPages 是 IO，调用方须在 IO 线程
        return candidates.filter { p ->
            try {
                engine.listPages(p).isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }
    }

    fun remove(context: Context, chapterKey: String) {
        // ★ F4.3：原子读改写
        SrCore.queueStore.mutate { it.filterNot { item -> item.chapterKey == chapterKey } }
    }

    fun clear(context: Context) {
        SrCore.queueStore.clear()
        try {
            context.stopService(Intent(context, SrBatchService::class.java))
        } catch (_: Exception) {}
    }

    private fun startService(context: Context) {
        try {
            context.startForegroundService(Intent(context, SrBatchService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed: ${e.message}")
            try {
                context.startService(Intent(context, SrBatchService::class.java))
            } catch (_: Exception) {}
        }
    }
}
