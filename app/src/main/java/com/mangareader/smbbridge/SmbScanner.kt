package com.mangareader.smbbridge

import com.ehviewer.core.database.model.SmbSourceEntity
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.mangareader.data.Book
import kotlinx.coroutines.Dispatchers
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
    suspend fun scanSmbServer(sourceId: Long, onProgress: ((Int) -> Unit)? = null): Int =
        withContext(Dispatchers.IO) {

            val source = SmbRepository.load(sourceId) ?: return@withContext 0
            val password = try { SmbPasswordStore.get(source.id) } catch (_: Throwable) { "" }

            val ctx = ScanCtx(source, password)
            try {
                // 从空串起步：SmbGateway.listDirectory 内部经 resolveLocation 自动拼 source.pathPrefix
                //（server-root 源则从共享列表开始），从 pathPrefix 起步会双重前缀
                walk(ctx, "", depth = 0, onProgress = onProgress)
                SmbRepository.markOk(sourceId)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "scan aborted: ${t.message}")
                // 扫描中断：保留已发现结果（spec 5.2.1 规则 5）
                SmbRepository.markError(sourceId, t.message?.take(200) ?: "扫描中断")
            }

            if (ctx.discovered.isEmpty()) return@withContext 0
            val dao = SmbDbBridge.mangaDatabase?.mangaDao() ?: return@withContext 0
            // ★ 按 filePath 去重：insertBooksBatch 的 OnConflictStrategy.IGNORE 检测主键 id，
            //   新 Book id=0 不冲突，每次都"新增"。先查已有 filePath 集合过滤。
            val existingPaths = dao.getSmbBooks(sourceId).map { it.filePath }.toHashSet()
            val toInsert = ctx.discovered.values.filter { it.filePath !in existingPaths }
            if (toInsert.isEmpty()) return@withContext 0
            val rows = dao.insertBooksWithStableId(toInsert)
            val inserted = rows.count { it != -1L }
            android.util.Log.i(
                TAG,
                "scan done: discovered=${ctx.discovered.size} inserted=$inserted skippedDirs=${ctx.skipped.get()}",
            )
            onProgress?.invoke(ctx.discovered.size)
            inserted
        }

    private class ScanCtx(val source: SmbSourceEntity, val password: String) {
        val discovered = LinkedHashMap<String, Book>()
        val skipped = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private suspend fun walk(ctx: ScanCtx, relDir: String, depth: Int, onProgress: ((Int) -> Unit)?) {
        if (depth > MAX_DEPTH) return
        val entries = runCatching { SmbGateway.listDirectory(ctx.source, ctx.password, relDir) }
            .onFailure { ctx.skipped.incrementAndGet() }
            .getOrDefault(emptyList())
        for (entry in entries) {
            if (ctx.discovered.size >= MAX_BOOKS) return
            when (entry) {
                is BrowseEntryRemote.Directory -> {
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
        val entries = runCatching { SmbGateway.listDirectory(ctx.source, ctx.password, relDir) }
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

    private fun titleOf(name: String): String =
        name.substringBefore(':').substringAfterLast('/').substringBeforeLast('.')
}
