package com.mangareader.smbbridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.ehviewer.Settings
import com.mangareader.data.Book
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
        val ext = fileName.substringAfterLast('.', "").lowercase()
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
        val safPath = "a:$auth|$treePath|$docId|$ext"
        val coverPath = "cv:$auth|$treePath|$docId|$fileName"

        val dao = MangaDatabase.getDatabase(ctx).mangaDao()
        if (task.bookId > 0) {
            val book = dao.getBookById(task.bookId)
            if (book != null) {
                runCatching { dao.updateBook(book.copy(filePath = safPath, source = "local", fileType = ext, coverPath = coverPath, originalPath = task.sourcePath)) }
                coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
            }
        } else {
            val book = dao.getBookByPath(task.sourcePath)
            if (book != null) {
                runCatching { dao.updateBook(book.copy(filePath = safPath, source = "local", fileType = ext, coverPath = coverPath, originalPath = task.sourcePath)) }
            } else {
                runCatching { dao.insertBook(Book(title = task.title, filePath = safPath, fileType = ext, source = "local", coverPath = coverPath, originalPath = task.sourcePath)) }
            }
            coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
        }
        safPath
    }
}
