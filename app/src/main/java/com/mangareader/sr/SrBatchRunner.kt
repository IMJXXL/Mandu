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
            com.mangareader.DiagTracer.markStatic("sr: enq n=${books.size} ch=$chapters p=$pages")
            onDone(EnqueueResult(chapters, pages))
        }
    }

    /** IO 协程内执行：解析章节列表 → 去重入队。返回 (新增章数, 总页数) */
    private suspend fun enqueueBookInternal(
        context: Context, bookId: Long, bookTitle: String, includeUnread: Boolean,
    ): Pair<Int, Int> {
        return try {
            val dao = MangaDatabase.getDatabase(context).mangaDao()
            val book = dao.getBookById(bookId)
                ?: run {
                    com.mangareader.DiagTracer.markStatic("sr: enq nobook id=$bookId")
                    return 0 to 0
                }
            val progressPaths = try {
                dao.getAllProgressForBook(bookId).map { it.path }
            } catch (_: Exception) {
                emptyList()
            }
            val paths = when {
                // ★ 网络书（2026-10-05，用户实测"已缓存的 WebDAV/SMB 书不能加入离线"）：
                //   章节集合 = 读过的章 + 书根本身（两模式同构）。远程目录书下的子档暂不
                //   枚举——与本地单文件书的退化行为一致；章路径必须与阅读 navStack 字符串
                //   同源，否则批量产物目录与显示链对不上（chapterKey 为准）。
                book.filePath.startsWith("smb:") || book.filePath.startsWith("webdav:") ->
                    (progressPaths + book.filePath).distinct()
                // ★ 整本模式（2026-10-02）：枚举书根下全部章节路径；枚举为空（单文件书/
                //   无子章）退化为书根本身一卷。与读过的章合并去重（chapterKey 为准）。
                includeUnread -> {
                    val probe = ArchiveEngine(context.applicationContext)
                    val enumerated = chapterPaths(probe, book.filePath)
                    (enumerated + book.filePath + progressPaths).distinct()
                }
                // ★ 0 进度死区修复（2026-10-03 用户实测"不能加入队列"）：默认模式只入
                //   读过的章，从未读过的书 progressPaths 为空 → 退化书根 listPages——对
                //   文件夹/多章压缩包书得 0 页，被 totalPages>0 滤光 → Toast"没有找到可
                //   处理的页面"。未读书没有"跳过未读"可言，直接退化为整本枚举才符合
                //   按钮本意；部分读过的书仍只入读过的章（省存储的语义不变）。
                else -> {
                    if (progressPaths.isNotEmpty()) progressPaths
                    else {
                        val probe = ArchiveEngine(context.applicationContext)
                        (chapterPaths(probe, book.filePath) + book.filePath).distinct()
                    }
                }
            }

            val probe = ArchiveEngine(context.applicationContext)
            val built = paths.map { path ->
                val key = SRQueueItem.chapterKey(bookId, path)
                val pageCount = when {
                    // ★ 网络书页数探测走临时远程会话（zip/epub/pdf 开包即定；tar/rar/7z
                    //   最多 8s 采样）——已缓存的档打开是本地盘速，未缓存的只拉中央目录。
                    path.startsWith("smb:") || path.startsWith("webdav:") -> remoteChapterPageCount(path)
                    else -> try {
                        probe.listPages(path).size
                    } catch (_: Exception) {
                        0
                    }
                }
                // ★ F4.4：入队打代际戳（重启续跑不改写已有条目的代际——只有显式重新入队才刷新）
                SRQueueItem(bookId, bookTitle, path, key, pageCount, enqueuedAt = System.currentTimeMillis())
            }
                // ★ 格式白名单放开后（2026-10-03）：真读不了的格式 listPages 返回 0 页，
                //   不入队——让"没有找到可处理的页面"提示如实出现，而不是排队一个空章
                .filter { it.totalPages > 0 }
            if (built.isEmpty()) {
                // 诊断短码：入队 0 章时报告书根形态/模式/已有进度章数——下份日志直接定位卡点
                val kind = when {
                    book.filePath.startsWith("a:") -> "a"
                    book.filePath.startsWith("d:") -> "d"
                    book.filePath.startsWith("z:") -> "z"
                    book.filePath.startsWith("smb:") -> "smb"
                    book.filePath.startsWith("webdav:") -> "webdav"
                    book.filePath.startsWith("komga:") -> "komga"
                    else -> "?"
                }
                com.mangareader.DiagTracer.markStatic(
                    "sr: enq0 kind=$kind whole=$includeUnread prog=${progressPaths.size}",
                )
            }
            // 重新入队 = 从头重做：队列里已有同章旧项时整体替换（processedPages 归零），
            // 否则残留的旧进度会让用户看到"从中间开始"。★ F4.3：经 store 锁内原子读改写。
            var addedPages = 0
            if (built.isNotEmpty()) {
                SrCore.queueStore.mutate { current ->
                    val next = current.toMutableList()
                    built.forEach { fresh ->
                        val oldIdx = next.indexOfFirst { it.chapterKey == fresh.chapterKey }
                        if (oldIdx >= 0) {
                            next[oldIdx] = fresh
                            // ★ 审计修复：重入队被替换的旧章页数也计入——否则 Toast 出现"N 章 0 页"
                            addedPages += fresh.totalPages
                        } else {
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
            com.mangareader.DiagTracer.markStatic("sr: enq ex ${e.javaClass.simpleName}")
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
        // ★ a: 文件根一律先试 listPages（2026-10-03 用户拍板放开格式白名单）：
        //   能读出页 = 整本一章（mobi 系走 libmobi、epub 走 zip 分支）；
        //   读不出页 = 空列表，由入队层过滤并给诚实提示。children() 对文件型 doc 恒为空，不必再走。
        if (rootPath.startsWith("a:")) {
            return try {
                if (engine.listPages(rootPath).isNotEmpty()) listOf(rootPath) else emptyList()
            } catch (e: Exception) {
                com.mangareader.DiagTracer.markStatic("sr: enq rootex ${e.javaClass.simpleName}")
                emptyList()
            }
        }
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

    /**
     * ★ 网络书页数探测（2026-10-05）：开临时远程会话等页数就绪（attach 内部已做稳定
     *  采样/免采样），随开随关——与阅读会话完全同构，缓存命中的档是本地盘读。
     *  打不开（服务器下线/路径失效/认证失败）返回 0，由入队层过滤并给诚实提示。
     */
    private suspend fun remoteChapterPageCount(path: String): Int = try {
        when {
            path.startsWith("webdav:") -> com.mangareader.webdavbridge.WebDavPaths.parseBookPath(path)?.let { ref ->
                com.mangareader.webdavbridge.WebDavBookRouter.openTransient(ref) { _, supply -> supply.pageCount }
            } ?: 0
            path.startsWith("smb:") -> com.mangareader.smbbridge.SmbPaths.parseBookPath(path)?.let { ref ->
                com.mangareader.smbbridge.SmbBookRouter.openTransient(ref) { _, supply -> supply.pageCount }
            } ?: 0
            else -> 0
        }
    } catch (e: Exception) {
        Log.d(TAG, "remote probe failed: ${e.message}")
        com.mangareader.DiagTracer.markStatic("sr: enq probe0 ${e.javaClass.simpleName}")
        0
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
