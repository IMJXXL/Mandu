package com.mangareader.smbbridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import com.hippo.ehviewer.Settings
import com.mangareader.data.MangaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteDownloadInfo(
    val filePath: String,
    val kind: String,
    val bookId: Long,
    val bookTitle: String,
)

object RemoteDownloadDialogState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _dialogState = MutableStateFlow<RemoteDownloadInfo?>(null)
    val dialogState: StateFlow<RemoteDownloadInfo?> = _dialogState.asStateFlow()

    private val _downloadBusy = MutableStateFlow(false)
    val downloadBusy: StateFlow<Boolean> = _downloadBusy.asStateFlow()

    fun showDialog(filePath: String, kind: String, bookId: Long, bookTitle: String) {
        _dialogState.value = RemoteDownloadInfo(filePath, kind, bookId, bookTitle)
    }

    fun dismissDialog() {
        _dialogState.value = null
    }

    fun startDownload(ctx: Context) {
        val info = _dialogState.value ?: return
        if (_downloadBusy.value) return

        val treeUriStr = Settings.smbDownloadDirTreeUri.value
        if (treeUriStr.isNullOrEmpty()) {
            _dialogState.value = null
            Toast.makeText(ctx, "请先在浏览页设置下载目录", Toast.LENGTH_LONG).show()
            return
        }

        _downloadBusy.value = true
        _dialogState.value = null

        val title = info.bookTitle.ifBlank { info.filePath.substringAfterLast('/') }
        val taskId = SmbDownloadQueue.enqueue(
            DownloadTask(
                id = 0,
                title = title,
                sourceKind = info.kind,
                sourcePath = info.filePath,
                status = DownloadStatus.Downloading,
                bookId = info.bookId,
            )
        )

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                downloadToSafDir(ctx, info, taskId, treeUriStr)
            }
            _downloadBusy.value = false
            if (result == null) {
                SmbDownloadQueue.markFailed(taskId, "下载失败")
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "下载失败，可重试", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            android.util.Log.i("RemoteDL", "download completed: $result")
            SmbDownloadQueue.markCompleted(taskId, result)
            val fileSize = runCatching { java.io.File(result).length() }.getOrDefault(0L)
            SmbDownloadQueue.updateTask(taskId) { it.copy(totalBytes = fileSize, downloadedBytes = fileSize) }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "下载完成，点击书籍阅读", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun downloadToSafDir(
        ctx: Context,
        info: RemoteDownloadInfo,
        taskId: Long,
        treeUriStr: String,
    ): String? {
        val fileName = info.filePath.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val treeUri = Uri.parse(treeUriStr)

        val cachePath = try {
            if (info.kind == "smb")
                SmbUnsupportedFormatHandler.downloadWhole(info.filePath) { p ->
                    SmbDownloadQueue.updateTask(taskId) { it.copy(progress = p) }
                }
            else
                com.mangareader.webdavbridge.WebDavUnsupportedFormatHandler.downloadWhole(info.filePath) { p ->
                    SmbDownloadQueue.updateTask(taskId) { it.copy(progress = p) }
                }
        } catch (t: Throwable) {
            android.util.Log.w("RemoteDL", "downloadWhole failed", t)
            return null
        } ?: return null

        val srcFile = java.io.File(cachePath)
        if (!srcFile.exists()) return null

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

        val destUri = DocumentsContract.createDocument(ctx.contentResolver, treeUri, "application/octet-stream", fileName)
            ?: return null
        ctx.contentResolver.openOutputStream(destUri)?.use { out ->
            srcFile.inputStream().use { it.copyTo(out) }
        } ?: return null

        val docId = DocumentsContract.getDocumentId(destUri)
        val treePath = treeUri.lastPathSegment?.removePrefix("tree/") ?: ""
        val auth = treeUri.authority ?: ""
        val safPath = "a:$auth|$treePath|$docId|$ext"

        val dao = MangaDatabase.getDatabase(ctx).mangaDao()
        val book = withContext(Dispatchers.IO) { dao.getBookById(info.bookId) }
        if (book != null) {
            withContext(Dispatchers.IO) {
                runCatching { dao.updateBook(book.copy(filePath = safPath, source = "local", fileType = ext)) }
            }
        }
        return safPath
    }
}
