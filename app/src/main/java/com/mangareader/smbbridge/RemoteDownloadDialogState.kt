package com.mangareader.smbbridge

import android.content.Context
import android.widget.Toast
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
            )
        )

        scope.launch {
            val localPath = withContext(Dispatchers.IO) {
                try {
                    if (info.kind == "smb")
                        SmbUnsupportedFormatHandler.downloadWhole(info.filePath) { p ->
                            SmbDownloadQueue.updateProgress(taskId, (p * 1000).toLong(), 1000)
                        }
                    else
                        com.mangareader.webdavbridge.WebDavUnsupportedFormatHandler.downloadWhole(info.filePath) { p ->
                            SmbDownloadQueue.updateProgress(taskId, (p * 1000).toLong(), 1000)
                        }
                } catch (t: Throwable) {
                    android.util.Log.w("RemoteDL", "downloadWhole failed", t)
                    null
                }
            }
            _downloadBusy.value = false
            if (localPath == null) {
                SmbDownloadQueue.markFailed(taskId, "下载失败")
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "下载失败，可重试", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val dao = MangaDatabase.getDatabase(ctx).mangaDao()
            withContext(Dispatchers.IO) {
                runCatching { dao.rehomeBookToLocal(info.bookId, localPath) }
            }
            SmbDownloadQueue.markCompleted(taskId, localPath)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "下载完成，点击书籍阅读", Toast.LENGTH_SHORT).show()
            }
        }
    }
}