package com.mangareader.smbbridge

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            Toast.makeText(ctx, "请先在 SMB 或 WebDAV 设置 → 下载目录 中设置保存路径", Toast.LENGTH_LONG).show()
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

/**
 * ★ "需全量下载"对话框统一宿主（2026-09-19）。
 * 原先只挂在书架：浏览页点 mobi/azw3 → 阅读器弹 Unsupported → 立即 back，
 * 书架不在组合里 → 对话框永远不出现（"点击没有书架一样的行为"）。
 * 书架 / SMB 浏览 / WebDAV 浏览各挂一份，同一路由栈同一时刻只有一个存活，不会重复弹窗。
 */
@androidx.compose.runtime.Composable
fun RemoteDownloadDialogHost() {
    val remoteDownloadInfo by RemoteDownloadDialogState.dialogState.collectAsState()
    val remoteDownloadBusy by RemoteDownloadDialogState.downloadBusy.collectAsState()
    if (remoteDownloadInfo != null) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!remoteDownloadBusy) RemoteDownloadDialogState.dismissDialog() },
            title = { androidx.compose.material3.Text("需要全量下载") },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.Text("此格式（mobi / azw3 等）需要完整下载到本地后才能查看。")
                    if (remoteDownloadBusy) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            androidx.compose.material3.Text(
                                "正在下载…",
                                fontSize = 13.sp,
                                color = androidx.compose.ui.graphics.Color(0xFF888888),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { RemoteDownloadDialogState.startDownload(ctx) },
                    enabled = !remoteDownloadBusy,
                ) { androidx.compose.material3.Text(if (remoteDownloadBusy) "下载中…" else "下载") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { RemoteDownloadDialogState.dismissDialog() },
                    enabled = !remoteDownloadBusy,
                ) { androidx.compose.material3.Text("取消") }
            },
        )
    }
}
