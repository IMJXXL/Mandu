package com.mangareader.smbbridge

import android.content.Context
import android.widget.Toast
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteDownloadInfo(
    val filePath: String,
    val kind: String,
    val bookId: Long,
    val bookTitle: String,
)

object RemoteDownloadDialogState {

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
            Toast.makeText(ctx, "请先在 SMB 设置 → 下载目录 中设置保存路径", Toast.LENGTH_LONG).show()
            return
        }

        _downloadBusy.value = true
        _dialogState.value = null

        val title = info.bookTitle.ifBlank { info.filePath.substringAfterLast('/') }
        SmbDownloadQueue.enqueue(
            DownloadTask(
                id = 0,
                title = title,
                sourceKind = info.kind,
                sourcePath = info.filePath,
                status = DownloadStatus.Downloading,
                bookId = info.bookId,
            )
        )
        _downloadBusy.value = false
        Toast.makeText(ctx, "已加入下载队列", Toast.LENGTH_SHORT).show()
    }

}
