package com.mangareader

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.mangareader.data.Book
import com.mangareader.data.Folder
import com.mangareader.data.MangaDao
import com.mangareader.zip.CommonsZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class Scanner(private val ctx: Context, private val dao: MangaDao) {
    // ★ v1.13 扫描优化：封面后台补齐专用域（独立 SupervisorJob，不阻塞扫描返回）
    private val coverScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    companion object {
        private const val TAG = "Scanner"

        // ★ C1 补丁：scan/rescan 全局互斥——并发双跑会双双读到旧 existingPaths 绕过查重，
        //   同批书插两遍（实机回归"导入后全部两份"的根源）
        private val scanMutex = kotlinx.coroutines.sync.Mutex()
    }

    suspend fun scan(uri: Uri, onProgress: ((Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        scanMutex.withLock {
        var count = 0
        try {
            val treeStr = uri.toString()
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val name = docId.substringAfterLast("/").replace("%20", " ")
            val auth = uri.authority ?: ""
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)

            val books = mutableListOf<Book>()
            scanDirFast(auth, treeStr, docId, child, books) { found -> onProgress?.invoke(found) }

            if (books.isNotEmpty()) {
                val newIds = dao.insertBooksBatch(books)
                count = books.size
                cacheRealPaths(books)
                // ★ 综合方案 D：Scanner 批插书行后，给每个 d:/z: 父书预写 progress 行。
                //   用 INSERT OR IGNORE 走 (bookId, path) 唯一索引；已存在 progress 行不会覆盖。
                //   - a: 单卷书不预写（无 children，进 Reader 时 upsertProgress 自然建行）
                //   - d: 父书：递归 children 拿所有"叶子卷"的 chPath，逐个 preinsert
                //   - z: 父书：调 children 拿内层子目录的 z: 路径，逐个 preinsert
                //   扫描本身已在后台协程（Dispatchers.IO），不阻 UI；递归只在批插后跑一次。
                //   ★ C6 修复：insertBooksBatch 已返回新 id（顺序=输入序），不再逐本 getBookByPath 回查；
                //     每本书的全部占位行走 preinsertProgressForBook 单事务（原每叶子各自事务 fsync N 次）
                try {
                    val engine = com.mangareader.viewer.ArchiveEngine(ctx)
                    for ((i, b) in books.withIndex()) {
                        try {
                            val bookId = newIds.getOrNull(i) ?: continue
                            if (bookId == -1L) continue
                            val leaves = collectLeafChPathsForBook(engine, b)
                            if (leaves.isNotEmpty()) {
                                dao.preinsertProgressForBook(bookId, leaves)
                                Log.d(TAG, "preinsertProgress: bookId=$bookId path=${b.filePath.takeLast(30)} leaves=${leaves.size}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "preinsertProgress for book ${b.filePath}: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "preinsertProgress batch: ${e.message}")
                }
            }

            val ex = dao.getFolderByUri(treeStr)
            if (ex != null) dao.updateFolder(ex.copy(lastScanAt = System.currentTimeMillis(), displayName = name))
            else dao.insertFolder(Folder(treeUri = treeStr, displayName = name, lastScanAt = System.currentTimeMillis()))
            Log.d(TAG, "scan: $count from $name (${System.currentTimeMillis()}ms)")
        } catch (e: Exception) { Log.e(TAG, "scan: ${e.message}", e) }
        count
        }
    }

    /**
     * ★ v1.13 扫描优化：封面预载并行化（4 路并发）。
     *   原为纯串行 for 循环——每本 SAF 打开+解压+JPEG 落盘 100~500ms，几百本要数分钟；
     *   并发后吞吐约 ×4。各书之间零共享状态（独立 thumb 文件），SAF ContentResolver 线程安全。
     *   skip 幂等逻辑保留：已有 thumb 直接跳过，重入安全。
     */
    suspend fun preloadCovers(books: List<Book>, onProgress: ((loaded: Int, total: Int) -> Unit)? = null) {
        val coverDir = File(ctx.filesDir, "covers")
        if (!coverDir.exists()) coverDir.mkdirs()
        val ok = java.util.concurrent.atomic.AtomicInteger()
        val fail = java.util.concurrent.atomic.AtomicInteger()
        val skip = java.util.concurrent.atomic.AtomicInteger()
        val noPath = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.concurrent.atomic.AtomicInteger()
        val total = books.size
        val sem = kotlinx.coroutines.sync.Semaphore(4)
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
        coroutineScope {
            for (b in books) {
                jobs.add(async(Dispatchers.IO) {
                    sem.withPermit {
                        val coverPath = b.coverPath
                        if (coverPath == null || !coverPath.startsWith("cv:")) { noPath.incrementAndGet(); return@withPermit }
                        try {
                            val key = md5(coverPath)
                            val file = File(coverDir, "$key.thumb")
                            if (file.exists()) { skip.incrementAndGet(); return@withPermit }

                            val parts = coverPath.removePrefix("cv:").split("|")
                            if (parts.size < 3) { fail.incrementAndGet(); return@withPermit }
                            val auth = parts[0]
                            val treeDocId = parts[1]
                            val docId = parts[2]
                            val treeUri = Uri.parse("content://$auth/tree/${Uri.encode(treeDocId)}")
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                            val ext = docId.substringAfterLast(".").lowercase()
                            if (ext in listOf("cbz", "zip", "epub")) {
                                preloadZipCover(coverPath, docUri, file)
                            } else if (ext in listOf("mobi", "azw", "azw3")) {
                                preloadMobiCover(coverPath, docUri, file)
                            } else if (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                                preloadImageCover(coverPath, docUri, file)
                            }
                            if (file.exists()) ok.incrementAndGet() else fail.incrementAndGet()
                        } catch (e: Exception) {
                            Log.e(TAG, "preloadCover failed: ${e.message}")
                            fail.incrementAndGet()
                        }
                        onProgress?.invoke(done.incrementAndGet(), total)
                    }
                })
            }
            jobs.joinAll()
        }
        Log.d(TAG, "preloadCovers done: ok=${ok.get()} fail=${fail.get()} skip=${skip.get()} noPath=${noPath.get()}")
    }

    private fun preloadZipCover(coverPath: String, docUri: Uri, file: File) {
        Log.d(TAG, "preloadZipCover: docUri=$docUri")
        try {
            val data = CommonsZipHelper.readImageEntry(ctx, docUri, 0)
            if (data != null && data.isNotEmpty()) {
                Log.d(TAG, "preloadZipCover: got ${data.size} bytes from CommonsZipHelper")
                val bmp = decodeCoverBitmap(data)
                if (bmp != null) {
                    file.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
                    bmp.recycle()
                    Log.d(TAG, "preloadZipCover: OK saved ${file.name}")
                    return
                }
                Log.w(TAG, "preloadZipCover: decodeCoverBitmap returned null")
            } else {
                Log.w(TAG, "preloadZipCover: CommonsZipHelper returned null/empty")
            }
        } catch (e: Exception) { Log.e(TAG, "preloadZipCover: ${e.message}") }
    }

    private fun preloadMobiCover(coverPath: String, docUri: Uri, file: File) {
        // fd-based opening
        try {
            val cacheKey = docUri.toString()
            val pfd = ctx.contentResolver.openFileDescriptor(docUri, "r")
            if (pfd != null) {
                val fd = pfd.detachFd()
                try {
                    try {
                        com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, fd)
                    } catch (_: Exception) {}
                    val rawData = com.mangareader.native.NativeEngine.readMobiCover(cacheKey)
                    if (rawData != null) {
                        val bmp = decodeCoverBitmap(rawData)
                        if (bmp != null) {
                            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                            bmp.recycle()
                            return
                        }
                    }
                } finally {
                    // ★ C5 修复：closeMobiHandle 把 openMobiWithFd 建的 refCount=1 整书 MobiCache 归零释放。
                    //   注意不要在此关 fd——native openMobiWithFd 五条路径全部自关（cache hit close(fd)/
                    //   fdopen 后 fclose(fp) 连同底层 fd），此处再关是 double-close，批量 preload 下 fd 号
                    //   复用会误关下一本书刚打开的 fd
                    try { com.mangareader.native.NativeEngine.closeMobiHandle(cacheKey) } catch (_: Exception) {}
                    try { pfd?.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        // Fallback: scan via ContentResolver (JPEG only)
        try {
            ctx.contentResolver.openInputStream(docUri)?.use { input ->
                val bis = java.io.BufferedInputStream(input, 65536)
                var b0 = 0; var b1 = bis.read(); var b2 = bis.read(); var b3 = bis.read()
                var pos = 4L
                while (b3 != -1) {
                    if (b0 == 0xFF && b1 == 0xD8) {
                        val imgData = readJpegFromStream(bis, pos)
                        if (imgData != null) {
                            val bmp = decodeCoverBitmap(imgData)
                            if (bmp != null) {
                                file.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
                                bmp.recycle()
                            }
                        }
                        return
                    }
                    b0 = b1; b1 = b2; b2 = b3; b3 = bis.read(); pos++
                }
            }
        } catch (_: Exception) {}
    }

    private fun readJpegFromStream(bis: java.io.BufferedInputStream, startPos: Long): ByteArray? {
        try {
            val baos = java.io.ByteArrayOutputStream()
            baos.write(0xFF)
            baos.write(0xD8)
            var prev = 0xFF
            while (true) {
                val b = bis.read(); if (b == -1) break
                baos.write(b)
                if (prev == 0xFF && b == 0xD9) break
                prev = b
            }
            return if (baos.size() > 100) baos.toByteArray() else null
        } catch (_: Exception) { return null }
    }

    private fun preloadImageCover(coverPath: String, docUri: Uri, file: File) {
        Log.d(TAG, "preloadImageCover: docUri=$docUri")
        try {
            ctx.contentResolver.openInputStream(docUri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                var sampleSize = 1
                while (opts.outWidth / sampleSize > 1024 || opts.outHeight / sampleSize > 1024) sampleSize *= 2
                Log.d(TAG, "preloadImageCover: ${opts.outWidth}x${opts.outHeight} sample=$sampleSize")
                ctx.contentResolver.openInputStream(docUri)?.use { input2 ->
                    val bmp = BitmapFactory.decodeStream(input2, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                    if (bmp != null) {
                        file.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
                        bmp.recycle()
                        Log.d(TAG, "preloadImageCover: OK saved to ${file.name}")
                    } else {
                        Log.w(TAG, "preloadImageCover: decode returned null")
                    }
                }
            } ?: Log.w(TAG, "preloadImageCover: openInputStream returned null")
        } catch (e: Exception) { Log.e(TAG, "preloadImageCover: ${e.message}") }
    }

    private fun getRealFile(uri: Uri): File? {
        try {
            ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (path != null) {
                            val f = File(path)
                            if (f.exists() && f.length() > 0) return f
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun decodeCoverBitmap(data: ByteArray): android.graphics.Bitmap? {
        if (data.isEmpty()) return null
        return try {
            com.mangareader.native.NativeEngine.decodeRaw(data, 1024, 1024)
                ?: run {
                    // ★ C4 修复：显式两遍解码。旧写法在 apply{inJustDecodeBounds=true} 的同一 Options 上
                    //   立即 let 读 outWidth——decodeByteArray 尚未执行，outWidth=0 → inSampleSize 恒 1，
                    //   大封面全尺寸解码（正是 catch(OOM) 高发根因）
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    var s = 1
                    while (bounds.outWidth / s > 1024 || bounds.outHeight / s > 1024) s *= 2
                    BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
                }
        } catch (e: OutOfMemoryError) {
            System.gc()
            try {
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = 4 })
            } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }

    private fun md5(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cacheRealPaths(books: List<Book>) {
        val prefs = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false
        for (b in books) {
            val filePath = b.filePath
            val cached = prefs.getString("realpath_$filePath", null)
            if (cached != null) continue
            try {
                val p = filePath.removePrefix("a:").split("|", limit = 4)
                if (p.size < 3) continue
                val realFile = getRealFileForPath(p[0], p[1], p[2])
                if (realFile != null) {
                    editor.putString("realpath_$filePath", realFile.absolutePath)
                    changed = true
                }
            } catch (_: Exception) {}
        }
        if (changed) editor.apply()
    }

    private fun getRealFileForPath(auth: String, treeDocId: String, docId: String): File? {
        try {
            val treeUri = Uri.parse("content://$auth/tree/${Uri.encode(treeDocId)}")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            ctx.contentResolver.query(docUri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (path != null) {
                            val f = File(path)
                            if (f.exists() && f.length() > 0) return f
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun checkNomedia(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            ctx.contentResolver.query(child, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(ni) ?: continue
                    if (name.equals(".nomedia", ignoreCase = true)) return@withContext true
                }
            }
        } catch (e: Exception) { Log.e(TAG, "checkNomedia: ${e.message}") }
        false
    }

    suspend fun createNomedia(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            DocumentsContract.createDocument(ctx.contentResolver, child, "application/octet-stream", ".nomedia")
            true
        } catch (e: Exception) {
            Log.e(TAG, "createNomedia: ${e.message}")
            false
        }
    }

    suspend fun getLastImportedFolder(): Uri? = withContext(Dispatchers.IO) {
        try {
            val folders = dao.getAllFoldersList()
            folders.maxByOrNull { it.lastScanAt }?.let { Uri.parse(it.treeUri) }
        } catch (e: Exception) {
            Log.e(TAG, "getLastImportedFolder: ${e.message}")
            null
        }
    }

    suspend fun rescan(f: Folder): Int = withContext(Dispatchers.IO) {
        scanMutex.withLock {
        var count = 0
        try {
            val uri = Uri.parse(f.treeUri)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val auth = uri.authority ?: ""
            // ★ C8：前缀 Kotlin 侧拼好（a/z 带尾 '|'，d 不带），LIKE 走 filePath 索引
            val oldCount = dao.countBooksByFolder("a:$auth|$docId|%", "d:$auth|$docId%", "z:a:$auth|$docId|%")
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)

            val books = mutableListOf<Book>()
            scanDirFast(auth, f.treeUri, docId, child, books, skipDedup = true)
            if (books.isNotEmpty()) {
                // ★ C1 修复：删前快照四表+单事务删插回填（原"先删后插"CASCADE 清光阅读进度/标签、书架留孤儿）
                //   SAF 扫描在事务外，纯 DB 操作由 DAO @Transaction 打包防长事务锁库
                dao.rescanFolderReplace(auth, docId, books)
                cacheRealPaths(books)
                // ★ v1.13 扫描优化：先出列表（书已入库即完成），封面转后台 4 路并行补齐
                coverScope.launch { preloadCovers(books) }
                count = books.size - oldCount
            }
            // 扫描为空时不删除旧数据，避免临时故障导致书架清空
            dao.updateFolder(f.copy(lastScanAt = System.currentTimeMillis()))
        } catch (e: Exception) { Log.e(TAG, "rescan: ${e.message}") }
        count
        }
    }

    private suspend fun scanDirFast(auth: String, treeStr: String, treeDocId: String, childUri: Uri, books: MutableList<Book>, skipDedup: Boolean = false, onFound: ((Int) -> Unit)? = null) {
        try {
            // ★ §37 扫描校验压缩包内容开关 —— 默认【关】（宁滥勿缺原则：宁可书架上多放几本非漫画
            //   archive，也绝不漏掉一本真漫画）。关闭即维持现状全收，开启后才多跑 hasZipImages 严格校验、
            //   把无图 zip/cbz 剔出书架。hasZipImages 与 hasZipSubDirs 共用 native 句柄不重复打开文件。
            val verifyZipContent = try {
                ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
                    .getBoolean("scan_verify_zip_content", false)
            } catch (_: Exception) { false }
            val existingPaths = mutableSetOf<String>()
            try {
                // ★ C1 补丁：查重数据源改全量（含隐藏书）——旧 getAllBooksList 只查未隐藏，
                //   隐藏过的文件夹重新导入会绕过查重产生重复
                dao.getAllBooksAnyHidden().forEach { existingPaths.add(it.filePath) }
            } catch (_: Exception) {}

            fun isPathDuplicate(newPath: String): Boolean {
                if (newPath in existingPaths) return true
                if (newPath.startsWith("a:")) {
                    val zPath = "z:${newPath}//"
                    if (zPath in existingPaths) return true
                }
                if (newPath.startsWith("z:")) {
                    val aPath = newPath.removePrefix("z:").removeSuffix("//")
                    if (aPath in existingPaths) return true
                }
                return false
            }

            var found = 0
            // ★ C7：单 engine 复用——hasZipImages/hasZipSubDirs 共用同一 libzip 句柄，
            //   每 zip 只解析一遍中央目录（原每处各自 new engine 重复打开）
            val sharedEngine = com.mangareader.viewer.ArchiveEngine(ctx)
            ctx.contentResolver.query(childUri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val name = c.getString(ni) ?: continue
                    val id = c.getString(ii) ?: continue
                    val mime = if (mi >= 0) c.getString(mi) ?: "" else ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                    if (isDir) {
                        val path = "d:$auth|$treeDocId|$id"
                        if (skipDedup || !isPathDuplicate(path)) {
                            // ★ C2 修复：判空与取封面合并为一次递归（原 :440+:447 同目录跑两遍
                            //   findFirstCoverInFolder，SAF binder 开销翻倍；函数确定性保证两次结果相同）
                            var firstCover: String? = null
                            try { firstCover = findFirstCoverInFolder(auth, treeDocId, id) } catch (_: Exception) {}
                            if (firstCover == null) continue  // 真正空文件夹，跳过
                            books.add(Book(title = name.replace(Regex("[._-]"), " ").trim(), filePath = path, fileType = "dir", coverPath = firstCover))
                            found++; onFound?.invoke(found)
                        }
                    } else if (name.isComic()) {
                        val ext = name.substringAfterLast(".").lowercase()
                        val docId = "$auth|$treeDocId|$id"
                        val path = "a:$docId|$ext"
                        // ★ §37 误判修正：zip/cbz 须内部至少含一张图才算漫画 —— 否则游戏 save zip / 一般 archive
                        //   会被错误收进书架（用户报告"纳*save.zip"非漫画被识别）。受设置开关控制，默认关保扫描速度。
                        //   hasZipImages 与 hasZipSubDirs 共用同一 native 句柄，不重复打开文件。
                        //   验证失败时：
                        //     ① 不收新书
                        //     ② 清掉 DB 里可能已存的旧脏数据。a: 和 z:a:...// 两种 path 形态都查：
                        //        - getBookByPath 拿到 id 后 removeBooksFromAllShelves(listOf(id))
                        //          （bookshelf_items 不带到 Book 的外键，须手动清，孤儿记录是项目老问题此处一起补掉）
                        //        - deleteBookByPath 删 Book（reading_progress / tag_links 由 Book 的 CASCADE 外键自动清）
                        //   先清书架再删 Book——顺序敏感：删了 Book 后 id 就拿不到了。
                        if (verifyZipContent && (ext == "cbz" || ext == "zip")) {
                            try {
                                val hasImg = sharedEngine.hasZipImages(path)
                                if (hasImg) {
                                    // 走正常分支收新书
                                } else {
                                    val zPath = "z:${path}//"
                                    var aCover: String? = null
                                    var zCover: String? = null
                                    try {
                                        val aBook = dao.getBookByPath(path)
                                        if (aBook != null) { dao.removeBooksFromAllShelves(listOf(aBook.id)); aCover = aBook.coverPath }
                                        val zBook = dao.getBookByPath(zPath)
                                        if (zBook != null) { dao.removeBooksFromAllShelves(listOf(zBook.id)); zCover = zBook.coverPath }
                                    } catch (_: Exception) {}
                                    // 清封面 thumb 缓存（coverPath 形如 cv:auth|treeDocId|docId|name）—— MD5 同 Library
                                    for (cp in listOf(aCover, zCover)) {
                                        if (cp.isNullOrEmpty()) continue
                                        try {
                                            val md5 = java.security.MessageDigest.getInstance("MD5").digest(cp.toByteArray()).joinToString("") { "%02x".format(it) }
                                            val f = java.io.File(ctx.filesDir, "covers/$md5.thumb")
                                            if (f.exists()) f.delete()
                                        } catch (_: Exception) {}
                                    }
                                    try { dao.deleteBookByPath(path) } catch (_: Exception) {}
                                    try { dao.deleteBookByPath(zPath) } catch (_: Exception) {}
                                    continue  // 不是漫画，跳过
                                }
                            } catch (_: Exception) {}
                        }
                        if (skipDedup || !isPathDuplicate(path)) {
                            val title = name.substringBeforeLast(".").replace(Regex("[._-]"), " ").trim()
                            val coverPath = "cv:$docId|$name"
                            if ((ext == "cbz" || ext == "zip") && sharedEngine.hasZipSubDirs("a:$docId|$ext")) {
                                books.add(Book(title = title, filePath = "z:a:$docId|$ext//", fileType = ext, coverPath = coverPath))
                            } else {
                                books.add(Book(title = title, filePath = path, fileType = ext, coverPath = coverPath))
                            }
                            found++; onFound?.invoke(found)
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "scanDirFast: ${e.message}") }
    }

    // 递归查找文件夹内第一本漫画（按名称排序），用作封面
    private fun findFirstCoverInFolder(auth: String, treeDocId: String, folderDocId: String, depth: Int = 0): String? {
        if (depth > 3) return null
        val treeUri = Uri.parse("content://$auth/tree/${Uri.encode(treeDocId)}")
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
        Log.d(TAG, "findFirstCover: depth=$depth folder=$folderDocId treeUri=$treeUri")
        val comics = mutableListOf<Pair<String, String>>()
        val subFolders = mutableListOf<String>()
        try {
            ctx.contentResolver.query(childUri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val n = c.getString(ni) ?: continue
                    val id = c.getString(ii) ?: continue
                    val mime = if (mi >= 0) c.getString(mi) ?: "" else ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subFolders.add(id)
                    } else if (n.isComic() || (mime.startsWith("image/") && n.isImg())) {
                        comics.add(n to id)
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "findFirstCover query: ${e.message}") }
        Log.d(TAG, "findFirstCover: comics=${comics.size} subFolders=${subFolders.size}")
        if (comics.isNotEmpty()) {
            val sorted = comics.sortedWith { a, b -> com.mangareader.util.ComicSortUtil.fileNameComparator.compare(a.first, b.first) }
            val result = "cv:$auth|$treeDocId|${sorted[0].second}|${sorted[0].first}"
            Log.d(TAG, "findFirstCover: OK result=$result")
            return result
        }
        for (subId in subFolders) {
            val result = findFirstCoverInFolder(auth, treeDocId, subId, depth + 1)
            if (result != null) return result
        }
        return null
    }

    private fun String.isImg() = lowercase().let {
        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") ||
                it.endsWith(".webp") || it.endsWith(".gif") || it.endsWith(".bmp")
    }

    private fun String.isComic() = lowercase().let {
        it.endsWith(".cbz") || it.endsWith(".zip") || it.endsWith(".pdf") || it.endsWith(".epub") ||
                it.endsWith(".mobi") || it.endsWith(".azw") || it.endsWith(".azw3") ||
                it.endsWith(".cbr") || it.endsWith(".rar") || it.endsWith(".cb7") || it.endsWith(".7z")
    }

    /**
     * ★ 综合方案 D：为 Scanner 预写 progress 行收集父书所有子卷的 chPath。
     *
     *   路径拼法与 Reader.enterReader 内 upsertProgress 走的 chPath 完全一致——
     *   enterReader 用 `navStack.lastOrNull() ?: b.filePath`：
     *     - d: 父书 → 子卷的 chPath 是 "d:auth|tree|doc"（dir=true 子夹被点开进 navStack）
     *     - z: 父书 → 子卷的 chPath 是 "z:archivePath//subDir/"（z: 内层目录的完整 z: 路径）
     *     - a: 单卷书 → 自己一行 chPath（这条 collect 不走——a: 在 Scanner 调用前过滤掉）
     *
     *   这个 helper 与 Reader.syncBookStatusFromProgress 的"递归 engine.children"逻辑同形：
     *     - d: 父书：递归 children 至深度 8，每个 dir=false 子文件产出 chPath（=叶子卷）
     *     - z: 父书：children 1 层即可（listZipDirs 不嵌套），产出每个 dir=true 子卷的 z: 路径
     *
     *   返回值：所有"子卷 chPath" list（去重）。
     */
    private suspend fun collectLeafChPathsForBook(
        engine: com.mangareader.viewer.ArchiveEngine,
        b: Book
    ): List<String> {
        val filePath = b.filePath
        // a: 单卷书不预写——enterReader 翻页时 upsertProgress 自然会建一行 progress
        if (!filePath.startsWith("d:") && !filePath.startsWith("z:")) return emptyList()

        val leaves = mutableSetOf<String>()
        if (filePath.startsWith("z:")) {
            // z: 父书：children 1 层拿所有内层子卷
            try {
                val inner = engine.children(filePath)
                // z: 内层 item 的 subPath 即相对压缩包根的 subDir/ 路径
                // 子卷 chPath（与 enterReader 一致）= "z:archivePath//subPath/"
                val archivePath = filePath.removePrefix("z:").removeSuffix("//")
                for (item in inner) {
                    val childChPath = if (item.dir) "z:${archivePath}//${item.subPath}" else filePath
                    if (childChPath.isNotEmpty()) leaves.add(childChPath)
                }
            } catch (_: Exception) {}
        } else {
            // d: 父书：BFS 递归 children，dir=false 子文件为叶子卷
            suspend fun rec(fp: String, depth: Int) {
                if (depth > 8) return
                val items = try { engine.children(fp) } catch (_: Exception) { emptyList() }
                if (items.isEmpty()) return
                for (item in items) {
                    if (!item.dir) {
                        // a: 叶子卷 chPath 也要一致：a:auth|tree|doc|ext
                        val chPath = "a:${item.auth}|${item.tree}|${item.doc}|${item.name.substringAfterLast(".").lowercase()}"
                        leaves.add(chPath)
                    } else {
                        // dir=true 子夹：递归进去
                        val childFp = "d:${item.auth}|${item.tree}|${item.doc}"
                        rec(childFp, depth + 1)
                    }
                }
            }
            rec(filePath, 0)
        }
        return leaves.toList()
    }
}
