package com.mangareader.smbbridge

import com.ehviewer.core.database.model.SmbSourceEntity
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.mangareader.data.Book
import com.mangareader.data.MangaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 书架扫描（design.md 2.2.2 SmbScanner / spec 5.2）。
 *
 * - 从 source.pathPrefix 起递归列举；SmbGateway.listDirectory 自带 NetworkFolderIndexCache
 *   增量缓存（mtime/size 对比在缓存层内部完成）
 * - 识别格式：zip/cbz/rar/cbr/7z/cb7/tar/cbt/pdf/epub/mobi/azw3 归档/文档 + 含图片目录（文件夹书）
 * - 入库：Book(source="smb", filePath="smb:{id}:{share}/{rel}")；insertBooksBatch
 *   （OnConflictStrategy.IGNORE 天然去重）；成功 markOk、失败 markError
 * - 扫描不全量下载（仅列目录）；本地书籍（source="local"）零影响
 */
object SmbScanner {

    /** 扫描入库的远程格式（spec 5.2.1 规则 3；cb7 归参考项目的 7z 处理） */
    val SCANNABLE_FORMATS: Set<String> = setOf(
        "zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "pdf", "epub", "mobi", "azw3",
    )

    private const val MAX_DEPTH = 8
    private const val MAX_BOOKS = 20_000
    private const val TAG = "SmbScanner"

    /**
     * 扫描 SMB 服务器，发现漫画写入漫读 DB。
     * @param onProgress 已发现书籍数回调
     * @return 新增书籍数（去重后实际插入行数）
     */
    // ★ 并发守卫 + 启动自动刷新（2026-10-07 方案 A/B）：与 WebDavScanner 对称
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()
    private val autoScanned = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

    fun isScanning(sourceId: Long): Boolean = locks[sourceId]?.isLocked == true

    /** 自动刷新发现新书时的主线程回调（Library 设置，弹汇总 toast） */
    @Volatile var onAutoScanNew: ((Int) -> Unit)? = null

    private val lastSkipped = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private val lastPruned = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    /** 最近一次扫描本源的"目录列出失败"数（护栏披露用；无记录=0） */
    fun lastScanSkippedDirs(sourceId: Long): Int = lastSkipped[sourceId] ?: 0
    /** 最近一次扫描本源清理的失效书数（toast 文案用） */
    fun lastScanPruned(sourceId: Long): Int = lastPruned[sourceId] ?: 0

    suspend fun scanSmbServer(
        sourceId: Long,
        onProgress: ((Int) -> Unit)? = null,
        updateSourceStatus: Boolean = true,
    ): Int = locks.getOrPut(sourceId) { kotlinx.coroutines.sync.Mutex() }.withLock {
        doScan(sourceId, onProgress, updateSourceStatus)
    }

    // ★ P1（2026-10-07 评审）：自动刷新用扫描器自有的进程级 scope——调用方（书架组合）
    //   离开页面销毁 scope 会把扫描协程拦腰取消，而 autoScanned 已标记导致本进程不再补扫
    private val autoScanScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** 启动自动刷新：fire-and-forget（P1）；先 tryLock 成功再标记（P2：启动瞬间手动扫描
     *  持锁时本进程稍后仍可补扫，不吞标记）；每进程每源一次 */
    fun autoScanAsync(sourceId: Long) {
        if (autoScanned.contains(sourceId)) return
        val m = locks.getOrPut(sourceId) { kotlinx.coroutines.sync.Mutex() }
        if (!m.tryLock()) return
        autoScanScope.launch {
            try {
                if (!autoScanned.add(sourceId)) return@launch
                val n = doScan(sourceId, onProgress = null, updateSourceStatus = false)
                if (n > 0) kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onAutoScanNew?.invoke(n)
                }
            } finally { m.unlock() }
        }
    }

    private suspend fun doScan(
        sourceId: Long,
        onProgress: ((Int) -> Unit)?,
        updateSourceStatus: Boolean,
    ): Int = withContext(Dispatchers.IO) {

            val source = SmbRepository.load(sourceId) ?: return@withContext 0
            val password = try { SmbPasswordStore.get(source.id) } catch (_: Throwable) { "" }

            val ctx = ScanCtx(source, password)
            try {
                // 从空串起步：SmbGateway.listDirectory 内部经 resolveLocation 自动拼 source.pathPrefix
                //（server-root 源则从共享列表开始），从 pathPrefix 起步会双重前缀
                walk(ctx, "", depth = 0, onProgress = onProgress)
                lastSkipped[sourceId] = ctx.skipped.get()
                if (updateSourceStatus) SmbRepository.markOk(sourceId)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "scan aborted: ${t.message}")
                // 扫描中断：保留已发现结果（spec 5.2.1 规则 5）；自动路径不标错误
                if (updateSourceStatus) SmbRepository.markError(sourceId, t.message?.take(200) ?: "扫描中断")
            }

            if (ctx.discovered.isEmpty()) return@withContext 0
            val dao = SmbDbBridge.mangaDatabase?.mangaDao() ?: return@withContext 0
            // ★ 按 filePath 去重：insertBooksBatch 的 OnConflictStrategy.IGNORE 检测主键 id，
            //   新 Book id=0 不冲突，每次都"新增"。先查已有 filePath 集合过滤。
            val existing = dao.getSmbBooks(sourceId)
            repairZipFolderBooks(dao, existing)
            val existingPaths = existing.map { it.filePath }.toHashSet()
            val toInsert = ctx.discovered.values.filter { it.filePath !in existingPaths }
            val rows = if (toInsert.isNotEmpty()) dao.insertBooksWithStableId(toInsert) else emptyList()
            // ★ 扫描顺带刷新已入库书封面（只写差异；下载书不覆盖）
            val oldByPath = existing.associateBy { it.filePath }
            ctx.discovered.forEach { (filePath, fresh) ->
                val old = oldByPath[filePath] ?: return@forEach
                val cp = fresh.coverPath
                if (old.originalPath == null && cp != null && old.coverPath != cp) {
                    runCatching { dao.updateBookCoverPath(old.id, cp) }
                }
            }
            // ★ 清理陈旧条目（语义收紧 + 护栏）：服务器上已不存在的远程书一律清理；
            //   有目录失败/清单截断 → 本轮放弃清理防误删
            val newPaths = ctx.discovered.keys
            val stale = existing.filter {
                it.source == "smb" && it.filePath !in newPaths && it.originalPath == null
            }
            val canPrune = ctx.skipped.get() == 0 && ctx.discovered.size < MAX_BOOKS
            if (canPrune) {
                for (book in stale) {
                    android.util.Log.i(TAG, "prune stale: ${book.title} ${book.filePath}")
                    com.mangareader.DiagTracer.markStatic("scan[smb] 清理陈旧书 ×1（详情见 logcat，导出只计次）")
                    runCatching { dao.deleteBookByPath(book.filePath) }
                }
                lastPruned[sourceId] = stale.size
            } else if (stale.isNotEmpty()) {
                android.util.Log.w(TAG, "prune skipped: skippedDirs=${ctx.skipped.get()} discovered=${ctx.discovered.size}")
                com.mangareader.DiagTracer.markStatic(
                    "scan[smb] 放弃清理(护栏): 跳过目录=${ctx.skipped.get()} 服务器现有=${ctx.discovered.size}")
            }
            val inserted = rows.count { it != -1L }
            android.util.Log.i(
                TAG,
                "scan done: discovered=${ctx.discovered.size} inserted=$inserted pruned=${stale.size} skippedDirs=${ctx.skipped.get()} paths=${ctx.discovered.keys.toList()}",
            )
            com.mangareader.DiagTracer.markStatic(
                "scan[smb] 完成: 服务器现有=${ctx.discovered.size} 新增=$inserted 清理=${stale.size} 跳过目录=${ctx.skipped.get()}")
            onProgress?.invoke(ctx.discovered.size)
            inserted
        }

    /** ★ 导出取证（2026-10-07 服务器删书验证）：关键扫描证据同步落 diag_stages，
     *  关于页「导出诊断日志」可见。目录名部分打码（首尾字符+***）：用户可对号，
     *  日志外发不泄露完整内容清单 */
    private fun maskNames(names: List<String>): String = names.joinToString { n ->
        val t = n.trim()
        when {
            t.isEmpty() -> "∅"
            t.length == 1 -> "${t[0]}*"
            t.length == 2 -> "${t[0]}*${t[1]}"
            else -> "${t.first()}***${t.last()}"
        }
    }

    private class ScanCtx(val source: SmbSourceEntity, val password: String) {
        val discovered = LinkedHashMap<String, Book>()
        val skipped = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private suspend fun walk(ctx: ScanCtx, relDir: String, depth: Int, onProgress: ((Int) -> Unit)?) {
        if (depth > MAX_DEPTH) return
        val entries = runCatching { SmbGateway.listDirectoryForScan(ctx.source, ctx.password, relDir) }
            .onFailure {
                ctx.skipped.incrementAndGet()
                if (relDir.isEmpty()) {
                    com.mangareader.DiagTracer.markStatic("scan[smb] 根目录列出失败: ${it.message}")
                }
            }
            .getOrDefault(emptyList())
        if (relDir.isEmpty()) {
            android.util.Log.i(TAG, "root raw listing: ${entries.joinToString { it.name }}")
            com.mangareader.DiagTracer.markStatic(
                "scan[smb] 根目录(${entries.size}): ${maskNames(entries.map { it.name })}")
        }
        for (entry in entries) {
            if (ctx.discovered.size >= MAX_BOOKS) return
            if (entry.virtual) continue
            when (entry) {
                is BrowseEntryRemote.Directory -> {
                    // ★ zip-as-dir：Directory 行可能是 zip/cbz 文件伪装的目录 —— 按真实身份
                    //   （档案书）入库，否则打开时走列目录分支把同一个 zip 再列出来（循环进入）
                    val zipName = ZipAsDirListing.zipFileRootName(entry.relativeName)
                    if (zipName != null) {
                        val zipRel = joinDir(relDir, zipName)
                        putBook(ctx, zipRel, titleOf(entry.name), extOf(zipName), zipRel, null)
                        continue
                    }
                    val remoteDir = joinDir(relDir, entry.relativeName)
                    val coverRemote = findFirstCoverRemote(ctx, remoteDir, 0)
                    if (coverRemote != null) {
                        putBook(
                            ctx,
                            remotePath = remoteDir,
                            title = entry.name.ifEmpty { remoteDir.substringAfterLast('/') },
                            ext = "",
                            coverRemote = coverRemote,
                            totalPages = null,
                        )
                    }
                }
                is BrowseEntryRemote.FolderGallery -> {
                    // ★ zip-as-dir：FolderGallery 行可能是 zip/cbz 的内部清单（relativeName =
                    //   `a.zip` 或 `a.zip/Album`）—— 书架收 zip 文件本身，不是文件夹书
                    val zipName = ZipAsDirListing.zipFileRootName(entry.relativeName)
                    if (zipName != null) {
                        val zipRel = joinDir(relDir, zipName)
                        putBook(ctx, zipRel, titleOf(entry.name), extOf(zipName), zipRel, entry.pageCount.takeIf { it > 0 })
                        continue
                    }
                    if (entry.imageFileNames.isEmpty()) continue
                    val remoteDir = joinDir(relDir, entry.relativeName)
                    putBook(
                        ctx,
                        remotePath = remoteDir,
                        title = entry.name.ifEmpty { remoteDir.substringAfterLast('/') },
                        ext = "",
                        coverRemote = joinDir(remoteDir, entry.imageFileNames.first()),
                        totalPages = entry.pageCount.takeIf { it > 0 },
                    )
                }
                is BrowseEntryRemote.ArchiveGallery -> {
                    val remote = joinDir(relDir, entry.fileName)
                    putBook(ctx, remote, titleOf(entry.name), extOf(entry.name), remote, entry.pageCount.takeIf { it > 0 })
                }
                is BrowseEntryRemote.VideoFile -> Unit // 视频不入库（spec 5.2 只收漫画）
                else -> {
                    val ext = extOf(entry.name)
                    if (ext in SCANNABLE_FORMATS) {
                        val remote = joinDir(relDir, entry.name)
                        putBook(ctx, remote, titleOf(entry.name), ext, remote, null)
                    }
                }
            }
            if (ctx.discovered.isNotEmpty()) onProgress?.invoke(ctx.discovered.size)
        }
    }

    private suspend fun findFirstCoverRemote(ctx: ScanCtx, relDir: String, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        val entries = runCatching { SmbGateway.listDirectory(ctx.source, ctx.password, relDir, useCache = false) }
            .getOrDefault(emptyList())
        for (entry in entries) {
            when (entry) {
                is BrowseEntryRemote.FolderGallery -> {
                    if (entry.imageFileNames.isNotEmpty()) {
                        return joinDir(joinDir(relDir, entry.relativeName), entry.imageFileNames.first())
                    }
                }
                is BrowseEntryRemote.ArchiveGallery -> {
                    return joinDir(relDir, entry.fileName)
                }
                is BrowseEntryRemote.Directory -> {
                    val subCover = findFirstCoverRemote(ctx, joinDir(relDir, entry.relativeName), depth + 1)
                    if (subCover != null) return subCover
                }
                else -> {
                    val ext = extOf(entry.name)
                    if (ext in SCANNABLE_FORMATS) {
                        return joinDir(relDir, entry.name)
                    }
                }
            }
        }
        return null
    }

    /**
     * ★ 历史误存纠正：zip-as-dir 展示曾把 zip/cbz 书存成文件夹书（fileType=""），
     *   打开时被路由进列目录分支（循环进入）。扫描时按路径中的 zip/cbz 段纠正类型，
     *   封面改指档案本身（与 ArchiveGallery 书一致）。filePath 不动：路由的
     *   splitZipBrowsePath 对带内部前缀的路径同样按 zip 档案打开。
     */
    private suspend fun repairZipFolderBooks(dao: MangaDao, existing: List<Book>) {
        var fixed = 0
        for (book in existing) {
            if (book.fileType.isNotEmpty()) continue
            val ext = zipExtInPath(book.filePath) ?: continue
            dao.updateBook(
                book.copy(
                    fileType = ext,
                    coverPath = SmbPaths.COVER_SCHEME + book.filePath.removePrefix(SmbPaths.SCHEME),
                ),
            )
            fixed++
        }
        if (fixed > 0) android.util.Log.i(TAG, "repaired $fixed zip-as-dir folder-books to archive books")
    }

    /** `smb:1:test2/a.zip[/inner|:inner]` → "zip"：路径中第一个 zip/cbz 文件段的扩展名（无则 null） */
    private fun zipExtInPath(filePath: String): String? {
        val body = filePath.removePrefix(SmbPaths.SCHEME).substringAfter(':')
        return body.split('/', '\\').firstNotNullOfOrNull { seg ->
            seg.substringBefore(':').takeIf { ZipAsDirListing.zipFileRootName(it) != null }?.let { extOf(it) }
        }
    }

    private fun putBook(
        ctx: ScanCtx,
        remotePath: String,
        title: String,
        ext: String,
        coverRemote: String,
        totalPages: Int?,
    ) {
        val filePath = SmbPaths.bookPath(ctx.source.id, ctx.source.share, remotePath)
        if (filePath in ctx.discovered) return
        ctx.discovered[filePath] = Book(
            title = title.ifEmpty { remotePath.substringAfterLast('/') },
            filePath = filePath,
            fileType = ext,
            coverPath = SmbPaths.coverPath(ctx.source.id, ctx.source.share, coverRemote),
            totalPages = totalPages ?: 0,
            source = "smb",
        )
    }

    private fun joinDir(dir: String, child: String): String =
        if (dir.isEmpty()) child.trim('/') else "${dir.trim('/')}/${child.trim('/')}"

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    private fun titleOf(name: String): String {
        val base = name.substringBefore(':').substringAfterLast('/')
        val appCtx = runCatching { com.mangareader.App.instance() }.getOrNull()
        val sp = runCatching { appCtx!!.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE) }.getOrNull()
        val enabled = sp?.getBoolean("smart_title_smb", false) ?: false
        val t2s = sp?.getBoolean("t2s_enabled", false) ?: false
        return if (enabled) com.mangareader.util.BookTitleParser.displayTitle(base, t2s)
        else base.substringBeforeLast('.')
    }
}
