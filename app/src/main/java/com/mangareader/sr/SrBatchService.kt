package com.mangareader.sr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 批量离线超分前台服务。
 * - 队列持久化在 SRQueueStore（JSON）：App 重启后服务再启动即可断点续做；
 * - 每页产物原子写入 filesDir/page_cache/sr/batch/<书名>/<章节key>/NNN.webp；
 * - 发送进度通知（可取消）；全部完成发完成通知并 stopSelf；
 * - 与阅读共用 SuperResolutionManager.mutex（GPU 独占，自动排队互不打架）。
 */
class SrBatchService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: ArchiveEngine

    // ★ F4.3：主线程 add 与 IO 协程读删并发 → 并发集合（原来是普通 mutableSetOf）
    private val cancelledKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    companion object {
        private const val TAG = "SrBatch"
        private const val CH_PROGRESS = "sr_progress"
        private const val CH_DONE = "sr_done"
        private const val NOTI_PROGRESS = 9001
        private const val NOTI_DONE = 9002
        const val ACTION_CANCEL_ONE = "com.mangareader.sr.CANCEL_ONE"
        const val ACTION_TOGGLE_PAUSE = "com.mangareader.sr.TOGGLE_PAUSE"
        const val EXTRA_CHAPTER_KEY = "chapterKey"

        @Volatile
        var isRunning = false
            private set

        /** 暂停标志：页面粒度停走（当前页算完即停） */
        @Volatile
        var paused = false
            private set

        fun togglePaused() { paused = !paused }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = ArchiveEngine(applicationContext)
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SrCore.initialized) SrCore.init(applicationContext)

        if (intent?.action == ACTION_CANCEL_ONE) {
            intent.getStringExtra(EXTRA_CHAPTER_KEY)?.let { cancelledKeys.add(it) }
            return START_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            togglePaused()
            notifyProgress(if (paused) "已暂停（点通知可继续）" else "继续增强…", 0, 0)
            SrCore.emitBatchPaused(paused)
            return START_STICKY
        }
        if (intent?.action == "CANCEL_ALL") {
            SrBatchRunner.clear(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning) return START_STICKY
        startForegroundSafe(buildProgressNotification("准备批量增强…", 0, 0))
        isRunning = true

        serviceScope.launch {
            try {
                runQueue()
            } catch (e: Exception) {
                Log.e(TAG, "queue loop crashed", e)
            } finally {
                isRunning = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun runQueue() {
        // 引擎按全局设置就位（批量不强制"阅读开关"开着——批量是显式用户指令）
        SrCore.applySettings(null)
        // ★ F4.1：等模型真正就绪再开跑——原来 applySettings 是异步 fire-and-forget，
        //   首章可能撞上未就绪引擎逐页静默跳过（result === original 被护栏拦下）白排队
        runCatching { SrCore.manager.ensureModelLoaded() }
        var waited = 0
        while (!SrCore.manager.isReady && waited < 20_000) {
            kotlinx.coroutines.delay(200)
            waited += 200
        }
        if (!SrCore.manager.isReady) Log.w(TAG, "model not ready after ${waited}ms, run anyway")

        while (true) {
            val queue = SrCore.queueStore.load()
            if (queue.isEmpty()) break

            val item = queue.first()
            val itemGeneration = item.enqueuedAt   // ★ F4.4：本条目入队代际
            if (item.chapterKey in cancelledKeys) {
                SrBatchRunner.remove(applicationContext, item.chapterKey)
                cancelledKeys.remove(item.chapterKey)
                continue
            }

            notifyProgress("正在增强：${item.bookTitle}", item.processedPages, item.totalPages)

            val pages = try {
                engine.listPages(item.path)
            } catch (e: Exception) {
                Log.e(TAG, "listPages failed for ${item.path}", e)
                emptyList()
            }
            if (pages.isEmpty()) {
                // 章节不存在/读不出 → 移出队列防死循环
                SrBatchRunner.remove(applicationContext, item.chapterKey)
                continue
            }

            val total = pages.size
            // 模型一致性检查：章节产物是用别的模型生成的 → 整章删除重算。
            // 无任何模型记录（老产物/未写完章节）→ 保留产物，断点续做不受影响。
            val batchModelKey = SrCore.settings.model.key
            val existingModel = SrCore.diskCache.getBatchChapterModelStamp(item.bookTitle, item.chapterKey)
                ?: SrCore.diskCache.getBatchChapterModelKey(item.bookTitle, item.chapterKey)
            if (existingModel != null && existingModel != batchModelKey) {
                Log.i(TAG, "batch model changed (${existingModel} -> ${batchModelKey}), re-run chapter ${item.chapterKey}")
                SrCore.diskCache.removeBatchChapter(item.bookTitle, item.chapterKey)
            }
            SrCore.diskCache.putBatchChapterModelStamp(item.bookTitle, item.chapterKey, batchModelKey)

            // 进度基线以"磁盘上真实存在的产物数量"为准，不信任队列里的旧数字：
            // 产物被清空后基线归零从头显示；断点续做时自动对准真实进度。
            var processed = SrCore.diskCache.countBatchDonePrefix(item.bookTitle, item.chapterKey, total)
            if (processed != item.processedPages) {
                updateItemProgress(item.chapterKey, processed, total)
                SrCore.emitBatchProgress(item.copy(processedPages = processed, totalPages = total))
            }
            var failedAll = true
            var sawApplicablePage = false

            for (i in 0 until total) {
                // 暂停：页面粒度停走，等恢复
                while (paused) {
                    kotlinx.coroutines.delay(400)
                }
                if (item.chapterKey in cancelledKeys) break

                // 已存在产物（断点续做）直接跳过；只看文件存在与否，不解码整图
                if (SrCore.diskCache.hasBatchPage(item.bookTitle, item.chapterKey, i)) {
                    processed = i + 1
                    continue
                }

                val meta = pages[i]
                if (!SrPipeline.isApplicable(meta)) {
                    processed = i + 1
                    continue
                }
                sawApplicablePage = true

                try {
                    val original = engine.loadPage(meta, item.path) ?: continue
                    if (original.isRecycled) continue
                    if (original.width < 100 || original.height < 100) continue

                    val version = SrCore.manager.currentModelVersion()
                    // 看门狗：批处理崩了下次启动也会自动切 CPU
                    if (!SrCore.inferenceInFlight) SrCore.markInferenceStart()
                    val result = SrCore.manager.process(original, version)
                    SrCore.markInferenceEnd()
                    if (result !== original && !result.isRecycled) {
                        SrCore.diskCache.putBatchPage(item.bookTitle, item.chapterKey, i, result)
                        failedAll = false
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // ★ F4.2：服务被销毁/协程取消——不盖章不删产物，条目原样留在队列，
                    //   下次服务启动按磁盘产物断点续做
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "page $i failed", e)
                }

                processed = i + 1
                if (i % 5 == 0 || i == total - 1) {
                    updateItemProgress(item.chapterKey, processed, total)
                    notifyProgress("${item.bookTitle}（${processed}/$total 页）", processed, total)
                    SrCore.emitBatchProgress(item.copy(processedPages = processed, totalPages = total))
                }
            }

            if (item.chapterKey in cancelledKeys) {
                // 取消：删掉半成品，避免半成品章节被当成完成品
                SrCore.diskCache.removeBatchChapter(item.bookTitle, item.chapterKey)
            } else if (sawApplicablePage && failedAll) {
                // ★ F4.2：整章零产物不盖章——盖章会让该章"实时超分被禁、永远原图"。
                //   不写 metadata、清残留产物、条目照常移出（用户修好环境后可重新入队）
                Log.w(TAG, "chapter all-failed, NOT stamped: ${item.chapterKey}")
                SrCore.diskCache.removeBatchChapter(item.bookTitle, item.chapterKey)
            } else {
                SrCore.diskCache.putBatchMetadata(item.bookTitle, item.chapterKey, total, item.bookId, item.path, batchModelKey)
            }
            // ★ F4.4：收尾移除前比对代际——运行中被重新入队的条目（代际更新）不删，留给下一轮
            val stillQueued = SrCore.queueStore.load().firstOrNull { it.chapterKey == item.chapterKey }
            if (stillQueued == null || stillQueued.enqueuedAt == itemGeneration) {
                SrBatchRunner.remove(applicationContext, item.chapterKey)
            } else {
                Log.i(TAG, "chapter ${item.chapterKey} re-enqueued during run (gen $itemGeneration -> ${stillQueued.enqueuedAt}), keep new entry")
            }
            cancelledKeys.remove(item.chapterKey)
        }

        notifyDone()
    }

    private fun updateItemProgress(chapterKey: String, processed: Int, total: Int) {
        // ★ F4.3：经 store 锁原子读改写
        SrCore.queueStore.mutate { items ->
            items.map {
                if (it.chapterKey == chapterKey) it.copy(processedPages = processed, totalPages = total) else it
            }
        }
    }

    private fun ensureChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "画质增强批量处理", NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (nm.getNotificationChannel(CH_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "增强完成", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun buildProgressNotification(text: String, progress: Int, max: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SrBatchService::class.java).setAction("CANCEL_ALL"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SrBatchService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH_PROGRESS)
            .setContentTitle(if (paused) "批量增强（已暂停）" else "批量增强进行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (paused) "继续" else "暂停", pauseIntent).build())
            .addAction(Notification.Action.Builder(null, "取消全部", cancelIntent).build())
            .build()
    }

    private fun notifyProgress(text: String, progress: Int, max: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTI_PROGRESS, buildProgressNotification(text, progress, max))
        } catch (_: Exception) {}
    }

    private fun notifyDone() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTI_PROGRESS)
            nm.notify(
                NOTI_DONE,
                Notification.Builder(this, CH_DONE)
                    .setContentTitle("批量增强完成")
                    .setContentText("所有排队章节已处理完毕，阅读时将直接使用增强图")
                    .setSmallIcon(android.R.drawable.ic_menu_upload)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: Exception) {}
    }

    private fun startForegroundSafe(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTI_PROGRESS, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        isRunning = false
        super.onDestroy()
    }

    // "取消全部" action 处理（通知按钮）
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }
}
