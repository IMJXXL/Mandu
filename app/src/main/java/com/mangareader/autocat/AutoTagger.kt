package com.mangareader.autocat

import android.content.Context
import android.util.Log
import com.mangareader.data.Book
import com.mangareader.data.MangaDatabase
import com.mangareader.metadata.ComicInfo
import com.mangareader.metadata.ComicInfoReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction

/**
 * 自动打标签（替代旧 AutoCategorizer 的"自动建书架"，spec: .codeartsdoer/specs/tag_system）：
 * - Writer/Genre/Publisher/自定义 XML 字段 → 标签（source=0，只增不删，永不碰 source=1 手动标签）
 * - Series → 结构化字段 Book.seriesId/seriesName（"local:"+名字），喂给书架系列合并，不进标签
 * - 命名空间前缀防跨维度碰撞（作者:张三 vs 类型张三），config.useNamespacePrefix 控制
 */
object AutoTagger {

    private const val TAG = "AutoTagger"

    const val SOURCE_XML = 0
    const val SOURCE_MANUAL = 1

    /** 导入扫描后触发（Scanner 调用点，签名与旧 AutoCategorizeTrigger.run 一致） */
    suspend fun run(books: List<Book>, ctx: Context) {
        val config = AutoCategorizeConfig.load(ctx)
        if (!config.enableSeries && !config.enableWriter && !config.enableGenre && !config.enablePublisher && config.enabledCustomFields.isEmpty()) {
            return
        }
        val db = MangaDatabase.getDatabase(ctx)
            val dao = db.mangaDao()
        for (book in books) {
            try {
                val info = ComicInfoReader.read(ctx, book.filePath) ?: continue
                applyToBook(dao, book, info, config)
            } catch (e: Exception) {
                Log.w(TAG, "auto-tag skipped for ${book.title}: ${e.message}")
            }
        }
    }

    /** 单本应用：只增不删（日常重扫/导入路径） */
    suspend fun applyToBook(dao: com.mangareader.data.MangaDao, book: Book, info: ComicInfo, config: AutoCategorizeConfig) {
        if (config.enableSeries && !info.series.isNullOrBlank()) {
            val stripped = info.series!!.trim().removeSuffix("系列").trim()
            if (stripped.isNotEmpty()) {
                // seriesId 里的 "/" 换全角斜杠：Nav 路由按 "/" 分段，半角斜杠会截断参数（如 Fate/Zero）；seriesName 保持原名
                val safeId = "local:" + stripped.replace('/', '／')
                try { dao.updateSeriesInfo(book.id, safeId, stripped) } catch (e: Exception) {
                    Log.w(TAG, "series write failed for ${book.title}: ${e.message}")
                }
            }
        }
        val names = collectTagNames(info, config)
        if (names.isNotEmpty()) {
            try { dao.setBookTags(book.id, names, SOURCE_XML) } catch (e: Exception) {
                Log.w(TAG, "tag write failed for ${book.title}: ${e.message}")
            }
        }
    }

    fun collectTagNames(info: ComicInfo, config: AutoCategorizeConfig): List<String> {
        val prefix = config.useNamespacePrefix
        val names = LinkedHashSet<String>()
        if (config.enableWriter && !info.writer.isNullOrBlank()) {
            val v = info.writer!!.trim()
            if (v.isNotEmpty()) names.add(if (prefix) "作者:$v" else v)
        }
        if (config.enableGenre && !info.genre.isNullOrBlank()) {
            // 类型是唯一裸值维度（漫画类型本身就是身份），逗号/全角逗号/顿号全拆
            info.genre!!.split(",", "，", "、")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { names.add(it) }
        }
        if (config.enablePublisher && !info.publisher.isNullOrBlank()) {
            val v = info.publisher!!.trim()
            if (v.isNotEmpty()) names.add(if (prefix) "出版:$v" else v)
        }
        for (fieldName in config.customFieldNames) {
            if (config.enabledCustomFields.none { it.equals(fieldName, ignoreCase = true) }) continue
            val v = info.extraFields.entries.firstOrNull { it.key.equals(fieldName, ignoreCase = true) }
                ?.value?.trim() ?: continue
            if (v.isNotEmpty()) names.add(if (prefix) "$fieldName:$v" else v)
        }
        return names.toList()
    }

    /**
     * 从 XML 重建全库自动标签（设置页显式动作，P1 前移自 P3）：
     * 每本先清 source=0 再重建；手动标签（source=1）永不动；isCancelled 支持中途取消。
     */
    suspend fun rebuildAll(ctx: Context, onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean) = withContext(Dispatchers.IO) {
        val config = AutoCategorizeConfig.load(ctx)
        if (config.enableSeries || config.enableWriter || config.enableGenre || config.enablePublisher || config.enabledCustomFields.isNotEmpty()) {
            val db = MangaDatabase.getDatabase(ctx)
            val dao = db.mangaDao()
            val books = try { dao.getAllVisibleBooksList() } catch (e: Exception) {
                Log.w(TAG, "rebuild load books failed: ${e.message}"); return@withContext
            }
            var done = 0
            for (book in books) {
                if (isCancelled()) break
                try {
                    // 先读后删：XML 不可读的书保留既有自动标签（旧流程先删后建，读失败=标签永久丢失）；
                    // 删+建包进同一事务，中途取消/崩溃不留"已删未建"半态
                    val info = ComicInfoReader.read(ctx, book.filePath)
                    db.withTransaction {
                        dao.deleteAutoTagsForBook(book.id)
                        if (info != null) applyToBook(dao, book, info, config)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "rebuild skip ${book.title}: ${e.message}")
                }
                done++
                onProgress(done, books.size)
            }
        }
    }
}
