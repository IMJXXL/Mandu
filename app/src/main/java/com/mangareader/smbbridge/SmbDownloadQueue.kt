package com.mangareader.smbbridge

import android.content.Context
import com.mangareader.data.DownloadTaskEntity
import com.mangareader.data.MangaDatabase
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
    val bookId: Long = 0L,
)

object SmbDownloadQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var nextId = 1L

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val jobs = mutableMapOf<Long, Job>()
    private var dao: com.mangareader.data.MangaDao? = null
    private var appCtx: Context? = null
    private var downloadFn: (suspend (taskId: Long, task: DownloadTask, ctx: Context) -> String?)? = null

    fun init(ctx: Context) {
        if (dao != null) return
        appCtx = ctx.applicationContext
        val d = MangaDatabase.getDatabase(ctx).mangaDao()
        dao = d
        scope.launch {
            val entities = d.getAllDownloadTasksList()
            val loaded = entities.map { it.toTask() }
            if (loaded.isNotEmpty()) {
                nextId = (entities.maxOf { it.id } + 1).coerceAtLeast(nextId)
                _tasks.value = loaded
            }
        }
    }

    fun setDownloadFn(fn: suspend (taskId: Long, task: DownloadTask, ctx: Context) -> String?) {
        downloadFn = fn
    }

    private fun DownloadTaskEntity.toTask() = DownloadTask(
        id = id, title = title, sourceKind = sourceKind, sourcePath = sourcePath,
        localPath = localPath, status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.Queued),
        progress = progress, error = error, totalBytes = totalBytes, downloadedBytes = downloadedBytes, bookId = bookId,
    )

    private fun DownloadTask.toEntity() = DownloadTaskEntity(
        id = id, title = title, sourceKind = sourceKind, sourcePath = sourcePath,
        localPath = localPath, status = status.name, progress = progress, error = error,
        totalBytes = totalBytes, downloadedBytes = downloadedBytes, bookId = bookId,
    )

    fun enqueue(task: DownloadTask, autoStart: Boolean = true): Long {
        val id = nextId++
        val taskWithId = task.copy(id = id, status = DownloadStatus.Downloading)
        _tasks.value = _tasks.value + taskWithId
        scope.launch { dao?.insertDownloadTask(taskWithId.toEntity()) }
        if (autoStart) startDownloadJob(id, taskWithId)
        return id
    }

    private fun startDownloadJob(id: Long, task: DownloadTask) {
        val fn = downloadFn ?: return
        val ctx = appCtx ?: return
        jobs[id]?.cancel()
        jobs[id] = scope.launch {
            try {
                updateTask(id) { it.copy(status = DownloadStatus.Downloading, error = null) }
                val result = fn(id, task, ctx)
                if (result != null) {
                    markCompleted(id, result)
                } else {
                    val cur = _tasks.value.find { it.id == id }
                    if (cur?.status != DownloadStatus.Failed) markFailed(id, "下载失败")
                }
            } catch (t: Throwable) {
                markFailed(id, t.message ?: "下载失败")
            }
            mutex.withLock { jobs.remove(id) }
        }
    }

    fun pause(id: Long) {
        scope.launch { mutex.withLock { jobs[id]?.cancel(); jobs.remove(id) } }
        updateTask(id) { it.copy(status = DownloadStatus.Paused) }
    }

    fun resume(id: Long) {
        val task = _tasks.value.find { it.id == id } ?: return
        startDownloadJob(id, task)
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
        scope.launch { dao?.deleteDownloadTask(id) }
    }

    fun removeByBookId(bookId: Long) {
        _tasks.value.filter { it.bookId == bookId }.forEach { task ->
            scope.launch { mutex.withLock { jobs[task.id]?.cancel(); jobs.remove(task.id) } }
        }
        _tasks.value = _tasks.value.filterNot { it.bookId == bookId }
        scope.launch { dao?.deleteDownloadTaskByBookId(bookId) }
    }

    fun clearCompleted(deleteFile: Boolean = false) {
        val toRemove = _tasks.value.filter { it.status == DownloadStatus.Completed || it.status == DownloadStatus.Failed }
        if (deleteFile) {
            toRemove.forEach { task ->
                task.localPath?.let { path ->
                    if (path.startsWith("a:")) {
                        runCatching { /* SAF path: skip file delete */ }
                    } else {
                        runCatching { java.io.File(path).delete() }
                    }
                }
            }
        }
        _tasks.value = _tasks.value.filterNot { it in toRemove }
        scope.launch {
            mutex.withLock { toRemove.forEach { jobs.remove(it.id) } }
            dao?.clearFinishedDownloadTasks()
        }
    }

    fun removeInvalid(dao: com.mangareader.data.MangaDao): List<Long> {
        val invalid = _tasks.value.filter { task ->
            task.status == DownloadStatus.Completed && task.localPath != null &&
            task.bookId > 0 && !java.io.File(task.localPath).exists()
        }
        val bookIds = invalid.map { it.bookId }.distinct()
        invalid.forEach { task ->
            scope.launch { mutex.withLock { jobs[task.id]?.cancel(); jobs.remove(task.id) } }
        }
        _tasks.value = _tasks.value.filterNot { it in invalid }
        scope.launch {
            invalid.forEach { dao?.deleteDownloadTask(it.id) }
            bookIds.forEach { bid ->
                dao?.getBookById(bid)?.let { dao?.deleteBook(it) }
            }
        }
        return bookIds
    }

    fun updateTask(id: Long, transform: (DownloadTask) -> DownloadTask) {
        val old = _tasks.value.find { it.id == id }
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
        if (old != null) {
            val newTask = transform(old)
            scope.launch { dao?.updateDownloadTask(newTask.toEntity()) }
        }
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
        _tasks.value = _tasks.value.map { if (it.id == id) it.copy(downloadedBytes = downloaded, totalBytes = total, progress = pct) else it }
    }

    fun updateProgressFloat(id: Long, p: Float) {
        _tasks.value = _tasks.value.map { if (it.id == id) it.copy(progress = p) else it }
    }
}
