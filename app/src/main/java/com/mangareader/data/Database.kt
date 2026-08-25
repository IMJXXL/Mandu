package com.mangareader.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
    val totalPages: Int = 0,
    val readPages: Int = 0,
    val lastReadAt: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val source: String = "local"
)

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
    val sortOrder: Int = 0
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
    val color: Long = 0xFF7C8CF8
)

@Entity(
    tableName = "tag_links",
    foreignKeys = [
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagId"), Index("bookId")]
)
data class TagLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagId: Long,
    val bookId: Long
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
    val anim_dialog_choice: Int = 0
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

    @Query("SELECT p.* FROM reading_progress p INNER JOIN manga_books b ON b.id = p.bookId WHERE b.isHidden = 0 AND (p.lastPage > 0 OR p.completed = 1) ORDER BY p.updatedAt DESC LIMIT 1")
    fun getLastReading(): Flow<ReadingProgress?>

    @Query("SELECT * FROM manga_books WHERE isHidden = 1 ORDER BY lastReadAt DESC")
    fun getHiddenBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
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
    suspend fun insertBooksBatch(books: List<Book>)

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

    @Query("SELECT * FROM manga_books WHERE filePath LIKE 'a:' || :auth || '|' || :treeDocId || '|%' OR filePath LIKE 'd:' || :auth || '|' || :treeDocId || '%' OR filePath LIKE 'z:a:' || :auth || '|' || :treeDocId || '|%'")
    suspend fun getBooksByFolderParams(auth: String, treeDocId: String): List<Book>

    @Query("UPDATE manga_books SET isHidden = :hidden WHERE filePath LIKE 'a:' || :auth || '|' || :treeDocId || '|%' OR filePath LIKE 'd:' || :auth || '|' || :treeDocId || '%' OR filePath LIKE 'z:a:' || :auth || '|' || :treeDocId || '|%'")
    suspend fun setHiddenByFolderParams(auth: String, treeDocId: String, hidden: Boolean)

    @Query("SELECT * FROM manga_books WHERE filePath = :path LIMIT 1")
    suspend fun getBookByPath(path: String): Book?

    /** §37 误判修正：扫描发现某 zip/cbz 非漫画时，按 path 精确删除 DB 里已有的脏书（CASCADE 清 reading_progress） */
    @Query("DELETE FROM manga_books WHERE filePath = :path")
    suspend fun deleteBookByPath(path: String)

    @Query("DELETE FROM manga_books WHERE filePath LIKE '%' || :treeUriStr || '%'")
    suspend fun deleteBooksByFolder(treeUriStr: String)

    @Query("DELETE FROM manga_books WHERE (filePath LIKE 'a:' || :auth || '|' || :treeDocId || '|%' OR filePath LIKE 'd:' || :auth || '|' || :treeDocId || '%' OR filePath LIKE 'z:a:' || :auth || '|' || :treeDocId || '|%')")
    suspend fun deleteBooksByFolderParams(auth: String, treeDocId: String)

    @Query("SELECT COUNT(*) FROM manga_books WHERE (filePath LIKE 'a:' || :auth || '|' || :treeDocId || '|%' OR filePath LIKE 'd:' || :auth || '|' || :treeDocId || '%' OR filePath LIKE 'z:a:' || :auth || '|' || :treeDocId || '|%')")
    suspend fun countBooksByFolder(auth: String, treeDocId: String): Int

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

    @Query("SELECT COALESCE(SUM(totalPages), 0) FROM manga_books")
    suspend fun countTotalPages(): Int

    @Query("SELECT COUNT(*) FROM manga_books WHERE readPages > 0")
    suspend fun countReadBooks(): Int

    @Insert
    suspend fun insertHistory(history: ReadingHistory)

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
        INSERT INTO reading_progress(bookId, path, lastPage, completed, updatedAt, anim_dialog_choice)
        VALUES (:bookId, :path, :lastPage, :completed, :updatedAt, 0)
        ON CONFLICT(bookId, path) DO UPDATE SET
            lastPage = :lastPage,
            completed = :completed,
            updatedAt = :updatedAt
        -- ★ v1.11 修订：移除原 completed 的 CASE 单向锁（1→0 禁降）。
        --   实机教训：一旦误标 completed=1（如小页数卷 done 误判/历史脏数据），单向锁让该卷
        --   永久命中"已读完→跳第1页"（enterReader 决策1）且无法自愈——正是"记忆全失效永远回第一页"根因。
        --   改为直写后：save(done=false) 可把误标拉回 false 自然恢复；
        --   动图弹窗按钮不再走本方法（改用 preinsertProgressIfAbsent 占位 + setAnimDialogChoice），
        --   原"弹窗冲掉 completed"的担忧源头已消除。
    """)
    suspend fun upsertProgress(
        bookId: Long, path: String, lastPage: Int, completed: Boolean, updatedAt: Long
    )

    // ★ 3+5 改动2：@Transaction 批量保存阅读进度——把 updateReadingProgress + upsertProgress + insertHistory
    //   三条写入打包在单个事务内，save/兜底 save 共用此方法。@Transaction 保证不读到半折叠状态。
    //   绝对不包 syncBookStatusFromProgress：它含 engine.children 文件 IO + nested scope.launch，
    //   长持事务会卡读者。schema 不升版本（纯加 DAO 方法），version 保持 10。
    @Transaction
    suspend fun saveReaderProgressBatch(
        bookId: Long, pages: Int, timestamp: Long,
        chPath: String, lastPage: Int, completed: Boolean,
        history: ReadingHistory
    ) {
        updateReadingProgress(bookId, pages, timestamp)
        upsertProgress(bookId, chPath, lastPage, completed, timestamp)
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

    // ★ 决策 3：动图对话框选择独立写路径
    @Query("UPDATE reading_progress SET anim_dialog_choice = :choice WHERE bookId = :bookId AND path = :path")
    suspend fun setAnimDialogChoice(bookId: Long, path: String, choice: Int)

    @Query("SELECT anim_dialog_choice FROM reading_progress WHERE bookId = :bookId AND path = :path LIMIT 1")
    suspend fun getAnimDialogChoice(bookId: Long, path: String): Int?
}

@Database(
    entities = [Book::class, Folder::class, ReadingHistory::class, Bookshelf::class, BookshelfItem::class, Tag::class, TagLink::class, ReadingProgress::class],
    version = 10,
    exportSchema = false
)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao

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

        fun getDatabase(context: Context): MangaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    "manga_reader_db"
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
