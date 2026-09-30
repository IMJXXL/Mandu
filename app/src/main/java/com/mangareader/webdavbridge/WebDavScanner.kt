package com.mangareader.webdavbridge

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import com.mangareader.data.Book
import com.mangareader.data.MangaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 书架扫描（design.md 2.2.2 WebDavScanner / spec 5.8）。
 * 与 SmbScanner 对称：递归 PROPFIND 列目录（WebDavGateway.listDirectory 自带
 * NetworkFolderIndexCache 增量缓存），**无 API 守卫**（API 26+ 全可用）。
 * filePath = "webdav:{serverId}:{relativePath}"（无 share 段）。
 */
object WebDavScanner {

    val SCANNABLE_FORMATS: Set<String> = setOf(
        "zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "pdf", "epub", "mobi", "azw3",
    )

    private const val MAX_DEPTH = 8
    private const val MAX_BOOKS = 20_000
    private const val TAG = "WebDavScanner"

    suspend fun scanWebDavServer(sourceId: Long, onProgress: ((Int) -> Unit)? = null): Int =
        withContext(Dispatchers.IO) {
            val source = WebDavRepository.load(sourceId) ?: return@withContext 0
            val password = try { WebDavPasswordStore.get(source.id) } catch (_: Throwable) { "" }

            val ctx = ScanCtx(source, password)
            try {
                // 从空串起步：WebDavClient.rootUrl/absoluteUrl 内部已拼 source.pathPrefix
                walk(ctx, "", depth = 0, onProgress = onProgress)
                WebDavRepository.markOk(sourceId)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "scan aborted: ${t.message}")
                WebDavRepository.markError(sourceId, t.message?.take(200) ?: "扫描中断")
            }

            if (ctx.discovered.isEmpty()) return@withContext 0
            val dao = WebDavDbBridge.mangaDatabase?.mangaDao() ?: return@withContext 0
            // ★ 按 filePath 去重（对齐 SmbScanner）：insertBooksBatch 的 IGNORE 检测主键 id，
            //   新 Book id=0 不冲突，每次都"新增"。先查已有 filePath 集合过滤。
            val existing = dao.getWebDavBooks(sourceId)
            repairZipFolderBooks(dao, existing)
            val existingPaths = existing.map { it.filePath }.toHashSet()
            val toInsert = ctx.discovered.values.filter { it.filePath !in existingPaths }
            val rows = if (toInsert.isNotEmpty()) dao.insertBooksWithStableId(toInsert) else emptyList()
            // ★ 清理陈旧条目：服务器上已不存在且无阅读进度/本地下载/收藏的远程书
            val newPaths = ctx.discovered.keys
            val stale = existing.filter {
                it.source == "webdav" && it.filePath !in newPaths &&
                    it.originalPath == null && !it.isFavorite &&
                    it.readPages == 0 && it.lastReadAt == null
            }
            for (book in stale) {
                runCatching { dao.deleteBookByPath(book.filePath) }
            }
            val inserted = rows.count { it != -1L }
            android.util.Log.i(
                TAG,
                "scan done: discovered=${ctx.discovered.size} inserted=$inserted pruned=${stale.size} skippedDirs=${ctx.skipped.get()}",
            )
            onProgress?.invoke(ctx.discovered.size)
            inserted
        }

    private class ScanCtx(val source: WebDavSourceEntity, val password: String) {
        val discovered = LinkedHashMap<String, Book>()
        val skipped = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private suspend fun walk(ctx: ScanCtx, relDir: String, depth: Int, onProgress: ((Int) -> Unit)?) {
        if (depth > MAX_DEPTH) return
        val entries = runCatching { WebDavGateway.listDirectory(ctx.source, ctx.password, relDir, useCache = false) }
            .onFailure { ctx.skipped.incrementAndGet() }
            .getOrDefault(emptyList())
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
                is BrowseEntryRemote.VideoFile -> Unit
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
        val entries = runCatching { WebDavGateway.listDirectory(ctx.source, ctx.password, relDir, useCache = false) }
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
                    coverPath = WebDavPaths.COVER_SCHEME + book.filePath.removePrefix(WebDavPaths.SCHEME),
                ),
            )
            fixed++
        }
        if (fixed > 0) android.util.Log.i(TAG, "repaired $fixed zip-as-dir folder-books to archive books")
    }

    /** `webdav:1/a.zip[/inner|:inner]` → "zip"：路径中第一个 zip/cbz 文件段的扩展名（无则 null） */
    private fun zipExtInPath(filePath: String): String? {
        val body = filePath.removePrefix(WebDavPaths.SCHEME).substringAfter(':')
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
        val filePath = WebDavPaths.bookPath(ctx.source.id, remotePath)
        if (filePath in ctx.discovered) return
        ctx.discovered[filePath] = Book(
            title = title.ifEmpty { remotePath.substringAfterLast('/') },
            filePath = filePath,
            fileType = ext,
            coverPath = WebDavPaths.coverPath(ctx.source.id, coverRemote),
            totalPages = totalPages ?: 0,
            source = "webdav",
        )
    }

    private fun joinDir(dir: String, child: String): String =
        if (dir.isEmpty()) child.trim('/') else "${dir.trim('/')}/${child.trim('/')}"

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    private fun titleOf(name: String): String {
        val base = name.substringBefore(':').substringAfterLast('/')
        // ★ 智能解析文件名（默认开，本地漫画设置可关）：干净书名+v01 短缀+繁转简
        val appCtx = runCatching { com.mangareader.App.instance() }.getOrNull()
        val sp = runCatching { appCtx!!.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE) }.getOrNull()
        val enabled = sp?.getBoolean("smart_title_webdav", false) ?: false
        val t2s = sp?.getBoolean("t2s_enabled", false) ?: false
        return if (enabled) com.mangareader.util.BookTitleParser.displayTitle(base, t2s)
        else base.substringBeforeLast('.')
    }
}
