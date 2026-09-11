package com.mangareader.smbbridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import com.hippo.ehviewer.Settings
import com.mangareader.data.MangaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RemoteDownloadExecutor {

    suspend fun execute(taskId: Long, task: DownloadTask, ctx: Context): String? = withContext(Dispatchers.IO) {
        val treeUriStr = Settings.smbDownloadDirTreeUri.value
        if (treeUriStr.isNullOrEmpty()) {
            SmbDownloadQueue.markFailed(taskId, "未设置下载目录")
            return@withContext null
        }

        val fileName = task.sourcePath.substringAfterLast('/')
        val treeUri = Uri.parse(treeUriStr)

        val cachePath = try {
            if (task.sourceKind == "smb")
                SmbUnsupportedFormatHandler.downloadWhole(task.sourcePath) { p ->
                    SmbDownloadQueue.updateProgressFloat(taskId, p)
                }
            else
                com.mangareader.webdavbridge.WebDavUnsupportedFormatHandler.downloadWhole(task.sourcePath) { p ->
                    SmbDownloadQueue.updateProgressFloat(taskId, p)
                }
        } catch (t: Throwable) {
            android.util.Log.w("RemoteDLExec", "downloadWhole failed", t)
            SmbDownloadQueue.markFailed(taskId, "下载失败：${t.message?.take(80) ?: "未知错误"}")
            return@withContext null
        }
        if (cachePath == null) {
            SmbDownloadQueue.markFailed(taskId, "下载失败：空间不足或连接中断")
            return@withContext null
        }

        val srcFile = java.io.File(cachePath)
        if (!srcFile.exists()) {
            SmbDownloadQueue.markFailed(taskId, "缓存文件不存在")
            return@withContext null
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        ctx.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == fileName) {
                    val existingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(1))
                    runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, existingUri) }
                }
            }
        }

        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val destUri = DocumentsContract.createDocument(ctx.contentResolver, parentDocUri, "application/octet-stream", fileName)
        if (destUri == null) {
            SmbDownloadQueue.markFailed(taskId, "无法创建目标文件")
            return@withContext null
        }
        val out = ctx.contentResolver.openOutputStream(destUri)
        if (out == null) {
            SmbDownloadQueue.markFailed(taskId, "无法打开输出流")
            return@withContext null
        }
        out.use { o -> srcFile.inputStream().use { it.copyTo(o) } }

        val docId = DocumentsContract.getDocumentId(destUri)
        val treePath = treeUri.lastPathSegment?.removePrefix("tree/") ?: ""
        val auth = treeUri.authority ?: ""
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val safPath = "a:$auth|$treePath|$docId|$ext"
        val localCoverPath = "cv:$auth|$treePath|$docId|$fileName"
        // ★ originalPath 存 SAF 路径（a:...），enterReader 的 a: 分支走 engine.listPages（libmobi）
        //   能读 mobi/azw3；listPages 返回空时回退文件系统路径走 enterLocalArchiveReader（libarchive）读 zip/cbz。
        val dao = MangaDatabase.getDatabase(ctx).mangaDao()
        if (task.bookId > 0) {
            runCatching { dao.rehomeBookToLocal(task.bookId, safPath) }
            runCatching { dao.updateBookCoverPath(task.bookId, localCoverPath) }
            coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
        } else {
            val book = dao.getBookByPath(task.sourcePath)
            if (book != null) {
                runCatching { dao.rehomeBookToLocal(book.id, safPath) }
                runCatching { dao.updateBookCoverPath(book.id, localCoverPath) }
                coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
            }
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(ctx, "下载完成：$fileName", Toast.LENGTH_SHORT).show()
        }
        safPath
    }
}
