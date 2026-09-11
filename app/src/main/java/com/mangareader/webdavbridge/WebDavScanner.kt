package com.mangareader.webdavbridge

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import com.mangareader.data.Book
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
            val existingPaths = dao.getWebDavBooks(sourceId).map { it.filePath }.toHashSet()
            val toInsert = ctx.discovered.values.filter { it.filePath !in existingPaths }
            if (toInsert.isEmpty()) return@withContext 0
            val rows = dao.insertBooksBatch(toInsert)
            val inserted = rows.count { it != -1L }
            android.util.Log.i(
                TAG,
                "scan done: discovered=${ctx.discovered.size} inserted=$inserted skippedDirs=${ctx.skipped.get()}",
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
        val entries = runCatching { WebDavGateway.listDirectory(ctx.source, ctx.password, relDir) }
            .onFailure { ctx.skipped.incrementAndGet() }
            .getOrDefault(emptyList())
        for (entry in entries) {
            if (ctx.discovered.size >= MAX_BOOKS) return
            when (entry) {
                is BrowseEntryRemote.Directory ->
                    walk(ctx, joinDir(relDir, entry.relativeName), depth + 1, onProgress)
                is BrowseEntryRemote.FolderGallery -> {
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

    private fun titleOf(name: String): String =
        name.substringBefore(':').substringAfterLast('/').substringBeforeLast('.')
}
