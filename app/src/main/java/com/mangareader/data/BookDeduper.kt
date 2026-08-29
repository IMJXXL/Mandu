package com.mangareader.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ★ 书库一次性去重（幂等；App 启动后台跑一次）。
 *
 *   manga_books.filePath 无唯一约束，历史重复导入留下了同一本书多行（诊断实锤 book=35/7）。
 *   数据库内的四表迁移全部走 MangaDao.dedupMigrateAndDelete 单事务（复用 rescanFolderReplace
 *   的回填模式）；本类只负责三件 DB 外的事：
 *     ① 分组检出 + 调事务
 *     ② prefs 继承：book_{id}_*（ReaderConfig）与 orientation_{id} —— 存活者完全没有配置时
 *        才从冗余记录整份拷贝，绝不覆盖已有配置；不删冗余 id 的旧键（autoGenerate 不复用 id，
 *        残键无害，留作只增不删的保守面）
 *     ③ SR 批量队列（SRQueueStore）条目的 bookId 改挂存活 id——chapterKey 含 bookId 须重算，
 *        同键碰撞由 store 按 chapterKey 覆写天然去重
 *   失败策略：DB 事务任一步失败已整体回滚；prefs/队列迁移在事务提交后才跑，各自 try-catch
 *   兜底（跳过不影响正确性，仅少继承一点配置）。下次启动重跑幂等。
 */
object BookDeduper {
    private const val TAG = "DedupBooks"

    suspend fun runOnce(ctx: Context, dao: MangaDao): Boolean = try {
        val all = withContext(Dispatchers.IO) { dao.getAllBooksAnyHidden() }
        val groups = all.groupBy { it.filePath }.filterValues { it.size > 1 }.values.toList()
        if (groups.isEmpty()) {
            Log.i(TAG, "无重复记录（书库 ${all.size} 本）")
            true
        } else {
            Log.i(TAG, "检出重复：${groups.size} 组 / ${groups.sumOf { it.size }} 行——" +
                groups.joinToString(" | ") { g -> "…${g.first().filePath.takeLast(24)}x${g.size}" })
            val result = dao.dedupMigrateAndDelete(groups)
            Log.i(TAG, "去重完成：removed=${result.outcomes.sumOf { it.dupIds.size }} " +
                "migrated(prog=${result.migratedProgress} hist=${result.migratedHistory} " +
                "shelf=${result.migratedShelf} tag=${result.migratedTags}) " +
                "映射=${result.outcomes.joinToString { "${it.dupIds}->${it.survivorId}" }}")
            inheritPrefs(ctx, result.outcomes)
            rewriteSrQueue(ctx, result.outcomes)
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "去重失败（已整体回滚，下次启动重试）", e)
        false
    }

    /** 阅读配置/方向键继承：仅当存活者一份 per-book 配置都没有，才从某个有配置的冗余记录整份拷贝 */
    private fun inheritPrefs(ctx: Context, outcomes: List<DedupOutcome>) {
        try {
            val prefs = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
            for (oc in outcomes) {
                if (prefs.contains("book_${oc.survivorId}_scrollMode")) continue
                val donor = oc.dupIds.lastOrNull { prefs.contains("book_${it}_scrollMode") } ?: continue
                val editor = prefs.edit()
                for ((k, v) in prefs.all) {
                    if (!k.startsWith("book_${donor}_")) continue
                    val nk = "book_${oc.survivorId}_" + k.removePrefix("book_${donor}_")
                    when (v) {
                        is String -> editor.putString(nk, v)
                        is Int -> editor.putInt(nk, v)
                        is Long -> editor.putLong(nk, v)
                        is Float -> editor.putFloat(nk, v)
                        is Boolean -> editor.putBoolean(nk, v)
                        else -> {}
                    }
                }
                if (!prefs.contains("orientation_${oc.survivorId}") && prefs.contains("orientation_$donor")) {
                    editor.putInt("orientation_${oc.survivorId}", prefs.getInt("orientation_$donor", -1))
                }
                editor.apply()
                Log.i(TAG, "prefs 继承：donor=$donor -> survivor=${oc.survivorId}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "prefs 继承跳过：${e.message}")
        }
    }

    /** SR 批量队列条目改挂存活 id（bookId 变 → chapterKey 重算；同键碰撞由 chapterKey 覆写去重） */
    private fun rewriteSrQueue(ctx: Context, outcomes: List<DedupOutcome>) {
        try {
            val remap = HashMap<Long, Long>()
            for (oc in outcomes) for (d in oc.dupIds) remap[d] = oc.survivorId
            if (remap.isEmpty()) return
            val store = com.mangareader.sr.SRQueueStore(ctx)
            val before = store.load().size
            store.mutate { items ->
                items.map { item ->
                    val nid = remap[item.bookId] ?: return@map item
                    item.copy(bookId = nid, chapterKey = com.mangareader.sr.SRQueueItem.chapterKey(nid, item.path))
                }
            }
            Log.i(TAG, "SR 队列改写：$before 条 -> bookId 映射 ${remap.size} 项")
        } catch (e: Exception) {
            Log.w(TAG, "SR 队列改写跳过：${e.message}")
        }
    }
}
