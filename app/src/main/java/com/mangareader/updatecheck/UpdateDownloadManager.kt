package com.mangareader.updatecheck

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object UpdateDownloadManager {

    enum class Status { IDLE, DOWNLOADING, DONE, FAILED }

    data class DownloadState(
        val status: Status = Status.IDLE,
        val progress: Float = 0f,
        val message: String = "",
        val mirrorIndex: Int = 0,
        val info: UpdateInfo? = null,
    )

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentJob: Job? = null

    fun isDownloading(): Boolean = _state.value.status == Status.DOWNLOADING

    fun startDownload(ctx: Context, info: UpdateInfo, mirrorIndex: Int = 0) {
        if (isDownloading()) return
        val appCtx = ctx.applicationContext
        val app = appCtx as? com.mangareader.App ?: return

        acquireWakeLock(appCtx)
        _state.value = DownloadState(status = Status.DOWNLOADING, progress = 0f, message = "正在连接…", mirrorIndex = mirrorIndex, info = info)

        currentJob = app.appScope.launch(Dispatchers.IO) {
            var downloading = false
            val mirror = UpdateConstants.MIRRORS.getOrNull(mirrorIndex)?.first ?: ""
            val file = ApkDownloader.download(info.downloadUrl, appCtx, info.version, info.apkSize, mirror) { p ->
                val msg = if (!downloading) { downloading = true; "下载中…" } else _state.value.message
                _state.value = _state.value.copy(progress = p, message = msg)
            }
            if (file != null) {
                _state.value = _state.value.copy(status = Status.DONE, progress = 1f, message = "下载完成，正在调起安装…")
                val ok = ApkInstaller.install(appCtx, file)
                if (!ok) {
                    mainHandler.post { Toast.makeText(appCtx, "安装失败，请重试", Toast.LENGTH_SHORT).show() }
                }
            } else {
                _state.value = _state.value.copy(status = Status.FAILED, message = "下载失败，可换线路重试")
            }
            releaseWakeLock()
            currentJob = null
        }
    }

    fun switchMirror(ctx: Context, mirrorIndex: Int) {
        currentJob?.cancel()
        releaseWakeLock()
        val info = _state.value.info ?: return
        _state.value = _state.value.copy(status = Status.IDLE, progress = 0f)
        startDownload(ctx, info, mirrorIndex)
    }

    fun reset() {
        if (_state.value.status != Status.DOWNLOADING) {
            _state.value = DownloadState()
        }
    }

    private fun acquireWakeLock(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MangaReader:UpdateDownload"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
            wakeLock = null
        } catch (_: Exception) {}
    }
}