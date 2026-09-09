package com.mangareader.smbbridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DownloadStatus { Queued, Downloading, Paused, Completed, Failed }

data class DownloadTask(
    val id: Long,
    val title: String,
    val sourceKind: String,
    val sourcePath: String,
    val localPath: String? = null,
    val status: DownloadStatus = DownloadStatus.Queued,
    val progress: Float = 0f,
    val error: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
)

object SmbDownloadQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var nextId = 1L

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val jobs = mutableMapOf<Long, Job>()

    fun enqueue(task: DownloadTask): Long {
        val id = nextId++
        val taskWithId = task.copy(id = id)
        _tasks.value = _tasks.value + taskWithId
        return id
    }

    fun pause(id: Long) {
        scope.launch { mutex.withLock { jobs[id]?.cancel() } }
        updateTask(id) { it.copy(status = DownloadStatus.Paused) }
    }

    fun resume(id: Long) {
        updateTask(id) { it.copy(status = DownloadStatus.Queued) }
    }

    fun cancel(id: Long) {
        scope.launch { mutex.withLock { jobs[id]?.cancel(); jobs.remove(id) } }
        updateTask(id) { it.copy(status = DownloadStatus.Paused) }
    }

    fun remove(id: Long, deleteFile: Boolean = false) {
        scope.launch { mutex.withLock { jobs[id]?.cancel(); jobs.remove(id) } }
        if (deleteFile) {
            _tasks.value.find { it.id == id }?.localPath?.let { path ->
                runCatching { java.io.File(path).delete() }
            }
        }
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    fun clearCompleted() {
        val toRemove = _tasks.value.filter { it.status == DownloadStatus.Completed || it.status == DownloadStatus.Failed }
        _tasks.value = _tasks.value.filterNot { it in toRemove }
        scope.launch { mutex.withLock { toRemove.forEach { jobs.remove(it.id) } } }
    }

    fun updateTask(id: Long, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    fun markCompleted(id: Long, localPath: String) {
        updateTask(id) { it.copy(status = DownloadStatus.Completed, localPath = localPath, progress = 1f) }
        scope.launch { mutex.withLock { jobs.remove(id) } }
    }

    fun markFailed(id: Long, error: String) {
        updateTask(id) { it.copy(status = DownloadStatus.Failed, error = error) }
        scope.launch { mutex.withLock { jobs.remove(id) } }
    }

    fun updateProgress(id: Long, downloaded: Long, total: Long) {
        val pct = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
        updateTask(id) { it.copy(downloadedBytes = downloaded, totalBytes = total, progress = pct) }
    }
}