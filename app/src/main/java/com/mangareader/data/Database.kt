package com.mangareader.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.*
import kotlinx.coroutines.flow.Flow

fun stableBookId(filePath: String): Long {
    var h = -0x340d631b7bdddcdbL
    for (c in filePath) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L
    }
    if (h == 0L) h = 1L
    return h
}

@Entity(
    tableName = "manga_books",
    indices = [
        Index("isHidden"),
        Index("isHidden", "lastReadAt"),
        Index("isHidden", "isFavorite"),
        Index("filePath"),
        Index("lastReadAt")
    ]
)
@Immutable
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val fileType: String = "",
    val coverPath: String? = null,
    /** ★ 自定义封面（2026-09-26）：用户手动设置的封面，"custom:<fileName>" 指向 filesDir/covers 私有文件。
     *  与 coverPath 分离的理由：Scanner 重扫会重写 coverPath（默认封面指针），custom 独立字段天然免疫；
     *  显示时 displayCoverPath 优先；null=未自定义（恢复默认=置 null+删文件） */
    val customCoverPath: String? = null,
    val totalPages: Int = 0,
    val readPages: Int = 0,
    val lastReadAt: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val source: String = "local",
    val originalPath: String? = null,
    /** 源物理文件 lastModified（重扫复用/封面新鲜度戳）；0=未知（SAF-only 或旧数据） */
    val sourceMtimeMs: Long = 0,
    /** 源物理文件 length */
    val sourceSizeBytes: Long = 0,
    val seriesId: String? = null,
    val seriesName: String? = null,
) {
    /** ★ 显示用封面：自定义优先；自定义文件丢失的孤儿由 Library 启动自愈置回 null */
    val displayCoverPath: String? get() = customCoverPath ?: coverPath
}

@Entity(tableName = "imported_folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val displayName: String = "",
    val lastScanAt: Long = 0
)

@Entity(tableName = "bookshelves")
@Immutable
data class Bookshelf(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val isHidden: Boolean = false
)

@Entity(
    tableName = "bookshelf_items",
    foreignKeys = [ForeignKey(entity = Bookshelf::class, parentColumns = ["id"], childColumns = ["bookshelfId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookshelfId"), Index("bookId")]
)
data class BookshelfItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookshelfId: Long,
    val bookId: Long
)

@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF7C8CF8,
    // 隐藏标签：不出现在首页筛选/选择器，管理页可见可恢复（对齐书架 isHidden 模式）
    val isHidden: Boolean = false,
    // 手动排序位（管理页"按手动"模式拖动排序；迁移时按 id 回填保持创建顺序）
    val sortOrder: Int = 0
)

@Entity(
    tableName = "tag_links",
    foreignKeys = [
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagId"), Index("bookId"), Index(value = ["tagId", "bookId"], unique = true)]
)
data class TagLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagId: Long,
    val bookId: Long,
    // 标签来源：0=XML自动打标 1=用户手动。XML重扫永不删 source=1（spec.md §五冲突策略）
    val source: Int = 1
)

/** 标签+挂书数（管理页/热门区/选择器共用） */
data class TagWithCount(
    @Embedded val tag: Tag,
    val bookCount: Int
)

/** 详情页标签区：书的标签+来源（0=XML 1=手动） */
data class TagWithSource(
    @Embedded val tag: Tag,
    val linkSource: Int
)

@Entity(tableName = "reading_history",
    indices = [Index("bookId"), Index("timestamp")])
data class ReadingHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pagesRead: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val zoomLevel: Float = 1.0f
)

/** ★ F 历史记录页行：JOIN 书信息 + 该书最新在读卷路径/页码（点击续读与"读到 X/Y 页"展示用） */
data class HistoryRow(
    val histId: Long,
    val bookId: Long,
    val bookTitle: String?,
    val coverPath: String?,
    val pagesRead: Int,
    val timestamp: Long,
    val lastPath: String?,
    val lastPage: Int?,
    val totalPages: Int?
)

/**
 * §23 阅读记忆方案 — 卷级阅读进度（mihon chapters.last_page_read 对应物）
 * 按 (bookId, path) 联合唯一：同一本书的不同子路径独立记忆。
 * path 形态：a:|...| / d:|...| / z:a:...//子路径/ / 文件根路径
 *
 * anim_dialog_choice（§24 §3 §F 决策 3）：
 *   0=未弹过对话框 / 1=同意切条漫 / 2=拒绝过
 *   只控制对话框要不要再弹，不控制播不播 —— 播不播只看 mode。
 */
@Entity(
    tableName = "reading_progress",
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "path"], unique = true),
        Index("updatedAt")
    ],
    foreignKeys = [ForeignKey(
        entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )]
)
@Immutable
data class ReadingProgress(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val path: String,
    val lastPage: Int = 0,
    val completed: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val anim_dialog_choice: Int = 0,
    // ★ v1.13 A'：该卷真实页数（enterReader listPages 结果写入）。
    //   历史页"第 X/N 页"的 N 改读此卷级值，摆脱 manga_books.totalPages 被多卷聚合污染的问题
    @ColumnInfo(defaultValue = "0")
    val totalPages: Int = 0
)

/**
 * ★ 书签（DB v12 纯增量表）：书 × 章节 × 页。chapterPath 与 reading_progress.path 完全同源
 *   （a:/d:/z: 全路径），page 与 cur 同义（五模式统一页索引，displayPages==pageList）。
 *   (bookId,chapterPath,page) 唯一索引：重复添加 IGNORE，天然防重防竞态。
 *   FK CASCADE：删书自动清书签，零代码。
 */
@Entity(
    tableName = "bookmarks",
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "chapterPath", "page"], unique = true)
    ],
    foreignKeys = [ForeignKey(
        entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )]
)
@Immutable
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterPath: String,
    val page: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val note: String? = null
)

@Dao
interface MangaDao {
    @Query("SELECT * FROM manga_books ORDER BY lastReadAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isFavorite = 1 ORDER BY lastReadAt DESC")
    fun getFavoriteBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books ORDER BY title ASC")
    fun getAllBooksByName(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books ORDER BY addedAt DESC")
    fun getAllBooksByDateAdded(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE id = :id")
    suspend fun getBookById(id: Long): Book?

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY lastReadAt DESC")
    fun getVisibleBooks(): Flow<List<Book>>

    // ★ 继续阅读 FAB（2026-09-26 实测反馈）：去掉 lastPage>0/completed 限制——停在第一页的合法
    //   进度（lastPage=0）与脏数据自愈写的 (0,false) 都会让 FAB 闪没；语义改为「最近打开过的卷」
    @Query("SELECT p.* FROM reading_progress p INNER JOIN manga_books b ON b.id = p.bookId WHERE b.isHidden = 0 ORDER BY p.updatedAt DESC LIMIT 1")
    fun getLastReading(): Flow<ReadingProgress?>

    @Query("SELECT * FROM manga_books WHERE isHidden = 1 ORDER BY lastReadAt DESC")
    fun getHiddenBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND (title LIKE '%' || :query || '%' OR id IN (SELECT tl.bookId FROM tag_links tl INNER JOIN tags t ON tl.tagId = t.id WHERE t.name LIKE '%' || :query || '%')) ORDER BY lastReadAt DESC")
    fun searchVisibleBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND isFavorite = 1 AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchFavoriteBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND (readPages > 0 OR lastReadAt IS NOT NULL) AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchReadingBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND readPages = 0 AND lastReadAt IS NULL AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchUnreadBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND totalPages > 0 AND readPages >= totalPages AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchFinishedBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND fileType = :type AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchByType(query: String, type: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY lastReadAt DESC")
    suspend fun getAllBooksList(): List<Book>

    /** ★ C1 补丁：含隐藏书的全量清单——扫描查重数据源必须覆盖隐藏书，否则重复导入 */
    @Query("SELECT * FROM manga_books")
    suspend fun getAllBooksAnyHidden(): List<Book>

    @Query("SELECT DISTINCT fileType FROM manga_books WHERE isHidden = 0")
    suspend fun getAllFileTypes(): List<String>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY title ASC")
    fun getVisibleBooksByName(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY addedAt DESC")
    fun getVisibleBooksByDateAdded(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY CASE WHEN totalPages > 0 THEN cast(readPages as float) / totalPages ELSE 0 END DESC")
    fun getVisibleBooksByProgress(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND isFavorite = 1 ORDER BY lastReadAt DESC")
    fun getVisibleFavoriteBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND totalPages > 0 AND readPages >= totalPages ORDER BY lastReadAt DESC")
    fun getVisibleFinishedBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND (readPages > 0 OR lastReadAt IS NOT NULL) AND (totalPages = 0 OR readPages < totalPages) ORDER BY lastReadAt DESC")
    fun getVisibleReadingBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND readPages = 0 AND lastReadAt IS NULL ORDER BY lastReadAt DESC")
    fun getVisibleUnreadBooks(): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBook(book: Book): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBooksBatch(books: List<Book>): List<Long>

    @Transaction
    suspend fun insertBookWithStableId(book: Book): Long {
        return insertBook(book.copy(id = stableBookId(book.filePath)))
    }

    @Transaction
    suspend fun insertBooksWithStableId(books: List<Book>): List<Long> {
        return insertBooksBatch(books.map { it.copy(id = stableBookId(it.filePath)) })
    }

    /** ★ SMB/WebDAV 下载完成：只记录本地缓存路径到 originalPath，不改 filePath/source/coverPath。
     *  书仍是远程书，打开时优先检查 originalPath 本地文件是否存在，有则从本地读（离线可读）。 */
    @Query("UPDATE manga_books SET originalPath = :filePath WHERE id = :id")
    suspend fun rehomeBookToLocal(id: Long, filePath: String)

    /** ★ 下载完成后更新封面路径为本地 SAF 封面路径，使封面可从本地提取 */
    @Query("UPDATE manga_books SET coverPath = :coverPath WHERE id = :id")
    suspend fun updateBookCoverPath(id: Long, coverPath: String)

    /** ★ 自定义封面：设置（"custom:<fileName>"）/ 恢复默认（null） */
    @Query("UPDATE manga_books SET customCoverPath = :path WHERE id = :id")
    suspend fun updateBookCustomCoverPath(id: Long, path: String?)

    /** ★ 孤儿自愈扫描：自定义封面文件被清（签名降级清 covers 等）后置回默认 */
    @Query("SELECT * FROM manga_books WHERE customCoverPath IS NOT NULL")
    suspend fun booksWithCustomCover(): List<Book>

    /** ★ 清除本地缓存路径记录（缓存文件被删时） */
    @Query("UPDATE manga_books SET originalPath = NULL WHERE id = :id")
    suspend fun restoreBookToRemote(id: Long)

    /** ★ 删除 SMB 服务器时级联清理其书籍（spec 5.1 级联清理；仅新增） */
    @Query("DELETE FROM manga_books WHERE (source = 'smb' AND filePath LIKE 'smb:' || :serverId || ':%') OR (source = 'local' AND originalPath LIKE 'smb:' || :serverId || ':%')")
    suspend fun deleteSmbBooks(serverId: Long)

    @Query("DELETE FROM manga_books WHERE source = 'komga' AND filePath LIKE 'komga:' || :serverId || ':%'")
    suspend fun deleteKomgaBooks(serverId: String)

    @Query("DELETE FROM manga_books WHERE source = 'opds' AND filePath LIKE 'opds:' || :serverId || ':%'")
    suspend fun deleteOpdsBooks(serverId: String)

    @Query("SELECT * FROM manga_books WHERE source = 'komga' AND filePath = :filePath LIMIT 1")
    suspend fun getKomgaBookByPath(filePath: String): Book?

    @Query("SELECT filePath FROM manga_books WHERE source = 'komga' AND filePath LIKE 'komga:' || :serverId || ':%'")
    suspend fun getKomgaBookPathsForServer(serverId: String): List<String>

    @Query("UPDATE manga_books SET seriesId = :seriesId, seriesName = :seriesName WHERE id = :id")
    suspend fun updateKomgaSeriesInfo(id: Long, seriesId: String, seriesName: String)

    @Query("SELECT * FROM manga_books WHERE source = 'komga' AND seriesId = :seriesId AND filePath LIKE 'komga:' || :serverId || ':%' AND isHidden = 0 ORDER BY title ASC")
    suspend fun getKomgaSeriesVisibleBooks(serverId: String, seriesId: String): List<Book>

    @Query("UPDATE manga_books SET isHidden = :hidden WHERE source = 'komga' AND filePath LIKE 'komga:' || :serverId || ':%'")
    suspend fun setKomgaBooksHidden(serverId: String, hidden: Boolean)

    @Query("SELECT COUNT(*) FROM manga_books WHERE source = 'komga' AND filePath LIKE 'komga:' || :serverId || ':%' AND isHidden = 1")
    suspend fun countKomgaHiddenBooks(serverId: String): Int

    @Query("UPDATE manga_books SET isHidden = :hidden WHERE source = 'opds' AND filePath LIKE 'opds:' || :serverId || ':%'")
    suspend fun setOpdsBooksHidden(serverId: String, hidden: Boolean)

    @Query("SELECT COUNT(*) FROM manga_books WHERE source = 'opds' AND filePath LIKE 'opds:' || :serverId || ':%' AND isHidden = 1")
    suspend fun countOpdsHiddenBooks(serverId: String): Int

    /** ★ SMB 书源整源隐藏/显示（对齐本地文件夹的隐藏机制；含已下载到本地的书） */
    @Query("UPDATE manga_books SET isHidden = :hidden WHERE (source = 'smb' AND filePath LIKE 'smb:' || :serverId || ':%') OR (source = 'local' AND originalPath LIKE 'smb:' || :serverId || ':%')")
    suspend fun setSmbBooksHidden(serverId: Long, hidden: Boolean)

    @Query("SELECT COUNT(*) FROM manga_books WHERE ((source = 'smb' AND filePath LIKE 'smb:' || :serverId || ':%') OR (source = 'local' AND originalPath LIKE 'smb:' || :serverId || ':%')) AND isHidden = 1")
    suspend fun countSmbHiddenBooks(serverId: Long): Int

    @Query("SELECT * FROM manga_books WHERE (source = 'smb' AND filePath LIKE 'smb:' || :serverId || ':%') OR (source = 'local' AND originalPath LIKE 'smb:' || :serverId || ':%')")
    suspend fun getSmbBooks(serverId: Long): List<Book>

    @Query("SELECT * FROM manga_books WHERE (source = 'webdav' AND filePath LIKE 'webdav:' || :serverId || ':%') OR (source = 'local' AND originalPath LIKE 'webdav:' || :serverId || ':%')")
    suspend fun getWebDavBooks(serverId: Long): List<Book>

    /** ★ WebDAV 书源整源隐藏/显示（含已下载到本地的书） */
    @Query("UPDATE manga_books SET isHidden = :hidden WHERE (source = 'webdav' AND filePath LIKE 'webdav:' || :serverId || ':%') OR (source = 'local' AND originalPath LIKE 'webdav:' || :serverId || ':%')")
    suspend fun setWebDavBooksHidden(serverId: Long, hidden: Boolean)

    @Query("SELECT COUNT(*) FROM manga_books WHERE ((source = 'webdav' AND filePath LIKE 'webdav:' || :serverId || ':%') OR (source = 'local' AND originalPath LIKE 'webdav:' || :serverId || ':%')) AND isHidden = 1")
    suspend fun countWebDavHiddenBooks(serverId: Long): Int

    /** ★ 删除 WebDAV 服务器时级联清理其书籍（含已下载到本地的书） */
    @Query("DELETE FROM manga_books WHERE (source = 'webdav' AND filePath LIKE 'webdav:' || :serverId || ':%') OR (source = 'local' AND originalPath LIKE 'webdav:' || :serverId || ':%')")
    suspend fun deleteWebDavBooks(serverId: Long)

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("UPDATE manga_books SET readPages = :pages, lastReadAt = :timestamp WHERE id = :id")
    suspend fun updateReadingProgress(id: Long, pages: Int, timestamp: Long)

    @Query("UPDATE manga_books SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE manga_books SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE manga_books SET isHidden = :hidden")
    suspend fun setHiddenAll(hidden: Boolean)

    @Query("UPDATE manga_books SET title = :title WHERE id = :id")
    suspend fun updateBookTitle(id: Long, title: String)

    @Transaction
    suspend fun updateBookTitlesBatch(updates: List<Pair<Long, String>>) {
        updates.forEach { (id, title) -> updateBookTitle(id, title) }
    }

    // ★ C8 修复：前缀由 Kotlin 侧拼好作为单参传入（旧写法右侧非常量表达式使 filePath 索引失效，
    //   rescan 高频全表扫）。前缀逐字符复刻旧拼接：a/z 带尾 '|'，d 不带。
    //   SAF authority/treeDocId 不含 %/_ 通配符，直接 LIKE 安全。
    @Query("SELECT * FROM manga_books WHERE filePath LIKE :aPrefix OR filePath LIKE :dPrefix OR filePath LIKE :zPrefix")
    suspend fun getBooksByFolderParams(aPrefix: String, dPrefix: String, zPrefix: String): List<Book>

    @Query("UPDATE manga_books SET isHidden = :hidden WHERE filePath LIKE :aPrefix OR filePath LIKE :dPrefix OR filePath LIKE :zPrefix")
    suspend fun setHiddenByFolderParams(aPrefix: String, dPrefix: String, zPrefix: String, hidden: Boolean)

    @Query("SELECT * FROM manga_books WHERE filePath = :path LIMIT 1")
    suspend fun getBookByPath(path: String): Book?

    /** §37 误判修正：扫描发现某 zip/cbz 非漫画时，按 path 精确删除 DB 里已有的脏书（CASCADE 清 reading_progress） */
    @Query("DELETE FROM manga_books WHERE filePath = :path")
    suspend fun deleteBookByPath(path: String)

    @Query("DELETE FROM manga_books WHERE filePath LIKE '%' || :treeUriStr || '%'")
    suspend fun deleteBooksByFolder(treeUriStr: String)

    @Query("DELETE FROM manga_books WHERE filePath LIKE :aPrefix OR filePath LIKE :dPrefix OR filePath LIKE :zPrefix")
    suspend fun deleteBooksByFolderParams(aPrefix: String, dPrefix: String, zPrefix: String)

    @Query("SELECT COUNT(*) FROM manga_books WHERE filePath LIKE :aPrefix OR filePath LIKE :dPrefix OR filePath LIKE :zPrefix")
    suspend fun countBooksByFolder(aPrefix: String, dPrefix: String, zPrefix: String): Int

    @Query("SELECT * FROM manga_books WHERE totalPages > 0 AND readPages >= totalPages ORDER BY lastReadAt DESC")
    fun getFinishedBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE lastReadAt IS NOT NULL AND (totalPages = 0 OR readPages < totalPages) ORDER BY lastReadAt DESC")
    fun getReadingBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE (totalPages = 0 OR readPages = 0) AND isFavorite = 0 ORDER BY lastReadAt DESC")
    fun getUnreadBooks(): Flow<List<Book>>

    @Query("SELECT * FROM imported_folders ORDER BY displayName")
    fun getAllFolders(): Flow<List<Folder>>

    @Query("SELECT * FROM imported_folders ORDER BY displayName")
    suspend fun getAllFoldersList(): List<Folder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder): Long

    @Update
    suspend fun updateFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)

    @Query("SELECT * FROM imported_folders WHERE treeUri = :uri LIMIT 1")
    suspend fun getFolderByUri(uri: String): Folder?

    @Query("SELECT COUNT(*) FROM manga_books WHERE isHidden = 1")
    suspend fun countHidden(): Int

    @Query("SELECT COUNT(*) FROM manga_books")
    suspend fun countAll(): Int

    // ★ 后台导入实时统计：入库每完成一批 Flow 自动重发，设置页总数无需手动刷新
    @Query("SELECT COUNT(*) FROM manga_books")
    fun observeTotalBooks(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT COALESCE(SUM(totalPages), 0) FROM manga_books")
    suspend fun countTotalPages(): Int

    @Query("SELECT COUNT(*) FROM manga_books WHERE readPages > 0")
    suspend fun countReadBooks(): Int

    @Insert
    suspend fun insertHistory(history: ReadingHistory)

    // ==================== ★ F 历史记录页 ====================

    /** 分页倒序：LEFT JOIN 书信息（history 无外键，书被删后记录仍在，标题显示"已删除书籍"）；
     *  子查询取该书最新在读卷 path 与页码（updatedAt>0 过滤 Scanner 占位行），点击续读 jump 用 */
    @Query("""
        SELECT h.id AS histId, h.bookId AS bookId, b.title AS bookTitle, b.coverPath AS coverPath,
               h.pagesRead AS pagesRead, h.timestamp AS timestamp,
               (SELECT p.path FROM reading_progress p WHERE p.bookId = h.bookId AND p.updatedAt > 0 ORDER BY p.updatedAt DESC LIMIT 1) AS lastPath,
               (SELECT p.lastPage FROM reading_progress p WHERE p.bookId = h.bookId AND p.updatedAt > 0 ORDER BY p.updatedAt DESC LIMIT 1) AS lastPage,
               b.totalPages AS totalPages
        FROM reading_history h LEFT JOIN manga_books b ON b.id = h.bookId
        ORDER BY h.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getHistoryPage(limit: Int, offset: Int): List<HistoryRow>

    /** ★ F：清空全部阅读历史（历史页顶栏清空按钮，需用户二次确认；不做自动清理） */
    @Query("DELETE FROM reading_history")
    suspend fun clearAllHistory()

    // ==================== ★ v1.13 A' 历史页 progress 主导数据源 ====================

    /** 每卷一行：(bookId,path) 唯一索引天然保证；updatedAt>0 过滤 Scanner 占位行与"已隐藏"行 */
    @Query("""
        SELECT p.bookId AS bookId, p.path AS chPath, b.title AS bookTitle, b.coverPath AS coverPath,
               p.lastPage AS lastPage, p.totalPages AS totalPages, p.updatedAt AS updatedAt,
               p.completed AS completed
        FROM reading_progress p LEFT JOIN manga_books b ON b.id = p.bookId
        WHERE p.updatedAt > 0
        ORDER BY p.updatedAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getProgressHistoryPage(limit: Int, offset: Int): List<ProgressHistoryRow>

    /**
     * ★ v1.13 A' 裁定：删除/清空 = updatedAt 归零"隐藏"，不物理删行——
     *   lastPage/completed/anim_dialog_choice 全保留（续读与动图选择不受影响），
     *   归零形态与 Scanner 占位行同构，被所有 updatedAt>0 过滤规则自然兼容。
     */
    @Query("UPDATE reading_progress SET updatedAt = 0 WHERE bookId = :bookId AND path = :path")
    suspend fun hideProgress(bookId: Long, path: String)

    @Query("UPDATE reading_progress SET updatedAt = 0")
    suspend fun hideAllProgress()

    @Query("SELECT * FROM reading_history WHERE bookId = :bookId ORDER BY timestamp DESC LIMIT 10")
    fun getBookHistory(bookId: Long): Flow<List<ReadingHistory>>

    @Query("SELECT SUM(pagesRead) FROM reading_history")
    suspend fun totalPagesRead(): Int

    @Query("SELECT COUNT(DISTINCT bookId) FROM reading_history")
    suspend fun uniqueBooksRead(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookshelf(bookshelf: Bookshelf): Long

    @Update
    suspend fun updateBookshelf(bookshelf: Bookshelf)

    @Query("UPDATE bookshelves SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateBookshelfSort(id: Long, sortOrder: Int)

    @Delete
    suspend fun deleteBookshelf(bookshelf: Bookshelf)

    @Query("SELECT * FROM bookshelves ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllBookshelves(): Flow<List<Bookshelf>>

    @Query("SELECT * FROM bookshelves ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAllBookshelvesList(): List<Bookshelf>

    @Query("SELECT * FROM bookshelves WHERE isHidden = 0 ORDER BY sortOrder ASC, createdAt DESC")
    fun getVisibleBookshelves(): Flow<List<Bookshelf>>

    @Query("SELECT * FROM bookshelves WHERE isHidden = 0 ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getVisibleBookshelvesList(): List<Bookshelf>

    @Query("UPDATE bookshelves SET isHidden = :hidden WHERE id = :id")
    suspend fun setBookshelfHidden(id: Long, hidden: Boolean)

    @Query("SELECT DISTINCT bookId FROM bookshelf_items WHERE bookshelfId IN (:sourceIds) AND bookId NOT IN (SELECT bookId FROM bookshelf_items WHERE bookshelfId = :targetId)")
    suspend fun getUniqueBookIdsFromShelves(sourceIds: List<Long>, targetId: Long): List<Long>

    @Query("DELETE FROM bookshelf_items WHERE bookshelfId IN (:sourceIds)")
    suspend fun deleteItemsInShelves(sourceIds: List<Long>)

    @Query("DELETE FROM bookshelves WHERE id IN (:ids)")
    suspend fun deleteBookshelvesByIds(ids: List<Long>)

    @Transaction
    suspend fun mergeBookshelves(targetId: Long, sourceIds: List<Long>) {
        val validSources = sourceIds.filter { it != targetId }
        if (validSources.isEmpty()) return
        val bookIds = getUniqueBookIdsFromShelves(validSources, targetId)
        deleteItemsInShelves(validSources)
        for (bookId in bookIds) {
            insertBookshelfItem(BookshelfItem(bookshelfId = targetId, bookId = bookId))
        }
        deleteBookshelvesByIds(validSources)
    }

    @Transaction
    suspend fun deleteBookshelvesBatch(ids: List<Long>) {
        if (ids.isEmpty()) return
        deleteItemsInShelves(ids)
        deleteBookshelvesByIds(ids)
    }

    @Query("UPDATE bookshelves SET isHidden = :hidden WHERE id IN (:ids)")
    suspend fun setBookshelvesHiddenBatch(ids: List<Long>, hidden: Boolean)

    @Query("SELECT * FROM bookshelves WHERE isHidden = 1 ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getHiddenBookshelvesList(): List<Bookshelf>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookshelfItem(item: BookshelfItem): Long

    @Delete
    suspend fun deleteBookshelfItem(item: BookshelfItem)

    @Query("DELETE FROM bookshelf_items WHERE bookshelfId = :bookshelfId AND bookId = :bookId")
    suspend fun removeBookFromShelf(bookshelfId: Long, bookId: Long)

    @Query("SELECT * FROM bookshelf_items WHERE bookshelfId = :bookshelfId")
    fun getBooksInShelf(bookshelfId: Long): Flow<List<BookshelfItem>>

    @Query("SELECT * FROM bookshelf_items WHERE bookshelfId = :bookshelfId")
    suspend fun getBooksInShelfList(bookshelfId: Long): List<BookshelfItem>

    @Query("SELECT * FROM bookshelf_items WHERE bookId = :bookId")
    suspend fun getShelvesForBook(bookId: Long): List<BookshelfItem>

    @Query("SELECT b.* FROM manga_books b INNER JOIN bookshelf_items bi ON b.id = bi.bookId WHERE bi.bookshelfId = :bookshelfId ORDER BY b.title ASC")
    fun getBooksByShelf(bookshelfId: Long): Flow<List<Book>>

    @Query("DELETE FROM manga_books WHERE id IN (:ids)")
    suspend fun deleteBooksByIds(ids: List<Long>)

    @Query("DELETE FROM bookshelf_items WHERE bookId IN (:bookIds)")
    suspend fun removeBooksFromAllShelves(bookIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: Tag): Long

    @Delete
    suspend fun deleteTag(tag: Tag)

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAllTagsList(): List<Tag>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagLink(link: TagLink): Long

    @Delete
    suspend fun deleteTagLink(link: TagLink)

    @Query("DELETE FROM tag_links WHERE bookId = :bookId")
    suspend fun removeAllTagsFromBook(bookId: Long)

    @Query("SELECT t.* FROM tags t INNER JOIN tag_links tl ON t.id = tl.tagId WHERE tl.bookId = :bookId")
    fun getTagsForBook(bookId: Long): Flow<List<Tag>>

    @Query("SELECT t.* FROM tags t INNER JOIN tag_links tl ON t.id = tl.tagId WHERE tl.bookId = :bookId")
    suspend fun getTagsForBookList(bookId: Long): List<Tag>

    @Query("SELECT b.* FROM manga_books b INNER JOIN tag_links tl ON b.id = tl.bookId WHERE tl.tagId = :tagId AND b.isHidden = 0 ORDER BY b.title ASC")
    fun getBooksByTag(tagId: Long): Flow<List<Book>>

    @Query("SELECT COUNT(*) FROM tag_links WHERE tagId = :tagId")
    suspend fun countBooksInTag(tagId: Long): Int

    // ==================== 标签系统 v22（规划：.codeartsdoer/specs/tag_system） ====================

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findTagByName(name: String): Tag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagIfAbsent(tag: Tag): Long

    /**
     * 按名取标签，无则建（大小写不敏感去重）。并发防护：调用方必须包在 withTransaction 内，
     * 即便如此仍处理 IGNORE 撞车（返回 -1 时回查）。
     */
    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM tags")
    suspend fun getMaxTagSort(): Int

    @Query("UPDATE tags SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateTagSort(id: Long, sortOrder: Int)

    @Transaction
    suspend fun ensureTag(name: String): Long {
        findTagByName(name)?.let { return it.id }
        val rowId = insertTagIfAbsent(Tag(name = name))
        if (rowId != -1L) {
            // 新标签排到手动排序末尾
            updateTagSort(rowId, getMaxTagSort() + 1)
            return rowId
        }
        return findTagByName(name)?.id ?: -1L
    }

    /** 挂标签（联合唯一索引 tagId+bookId 兜底，重复挂静默忽略） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkTag(link: TagLink): Long

    /** 热门标签 Top N（按挂书量，选择器热门区/首页快捷行共用；只出可见标签） */
    @Query(
        """SELECT t.*, (SELECT COUNT(*) FROM tag_links tl WHERE tl.tagId = t.id) AS bookCount
        FROM tags t WHERE t.isHidden = 0 ORDER BY bookCount DESC, t.name COLLATE NOCASE ASC LIMIT :limit"""
    )
    fun getTopTags(limit: Int): Flow<List<TagWithCount>>

    /** 首页/选择器用：只出可见标签（按名称） */
    @Query(
        """SELECT t.*, (SELECT COUNT(*) FROM tag_links tl WHERE tl.tagId = t.id) AS bookCount
        FROM tags t WHERE t.isHidden = 0 ORDER BY t.name COLLATE NOCASE ASC"""
    )
    fun getTagsWithCounts(): Flow<List<TagWithCount>>

    /** 管理页用：全部标签含隐藏 */
    @Query(
        """SELECT t.*, (SELECT COUNT(*) FROM tag_links tl WHERE tl.tagId = t.id) AS bookCount
        FROM tags t ORDER BY t.isHidden ASC, t.name COLLATE NOCASE ASC"""
    )
    fun getAllTagsWithCounts(): Flow<List<TagWithCount>>

    @Query(
        """SELECT t.*, (SELECT COUNT(*) FROM tag_links tl WHERE tl.tagId = t.id) AS bookCount
        FROM tags t WHERE t.isHidden = 0 AND t.name LIKE '%' || :q || '%' ORDER BY bookCount DESC, t.name COLLATE NOCASE ASC"""
    )
    suspend fun searchTags(q: String): List<TagWithCount>

    @Query("UPDATE tags SET isHidden = :hidden WHERE id = :id")
    suspend fun setTagHidden(id: Long, hidden: Boolean)

    /** AND 语义：同时带 n 个标签的书（索引查询，spec.md §八） */
    @Query(
        """SELECT b.* FROM manga_books b
        INNER JOIN tag_links tl ON b.id = tl.bookId
        WHERE b.isHidden = 0 AND tl.tagId IN (:tagIds)
        GROUP BY b.id HAVING COUNT(DISTINCT tl.tagId) = :n"""
    )
    suspend fun getBooksByTagsAnd(tagIds: List<Long>, n: Int): List<Book>

    /** OR 语义：带任一标签的书 */
    @Query(
        """SELECT DISTINCT b.* FROM manga_books b
        INNER JOIN tag_links tl ON b.id = tl.bookId
        WHERE b.isHidden = 0 AND tl.tagId IN (:tagIds)"""
    )
    suspend fun getBooksByTagsOr(tagIds: List<Long>): List<Book>

    @Query("SELECT * FROM tag_links WHERE tagId IN (:tagIds)")
    suspend fun getLinksForTags(tagIds: List<Long>): List<TagLink>

    /** 合并标签：源的 tag_links 全部搬到目标（目标已有的静默忽略），源标签删除（FK CASCADE 连带清 links） */
    @Transaction
    suspend fun mergeTags(targetId: Long, sourceIds: List<Long>) {
        val valid = sourceIds.filter { it != targetId }
        if (valid.isEmpty()) return
        val links = getLinksForTags(valid)
        for (l in links) linkTag(TagLink(tagId = targetId, bookId = l.bookId, source = l.source))
        deleteTagsByIds(valid)
    }

    @Query("DELETE FROM tags WHERE id IN (:ids)")
    suspend fun deleteTagsByIds(ids: List<Long>)

    /** 一本书批量设标签（详情页/批量打标签共用） */
    @Transaction
    suspend fun setBookTags(bookId: Long, names: List<String>, source: Int) {
        for (name in names) {
            val tagId = ensureTag(name)
            if (tagId != -1L) linkTag(TagLink(tagId = tagId, bookId = bookId, source = source))
        }
    }

    /** "从 XML 重建"专用：只清自动标签（source=0），手动标签永不动（spec.md §五冲突策略） */
    @Query("DELETE FROM tag_links WHERE bookId = :bookId AND source = 0")
    suspend fun deleteAutoTagsForBook(bookId: Long)

    @Query("SELECT t.*, tl.source AS linkSource FROM tags t INNER JOIN tag_links tl ON t.id = tl.tagId WHERE tl.bookId = :bookId ORDER BY t.name COLLATE NOCASE ASC")
    suspend fun getTagsForBookWithSource(bookId: Long): List<TagWithSource>

    @Query("DELETE FROM tag_links WHERE tagId = :tagId AND bookId = :bookId")
    suspend fun deleteTagLinkByTagAndBook(tagId: Long, bookId: Long)

    /** 系列信息写入（本地 ComicInfo 与 Komga 共用；seriesId 本地用 "local:"+名字） */
    @Query("UPDATE manga_books SET seriesId = :seriesId, seriesName = :seriesName WHERE id = :id")
    suspend fun updateSeriesInfo(id: Long, seriesId: String, seriesName: String)

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY title ASC")
    suspend fun getAllVisibleBooksList(): List<Book>


    /** 本地 ComicInfo 系列的可见卷（seriesId="local:"+名字，serverId 路由用 "local" 伪值） */
    @Query("SELECT * FROM manga_books WHERE seriesId = :seriesId AND isHidden = 0 ORDER BY title ASC")
    suspend fun getLocalSeriesVisibleBooks(seriesId: String): List<Book>

    // ---- 标签管理（P3）----

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun renameTag(id: Long, name: String)

    @Query("UPDATE tags SET color = :color WHERE id = :id")
    suspend fun setTagColor(id: Long, color: Long)

    /** XML 标签转手动（转后重扫免疫） */
    @Query("UPDATE tag_links SET source = 1 WHERE tagId = :tagId AND source = 0")
    suspend fun markTagManual(tagId: Long)


    // ==================== §23 阅读记忆 ====================

    // ★ §37(14) 父书三态聚合用：一次拉出该书所有 reading_progress 状态做子卷 completed 统计
    //   ★ ⑥ 修复：加 `updatedAt > 0` 过滤掉 Scanner.kt:57 预写的占位行（writing updatedAt=0）。
    //   占位行形态 = 真用户停在首页未读完的合法国产行（都是 lastPage=0, completed=0）完全一致；
    //   唯一区分是 Scanner 写 updatedAt=0，所有真用户进度路径写 System.currentTimeMillis() > 0
    //   （save() 行 340 / enterReader 行 388 / markAsReadNow 行 724 / 动图弹窗 1245-1261 都已实地核过）。
    //   不动 getProgress 单条 query：保留对 Scanner 占位行的默认语义，避免影响 enterReader 的初始 cur 恢复。
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId AND updatedAt > 0 ORDER BY updatedAt DESC")
    suspend fun getAllProgressForBook(bookId: Long): List<ReadingProgress>

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId AND path = :path LIMIT 1")
    suspend fun getProgress(bookId: Long, path: String): ReadingProgress?

    @Query("""
        INSERT OR IGNORE INTO reading_progress(bookId, path, lastPage, completed, updatedAt, anim_dialog_choice, totalPages)
        VALUES (:bookId, :path, :lastPage, :completed, :updatedAt, 0, :totalPages)
    """)
    suspend fun insertOrIgnoreProgress(
        bookId: Long, path: String, lastPage: Int, completed: Boolean, updatedAt: Long,
        totalPages: Int
    )

    @Query("""
        UPDATE reading_progress SET
            lastPage = :lastPage,
            completed = :completed,
            updatedAt = :updatedAt,
            totalPages = max(totalPages, :totalPages)
        WHERE bookId = :bookId AND path = :path
    """)
    suspend fun updateProgress(
        bookId: Long, path: String, lastPage: Int, completed: Boolean, updatedAt: Long,
        totalPages: Int
    )

    @Transaction
    suspend fun upsertProgress(
        bookId: Long, path: String, lastPage: Int, completed: Boolean, updatedAt: Long,
        totalPages: Int
    ) {
        insertOrIgnoreProgress(bookId, path, lastPage, completed, updatedAt, totalPages)
        updateProgress(bookId, path, lastPage, completed, updatedAt, totalPages)
    }

    // ★ 3+5 改动2：@Transaction 批量保存阅读进度——把 updateReadingProgress + upsertProgress + insertHistory
    //   三条写入打包在单个事务内，save/兜底 save 共用此方法。@Transaction 保证不读到半折叠状态。
    //   绝对不包 syncBookStatusFromProgress：它含 engine.children 文件 IO + nested scope.launch，
    //   长持事务会卡读者。
    @Transaction
    suspend fun saveReaderProgressBatch(
        bookId: Long, pages: Int, timestamp: Long,
        chPath: String, lastPage: Int, completed: Boolean,
        history: ReadingHistory, totalPages: Int
    ) {
        updateReadingProgress(bookId, pages, timestamp)
        upsertProgress(bookId, chPath, lastPage, completed, timestamp, totalPages)
        insertHistory(history)
    }

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun clearProgress(bookId: Long)

    // ★ ④ 修复：单卷级进度精删（区别 clearProgress 清整本）——FAB 死胡同兜底需要"仅删这一卷坏 progress"
    //   用场景：listPages 失败（源文件丢失或 path 错乱）+该卷有 progress 历史时，用户主动选删；
    //   不能误删用户的合法进度（SAF 权限 revoke 等临时 IO 失败时 progress 其实是好的，用户不去点即可保留）。
    @Query("DELETE FROM reading_progress WHERE bookId = :bookId AND path = :path")
    suspend fun deleteProgress(bookId: Long, path: String)

    // ★ 综合 方案 D（§37(14) 修订）：Scanner 预写 progress 行，确保 Library 三态徽章有完整数据底。
    //   用 INSERT OR IGNORE 走 (bookId, path) 唯一索引：
    //     - 新书预写：原本无行的 (bookId, chPath) 会被插入 lastPage=0 completed=false
    //     - 重扫书架：已存在行直接 ignored，不覆盖用户已读进度（防"用户重扫书架会丢完成状态"）
    //   只调一次→enterReader 内已有的 upsertProgress ON CONFLICT DO UPDATE 已覆盖常规翻页路径，
    //   预写跟它语义不冲突（预写是"补空白"，翻页是"有行则更新、没行则补建"——后者永远兜底）。
    @Query("""
        INSERT OR IGNORE INTO reading_progress(bookId, path, lastPage, completed, updatedAt, anim_dialog_choice)
        VALUES (:bookId, :path, 0, 0, 0, 0)
    """)
    suspend fun preinsertProgressIfAbsent(bookId: Long, path: String)

    /** ★ C6 修复：整书叶子卷占位行打包单事务（旧逐条各自事务 fsync N 次） */
    @Transaction
    suspend fun preinsertProgressForBook(bookId: Long, paths: List<String>) {
        for (p in paths) preinsertProgressIfAbsent(bookId, p)
    }

    // ★ C1 补丁（2026-08-25 实机回归）：reading_history/bookshelf_items 无 Book 外键，
    //   删书后旧引用行永久残留——回填前必须显式清掉，否则每次重扫 history 成倍堆积
    @Query("DELETE FROM reading_history WHERE bookId IN (:bookIds)")
    suspend fun deleteHistoryForBookIds(bookIds: List<Long>)

    /** ★ F：单条历史移除（历史页长按删除，支持一次删同书聚合的全部行） */
    @Query("DELETE FROM reading_history WHERE id IN (:ids)")
    suspend fun deleteHistoryByIds(ids: List<Long>)

    @Query("DELETE FROM bookshelf_items WHERE bookId IN (:bookIds)")
    suspend fun deleteShelfItemsForBookIds(bookIds: List<Long>)

    // ★ 决策 3：动图对话框选择独立写路径
    @Query("UPDATE reading_progress SET anim_dialog_choice = :choice WHERE bookId = :bookId AND path = :path")
    suspend fun setAnimDialogChoice(bookId: Long, path: String, choice: Int)

    @Query("SELECT anim_dialog_choice FROM reading_progress WHERE bookId = :bookId AND path = :path LIMIT 1")
    suspend fun getAnimDialogChoice(bookId: Long, path: String): Int?

    // ==================== ★ 书签（DB v12 纯增量） ====================

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getBookmarksForBook(bookId: Long): List<Bookmark>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE bookId = :bookId AND chapterPath = :chapterPath AND page = :page")
    suspend fun countBookmark(bookId: Long, chapterPath: String, page: Int): Int

    /** IGNORE 走 (bookId,chapterPath,page) 唯一索引：重复添加/迁移冲突静默吸收，返回 -1=冲突 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(b: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND chapterPath = :chapterPath AND page = :page")
    suspend fun deleteBookmark(bookId: Long, chapterPath: String, page: Int)

    @Query("UPDATE bookmarks SET note = :note WHERE bookId = :bookId AND chapterPath = :chapterPath AND page = :page")
    suspend fun updateBookmarkNote(bookId: Long, chapterPath: String, page: Int, note: String?)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND chapterPath = :chapterPath AND page = :page")
    suspend fun getBookmark(bookId: Long, chapterPath: String, page: Int): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE bookId IN (:bookIds)")
    suspend fun getBookmarksForBooks(bookIds: List<Long>): List<Bookmark>

    // ==================== 下载任务持久化 ====================

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun getAllDownloadTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    suspend fun getAllDownloadTasksList(): List<DownloadTaskEntity>

    @Insert
    suspend fun insertDownloadTask(t: DownloadTaskEntity): Long

    @Update
    suspend fun updateDownloadTask(t: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteDownloadTask(id: Long)

    @Query("DELETE FROM download_tasks WHERE status = 'Completed' OR status = 'Failed'")
    suspend fun clearFinishedDownloadTasks()

    @Query("DELETE FROM download_tasks WHERE bookId = :bookId")
    suspend fun deleteDownloadTaskByBookId(bookId: Long)

    // ==================== ★ C1 修复：rescan 四表快照回填 ====================

    /** 全字段裸插入回填行（内存合并后调用，IGNORE 仅作防御） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProgressFull(row: ReadingProgress)

    @Query("SELECT * FROM reading_progress WHERE bookId IN (:bookIds)")
    suspend fun getProgressForBooks(bookIds: List<Long>): List<ReadingProgress>

    @Query("SELECT * FROM reading_history WHERE bookId IN (:bookIds)")
    suspend fun getHistoryForBooks(bookIds: List<Long>): List<ReadingHistory>

    @Query("SELECT * FROM bookshelf_items WHERE bookId IN (:bookIds)")
    suspend fun getShelfItemsForBooks(bookIds: List<Long>): List<BookshelfItem>

    @Query("SELECT * FROM tag_links WHERE bookId IN (:bookIds)")
    suspend fun getTagLinksForBooks(bookIds: List<Long>): List<TagLink>

    /**
     * ★ C1 修复：rescan 原子替换同文件夹书目。
     *
     *   旧实现"先删后插"无事务，且 Book 删除经外键 CASCADE 连带清光 reading_progress/tag_links
     *   （Database.kt :111/:73 外键实锤），bookshelf_items 还会留孤儿记录、history 的 bookId 悬空——
     *   用户重扫一次书架就丢全部阅读进度。现流程（单事务；SAF 扫描已在事务外完成，防长事务锁库）：
     *     ① getBooksByFolderParams 快照旧书（与 deleteBooksByFolderParams 完全同 LIKE 范围）
     *     ② 快照四表旧数据（progress/history/bookshelf_items/tag_links）
     *     ③ 删旧书（CASCADE 清掉的正是②已快照的数据）
     *     ④ insertBooksBatch 返回新 id，按 filePath 建 oldId→newId 映射（filePath 有索引无唯一约束）
     *     ⑤ 回填四表：
     *        - progress：(newId,path) 冲突（多旧书映射同一新书）保留 updatedAt 最新一条
     *        - history/bookshelf_items/tag_links：直接重挂 newId
     *   已知取舍（K.c）：极端并发（正在阅读时 rescan）退出保存写旧 bookId 会因 FK 失败被上层
     *   try-catch 吞掉——丢一次保存不崩，可接受。
     */
    @Transaction
    suspend fun rescanFolderReplace(auth: String, treeDocId: String, newBooks: List<Book>) {
        // ★ C8：前缀 Kotlin 侧拼好（a/z 带尾 '|'，d 不带——逐字符复刻旧 LIKE 拼接）
        val aPrefix = "a:$auth|$treeDocId|%"
        val dPrefix = "d:$auth|$treeDocId%"
        val zPrefix = "z:a:$auth|$treeDocId|%"
        val oldBooks = getBooksByFolderParams(aPrefix, dPrefix, zPrefix)
        if (oldBooks.isEmpty()) {
            insertBooksWithStableId(newBooks)
            return
        }
        val oldIds = oldBooks.map { it.id }
        val progSnap = getProgressForBooks(oldIds)
        val histSnap = getHistoryForBooks(oldIds)
        val shelfSnap = getShelfItemsForBooks(oldIds)
        val tagSnap = getTagLinksForBooks(oldIds)
        // ★ 书签随书迁移：bookmarks 有 FK CASCADE，删书时行会级联清——必须在删书前快照
        val bookmarkSnap = getBookmarksForBooks(oldIds)

        deleteBooksByFolderParams(aPrefix, dPrefix, zPrefix)

        // ★ C1 补丁：新书继承旧书的阅读元数据。readPages/lastReadAt 是书架三态徽章与
        //   "继续阅读"FAB 的数据源，totalPages/isFavorite/isHidden/addedAt 同属用户资产，
        //   删书重插若不继承则"阅读中"全部消失
        val oldByPath = HashMap<String, Book>(oldBooks.size * 2)
        for (ob in oldBooks) oldByPath[ob.filePath] = ob
        val mergedNew = newBooks.map { nb ->
            oldByPath[nb.filePath]?.let { ob ->
                nb.copy(
                    readPages = ob.readPages, lastReadAt = ob.lastReadAt,
                    totalPages = ob.totalPages, isFavorite = ob.isFavorite,
                    isHidden = ob.isHidden, addedAt = ob.addedAt
                )
            } ?: nb
        }

        val newIds = insertBooksWithStableId(mergedNew)

        val newIdByPath = HashMap<String, Long>(mergedNew.size * 2)
        mergedNew.forEachIndexed { i, b ->
            newIds.getOrNull(i)?.takeIf { it != -1L }?.let { newIdByPath[b.filePath] = it }
        }
        if (newIdByPath.isEmpty()) return

        val newIdByOldId = HashMap<Long, Long>(oldBooks.size * 2)
        for (ob in oldBooks) newIdByPath[ob.filePath]?.let { newIdByOldId[ob.id] = it }
        if (newIdByOldId.isNotEmpty()) {
            // ★ C1 补丁：无外键表先清旧 bookId 引用再重插映射行，防悬空堆积。
            //   仅在确有映射时清理——映射失败时保留旧行（历史页显示"已删除书籍"优于丢数据）
            deleteHistoryForBookIds(oldIds)
            deleteShelfItemsForBookIds(oldIds)

            // progress 回填：内存按 (newId,path) 合并，同 key 保留 updatedAt 最新一条后 IGNORE 插入
            val progMerged = HashMap<Pair<Long, String>, ReadingProgress>()
            for (p in progSnap) {
                val nid = newIdByOldId[p.bookId] ?: continue
                val key = nid to p.path
                val exist = progMerged[key]
                if (exist == null || p.updatedAt >= exist.updatedAt) progMerged[key] = p.copy(bookId = nid)
            }
            for (p in progMerged.values) insertProgressFull(p)

            for (h in histSnap) {
                newIdByOldId[h.bookId]?.let { insertHistory(h.copy(id = 0, bookId = it)) }
            }
            val insertedShelfKeys = HashSet<Pair<Long, Long>>()
            for (s in shelfSnap) {
                newIdByOldId[s.bookId]?.let { newBookId ->
                    val key = newBookId to s.bookshelfId
                    if (key !in insertedShelfKeys) {
                        insertBookshelfItem(BookshelfItem(bookshelfId = s.bookshelfId, bookId = newBookId))
                        insertedShelfKeys.add(key)
                    }
                }
            }
            val insertedTagKeys = HashSet<Pair<Long, Long>>()
            for (t in tagSnap) {
                newIdByOldId[t.bookId]?.let { newBookId ->
                    val key = newBookId to t.tagId
                    if (key !in insertedTagKeys) {
                        insertTagLink(TagLink(tagId = t.tagId, bookId = newBookId))
                        insertedTagKeys.add(key)
                    }
                }
            }

            // ★ 书签重挂新 id：同 filePath 多旧行映射同一新书时，同页书签经唯一索引 IGNORE 吸收
            for (bm in bookmarkSnap) {
                newIdByOldId[bm.bookId]?.let { insertBookmark(bm.copy(id = 0, bookId = it)) }
            }
        }
    }

    // ==================== ★ 书库一次性去重（DedupBooks） ====================

    @Query("DELETE FROM reading_progress WHERE bookId IN (:bookIds)")
    suspend fun deleteProgressForBookIds(bookIds: List<Long>)

    @Query("DELETE FROM tag_links WHERE bookId IN (:bookIds)")
    suspend fun deleteTagLinksForBookIds(bookIds: List<Long>)

    /**
     * ★ 书库去重（幂等；App 启动后台跑，复用 rescanFolderReplace 的四表回填模式）。
     *   背景：manga_books.filePath 只有普通索引无唯一约束，历史重复导入/并发扫描留下了
     *   同一 filePath 多行（实机诊断实锤 book=35/7 同书两行）——书架重复显示、进度挂错 id。
     *
     *   每组同 filePath 行的处理（单事务，任一组失败整体回滚）：
     *     ① 存活者 = 活跃度最高（max(自身 lastReadAt, 其 progress 行最大 updatedAt)），平手取小 id
     *     ② 四表迁移：
     *        - progress：全组行（含存活者自己的）按 (存活id, path) 合并，同 key 保 updatedAt
     *          最新，全组删后重插赢家——不能只插不删：存活者旧行若更旧，IGNORE 会挡住新赢家
     *        - history：冗余 id 行重挂存活 id（reading_history 无 Book 外键，须显式清）
     *        - bookshelf_items / tag_links：重挂前查重——存活者已在同书架/同标签则跳过
     *     ③ 存活者 Book 行吸收冗余行资产（readPages/totalPages 取 max、lastReadAt 取最新、
     *        isFavorite 取或、addedAt 取最早、coverPath 空则从冗余行补）
     *     ④ 删冗余行——此时 CASCADE 清掉的只是已搬空的壳
     *   已知取舍（与 rescanFolderReplace 的 K.c 同款）：与 rescan/正在阅读并发碰撞时
     *   FK 失败 → 事务整体回滚，无损，下次启动重试。
     */
    @Transaction
    suspend fun dedupMigrateAndDelete(groups: List<List<Book>>): DedupResult {
        var migratedProgress = 0; var migratedHistory = 0; var migratedShelf = 0; var migratedTags = 0
        var migratedBookmarks = 0
        val outcomes = mutableListOf<DedupOutcome>()
        for (group in groups) {
            if (group.size < 2) continue
            val ids = group.map { it.id }
            val progSnap = getProgressForBooks(ids)
            // ① 存活者：活跃度优先，平手小 id（老记录被书架/历史引用的概率更高）
            val survivor = group.sortedWith(
                compareByDescending<Book> { b ->
                    maxOf(b.lastReadAt ?: 0L, progSnap.filter { it.bookId == b.id }.maxOfOrNull { it.updatedAt } ?: 0L)
                }.thenBy { it.id }
            ).first()
            val dupIds = ids.filter { it != survivor.id }

            val histSnap = getHistoryForBooks(dupIds)
            val shelfSnap = getShelfItemsForBooks(dupIds)
            val tagSnap = getTagLinksForBooks(dupIds)
            val bookmarkSnap = getBookmarksForBooks(dupIds)

            // ② progress：全组删后按赢家重插
            deleteProgressForBookIds(ids)
            val progMerged = HashMap<Pair<Long, String>, ReadingProgress>()
            for (p in progSnap) {
                val key = survivor.id to p.path
                val exist = progMerged[key]
                if (exist == null || p.updatedAt >= exist.updatedAt) progMerged[key] = p.copy(id = 0, bookId = survivor.id)
            }
            for (p in progMerged.values) insertProgressFull(p)
            migratedProgress += progMerged.size

            deleteHistoryForBookIds(dupIds)
            for (h in histSnap) insertHistory(h.copy(id = 0, bookId = survivor.id))
            migratedHistory += histSnap.size

            deleteShelfItemsForBookIds(dupIds)
            val survivorShelves = getShelfItemsForBooks(listOf(survivor.id)).map { it.bookshelfId }.toHashSet()
            for (s in shelfSnap) {
                if (s.bookshelfId in survivorShelves) continue
                insertBookshelfItem(BookshelfItem(bookshelfId = s.bookshelfId, bookId = survivor.id))
                survivorShelves.add(s.bookshelfId); migratedShelf++
            }

            deleteTagLinksForBookIds(dupIds)
            val survivorTags = getTagLinksForBooks(listOf(survivor.id)).map { it.tagId }.toHashSet()
            for (t in tagSnap) {
                if (t.tagId in survivorTags) continue
                insertTagLink(TagLink(tagId = t.tagId, bookId = survivor.id))
                survivorTags.add(t.tagId); migratedTags++
            }

            // ★ 书签重挂存活者：唯一索引 IGNORE 吸收存活者已有同页书签的冲突
            for (bm in bookmarkSnap) {
                insertBookmark(bm.copy(id = 0, bookId = survivor.id))
                migratedBookmarks++
            }

            // ③ 存活者吸收资产
            updateBook(survivor.copy(
                readPages = group.maxOf { it.readPages },
                totalPages = group.maxOf { it.totalPages },
                lastReadAt = group.mapNotNull { it.lastReadAt }.maxOrNull(),
                isFavorite = group.any { it.isFavorite },
                addedAt = group.minOf { it.addedAt },
                coverPath = survivor.coverPath ?: group.firstNotNullOfOrNull { it.coverPath },
            ))

            // ④ 删冗余
            deleteBooksByIds(dupIds)
            outcomes += DedupOutcome(survivor.id, dupIds, survivor.filePath)
        }
        return DedupResult(outcomes, migratedProgress, migratedHistory, migratedShelf, migratedTags, migratedBookmarks)
    }

    @Query("SELECT * FROM reading_progress")
    suspend fun exportAllProgress(): List<ReadingProgress>

    @Query("SELECT * FROM reading_history")
    suspend fun exportAllHistory(): List<ReadingHistory>

    @Query("SELECT * FROM bookmarks")
    suspend fun exportAllBookmarks(): List<Bookmark>

    @Query("SELECT id, readPages, lastReadAt, filePath FROM manga_books")
    suspend fun exportAllBooksAggregate(): List<BookAggregateRow>

    @Query("SELECT id, isFavorite, isHidden FROM manga_books")
    suspend fun exportAllBooksShelfFlags(): List<BookShelfFlagsRow>

    @Query("SELECT * FROM bookshelves")
    suspend fun exportAllBookshelves(): List<Bookshelf>

    @Query("SELECT * FROM bookshelf_items")
    suspend fun exportAllBookshelfItems(): List<BookshelfItem>

    @Query("SELECT * FROM tags")
    suspend fun exportAllTags(): List<Tag>

    @Query("SELECT * FROM tag_links")
    suspend fun exportAllTagLinks(): List<TagLink>

    @Query("SELECT * FROM imported_folders")
    suspend fun exportAllImportedFolders(): List<Folder>

    @Query("SELECT * FROM SMB_SOURCES")
    suspend fun exportAllSmbSources(): List<com.ehviewer.core.database.model.SmbSourceEntity>

    @Query("SELECT * FROM WEBDAV_SOURCES")
    suspend fun exportAllWebDavSources(): List<com.ehviewer.core.database.model.WebDavSourceEntity>

    @Query("SELECT * FROM LIBRARY_ROOTS")
    suspend fun exportAllLibraryRoots(): List<com.ehviewer.core.database.model.LibraryRootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgressReplace(items: List<ReadingProgress>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProgressIgnore(items: List<ReadingProgress>)

    @Query("DELETE FROM reading_progress")
    suspend fun clearAllProgress()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryReplace(items: List<ReadingHistory>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistoryIgnore(items: List<ReadingHistory>)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarksReplace(items: List<Bookmark>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmarksIgnore(items: List<Bookmark>)

    @Query("DELETE FROM bookmarks")
    suspend fun clearAllBookmarks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookshelvesReplace(items: List<Bookshelf>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookshelvesIgnore(items: List<Bookshelf>)

    @Query("DELETE FROM bookshelves")
    suspend fun clearAllBookshelves()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookshelfItemsReplace(items: List<BookshelfItem>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookshelfItemsIgnore(items: List<BookshelfItem>)

    @Query("DELETE FROM bookshelf_items")
    suspend fun clearAllBookshelfItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagsReplace(items: List<Tag>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagsIgnore(items: List<Tag>)

    @Query("DELETE FROM tags")
    suspend fun clearAllTags()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagLinksReplace(items: List<TagLink>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagLinksIgnore(items: List<TagLink>)

    @Query("DELETE FROM tag_links")
    suspend fun clearAllTagLinks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmbSourcesReplace(items: List<com.ehviewer.core.database.model.SmbSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSmbSourcesIgnore(items: List<com.ehviewer.core.database.model.SmbSourceEntity>)

    @Query("DELETE FROM SMB_SOURCES")
    suspend fun clearAllSmbSources()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWebDavSourcesReplace(items: List<com.ehviewer.core.database.model.WebDavSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWebDavSourcesIgnore(items: List<com.ehviewer.core.database.model.WebDavSourceEntity>)

    @Query("DELETE FROM WEBDAV_SOURCES")
    suspend fun clearAllWebDavSources()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLibraryRootsReplace(items: List<com.ehviewer.core.database.model.LibraryRootEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibraryRootsIgnore(items: List<com.ehviewer.core.database.model.LibraryRootEntity>)

    @Query("DELETE FROM LIBRARY_ROOTS")
    suspend fun clearAllLibraryRoots()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportedFoldersReplace(items: List<Folder>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImportedFoldersIgnore(items: List<Folder>)

    @Query("DELETE FROM imported_folders")
    suspend fun clearAllImportedFolders()

    @Query("UPDATE manga_books SET readPages = :readPages, lastReadAt = :lastReadAt WHERE id = :id")
    suspend fun updateBookAggregate(id: Long, readPages: Int, lastReadAt: Long?)

    @Query("UPDATE manga_books SET isFavorite = :isFavorite, isHidden = :isHidden WHERE id = :id")
    suspend fun updateBookShelfFlags(id: Long, isFavorite: Boolean, isHidden: Boolean)
}

data class BookAggregateRow(
    val id: Long,
    val readPages: Int,
    val lastReadAt: Long?,
    val filePath: String,
)

data class BookShelfFlagsRow(
    val id: Long,
    val isFavorite: Boolean,
    val isHidden: Boolean,
)

/** ★ 书库去重结果（日志 + prefs/SR 队列迁移用） */
data class DedupOutcome(
    val survivorId: Long,
    val dupIds: List<Long>,
    val filePath: String,
)

data class DedupResult(
    val outcomes: List<DedupOutcome>,
    val migratedProgress: Int,
    val migratedHistory: Int,
    val migratedShelf: Int,
    val migratedTags: Int,
    val migratedBookmarks: Int = 0,
)

/**
 * ★ v1.13 A'：历史页 progress 主导行——每分卷一条独立阅读记忆。
 *   totalPages 为卷级真实页数（v11 起写入；存量旧行=0 时 UI 退化显示"第 X 页"）。
 */
data class ProgressHistoryRow(
    val bookId: Long,
    val chPath: String,
    val bookTitle: String?,
    val coverPath: String?,
    val lastPage: Int,
    val totalPages: Int,
    val updatedAt: Long,
    val completed: Boolean
)

@Entity(tableName = "download_tasks", indices = [Index("bookId")])
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceKind: String,
    val sourcePath: String,
    val localPath: String? = null,
    val status: String = "Queued",
    val progress: Float = 0f,
    val error: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val bookId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "komga_series_cache",
    indices = [Index(value = ["serverId", "seriesId"], unique = true)]
)
data class KomgaSeriesCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val seriesId: String,
    val libraryId: String = "",
    val title: String = "",
    val summary: String = "",
    val authors: String = "",
    val status: String = "",
    val tags: String = "",
    val genres: String = "",
    val readingDirection: String = "",
    val coverUrl: String = "",
    val cachedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "komga_book_memo",
    indices = [Index(value = ["serverId", "bookId"], unique = true)]
)
data class KomgaBookMemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val bookId: String,
    val seriesId: String = "",
    val title: String = "",
    val number: Int = 0,
    val pageCount: Int = 0,
    val summary: String = "",
    val mediaType: String = "",
    val mediaProfile: String = "",
    val cachedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "komga_progress_sync_queue",
    indices = [Index(value = ["serverId", "bookId"])]
)
data class KomgaProgressSyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val bookId: String,
    val page: Int = 0,
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Database(
    entities = [Book::class, Folder::class, ReadingHistory::class, Bookshelf::class, BookshelfItem::class, Tag::class, TagLink::class, ReadingProgress::class, Bookmark::class, com.ehviewer.core.database.model.SmbSourceEntity::class, com.ehviewer.core.database.model.LibraryRootEntity::class, com.ehviewer.core.database.model.LocalGalleryEntity::class, com.ehviewer.core.database.model.WebDavSourceEntity::class, DownloadTaskEntity::class, KomgaSeriesCacheEntity::class, KomgaBookMemoEntity::class, KomgaProgressSyncQueueEntity::class],
    version = 25,
    exportSchema = false
)
abstract class MangaDatabase : com.ehviewer.core.database.LocalLibraryDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract override fun smbSourceDao(): com.ehviewer.core.database.dao.SmbSourceDao

    companion object {
        @Volatile
        private var INSTANCE: MangaDatabase? = null

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, color INTEGER NOT NULL DEFAULT 8134264)")
                database.execSQL("CREATE TABLE IF NOT EXISTS tag_links (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tagId INTEGER NOT NULL, bookId INTEGER NOT NULL, FOREIGN KEY (tagId) REFERENCES tags(id) ON DELETE CASCADE, FOREIGN KEY (bookId) REFERENCES manga_books(id) ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tag_links_tagId ON tag_links(tagId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tag_links_bookId ON tag_links(bookId)")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reading_history ADD COLUMN scrollX INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE reading_history ADD COLUMN scrollY INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE reading_history ADD COLUMN zoomLevel REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_isHidden ON manga_books(isHidden)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_isHidden_lastReadAt ON manga_books(isHidden, lastReadAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_isHidden_isFavorite ON manga_books(isHidden, isFavorite)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_filePath ON manga_books(filePath)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_lastReadAt ON manga_books(lastReadAt)")
            }
        }

        // §23 阅读记忆：新增 reading_progress 表（按 (bookId, path) 唯一）
        // 自举：把 manga_books.readPages 一次性灌入新表 → 老用户升级不丢已读位
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_progress (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        bookId INTEGER NOT NULL,
                        path TEXT NOT NULL,
                        lastPage INTEGER NOT NULL DEFAULT 0,
                        completed INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        anim_dialog_choice INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(bookId) REFERENCES manga_books(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reading_progress_bookId_path ON reading_progress(bookId, path)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_reading_progress_bookId ON reading_progress(bookId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_reading_progress_updatedAt ON reading_progress(updatedAt)")

                // 自举：每本已读书的 readPages（1-based）→ lastPage（0-based = readPages - 1）
                database.execSQL("""
                    INSERT INTO reading_progress(bookId, path, lastPage, completed, updatedAt, anim_dialog_choice)
                    SELECT id, filePath,
                           MAX(0, readPages - 1),
                           CASE WHEN totalPages > 0 AND readPages >= totalPages THEN 1 ELSE 0 END,
                           COALESCE(lastReadAt, strftime('%s','now') * 1000),
                           0
                    FROM manga_books
                    WHERE readPages > 0
                """.trimIndent())
            }
        }

        // ★ v1.13 A'：reading_progress 加卷级 totalPages 列（历史页"共 N 页"数据源）
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE reading_progress ADD COLUMN totalPages INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // ★ 书签表（纯新增，不动现有表——Room 迁移后 schema 校验唯一风险点只在新表 SQL 与实体逐字匹配）
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        chapterPath TEXT NOT NULL,
                        page INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES manga_books(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_bookId_chapterPath_page ON bookmarks(bookId, chapterPath, page)")
            }
        }

        // ★ SMB 源表 + 本地库表（DB v13 纯增量，无外键——SMB_SOURCES 独立于 manga_books）
        //   列名对齐参考项目 com.ehviewer.core.database.model.SmbSourceEntity 的 @ColumnInfo
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS SMB_SOURCES (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        DISPLAY_NAME TEXT NOT NULL,
                        HOST TEXT NOT NULL,
                        PORT INTEGER NOT NULL,
                        EASYTIER_HOST TEXT NOT NULL DEFAULT '',
                        SHARE TEXT NOT NULL,
                        PATH_PREFIX TEXT NOT NULL,
                        USERNAME TEXT NOT NULL,
                        DOMAIN TEXT NOT NULL,
                        ADDED_AT INTEGER NOT NULL,
                        LAST_OK_AT INTEGER,
                        LAST_ERROR TEXT
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS LIBRARY_ROOTS (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        TREE_URI TEXT NOT NULL,
                        DISPLAY_NAME TEXT NOT NULL,
                        ADDED_AT INTEGER NOT NULL,
                        ROLE INTEGER NOT NULL DEFAULT 1,
                        ACCESS_MODE INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS LOCAL_GALLERIES (
                        ID INTEGER NOT NULL PRIMARY KEY,
                        ROOT_ID INTEGER NOT NULL,
                        RELATIVE_PATH TEXT NOT NULL,
                        TITLE TEXT NOT NULL,
                        KIND INTEGER NOT NULL,
                        PAGE_COUNT INTEGER NOT NULL,
                        COVER_PATH TEXT,
                        CONTENT_PATH TEXT NOT NULL,
                        MTIME INTEGER NOT NULL,
                        FOREIGN KEY(ROOT_ID) REFERENCES LIBRARY_ROOTS(ID) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_LOCAL_GALLERIES_ROOT_ID_RELATIVE_PATH ON LOCAL_GALLERIES(ROOT_ID, RELATIVE_PATH)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_LOCAL_GALLERIES_TITLE ON LOCAL_GALLERIES(TITLE)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS WEBDAV_SOURCES (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        DISPLAY_NAME TEXT NOT NULL,
                        BASE_URL TEXT NOT NULL,
                        EASYTIER_HOST TEXT NOT NULL DEFAULT '',
                        PATH_PREFIX TEXT NOT NULL,
                        USERNAME TEXT NOT NULL,
                        ADDED_AT INTEGER NOT NULL,
                        LAST_OK_AT INTEGER,
                        LAST_ERROR TEXT,
                        NO_COVER_FETCH INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE manga_books ADD COLUMN originalPath TEXT")
            }
        }

        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS download_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        sourceKind TEXT NOT NULL,
                        sourcePath TEXT NOT NULL,
                        localPath TEXT,
                        status TEXT NOT NULL DEFAULT 'Queued',
                        progress REAL NOT NULL DEFAULT 0,
                        error TEXT,
                        totalBytes INTEGER NOT NULL DEFAULT 0,
                        downloadedBytes INTEGER NOT NULL DEFAULT 0,
                        bookId INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_bookId ON download_tasks(bookId)")
            }
        }

        private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE bookmarks ADD COLUMN note TEXT")
            }
        }

        private val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS komga_series_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        serverId TEXT NOT NULL,
                        seriesId TEXT NOT NULL,
                        libraryId TEXT NOT NULL DEFAULT '',
                        title TEXT NOT NULL DEFAULT '',
                        summary TEXT NOT NULL DEFAULT '',
                        authors TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT '',
                        tags TEXT NOT NULL DEFAULT '',
                        genres TEXT NOT NULL DEFAULT '',
                        readingDirection TEXT NOT NULL DEFAULT '',
                        coverUrl TEXT NOT NULL DEFAULT '',
                        cachedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_komga_series_cache_serverId_seriesId ON komga_series_cache(serverId, seriesId)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS komga_book_memo (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        serverId TEXT NOT NULL,
                        bookId TEXT NOT NULL,
                        seriesId TEXT NOT NULL DEFAULT '',
                        title TEXT NOT NULL DEFAULT '',
                        number INTEGER NOT NULL DEFAULT 0,
                        pageCount INTEGER NOT NULL DEFAULT 0,
                        summary TEXT NOT NULL DEFAULT '',
                        mediaType TEXT NOT NULL DEFAULT '',
                        mediaProfile TEXT NOT NULL DEFAULT '',
                        cachedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_komga_book_memo_serverId_bookId ON komga_book_memo(serverId, bookId)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS komga_progress_sync_queue (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        serverId TEXT NOT NULL,
                        bookId TEXT NOT NULL,
                        page INTEGER NOT NULL DEFAULT 0,
                        completed INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_komga_progress_sync_queue_serverId_bookId ON komga_progress_sync_queue(serverId, bookId)")
            }
        }

        private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 源文件戳：重扫时 mtime+size 未变即复用旧分类（不再重开 zip 判子目录），
                // 变了才删封面重生成（对齐上游"已知 zip 复用 + 封面新鲜度"思想）
                database.execSQL("ALTER TABLE manga_books ADD COLUMN sourceMtimeMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE manga_books ADD COLUMN sourceSizeBytes INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE manga_books ADD COLUMN seriesId TEXT")
                database.execSQL("ALTER TABLE manga_books ADD COLUMN seriesName TEXT")
            }
        }

        private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // ★ 自定义封面字段（默认 null=未自定义，恢复默认=置 null）
                database.execSQL("ALTER TABLE manga_books ADD COLUMN customCoverPath TEXT")
            }
        }

        private val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE bookshelves ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // WebDAV 每源「不主动加载封面」开关（防服务器限流）；默认关，存量零感知
                database.execSQL("ALTER TABLE WEBDAV_SOURCES ADD COLUMN NO_COVER_FETCH INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tags ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                // 按 id 回填保持创建顺序
                database.execSQL("UPDATE tags SET sortOrder = id")
            }
        }

        private val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tags ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // ① 先去重（存量可能有重复行，不去重直接建联合唯一索引会让迁移崩溃）
                database.execSQL("DELETE FROM tag_links WHERE id NOT IN (SELECT MIN(id) FROM tag_links GROUP BY tagId, bookId)")
                // ② source 列：0=XML自动打标 1=用户手动
                database.execSQL("ALTER TABLE tag_links ADD COLUMN source INTEGER NOT NULL DEFAULT 1")
                // ③ 联合唯一索引（防并发/重放写入产生重复挂载）
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tag_links_pair ON tag_links(tagId, bookId)")
            }
        }

        fun getDatabase(context: Context): MangaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    "manga_reader_db"
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
