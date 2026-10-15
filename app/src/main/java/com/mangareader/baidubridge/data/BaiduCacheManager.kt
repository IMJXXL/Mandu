package com.mangareader.baidubridge.data

import android.content.Context
import com.mangareader.baidubridge.BaiduConstants
import com.mangareader.baidubridge.api.BaiduFileEntry
import java.io.File

class BaiduCacheManager(context: Context) {

    private val baseDir = File(context.cacheDir, BaiduConstants.CACHE_DIR)
    private val dirsDir = File(baseDir, "dirs")
    private val pagesDir = File(baseDir, "pages")
    private val filesDir = File(baseDir, "files")

    init {
        baseDir.mkdirs()
        dirsDir.mkdirs()
        pagesDir.mkdirs()
        filesDir.mkdirs()
    }

    fun getDirCache(dirPath: String): List<BaiduFileEntry>? {
        val file = File(dirsDir, "${sanitizeKey(dirPath)}.json")
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            parseDirCache(text)
        } catch (_: Exception) {
            null
        }
    }

    fun saveDirCache(dirPath: String, items: List<BaiduFileEntry>) {
        try {
            val file = File(dirsDir, "${sanitizeKey(dirPath)}.json")
            file.writeText(serializeDirCache(items))
            file.setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {}
    }

    fun getArchiveCache(fsId: Long, mtime: Long, ext: String): File? {
        val file = File(filesDir, "${fsId}_${mtime}.${ext}")
        return if (file.exists() && file.length() > 0) file else null
    }

    fun newArchiveCacheFile(fsId: Long, mtime: Long, ext: String): File {
        return File(filesDir, "${fsId}_${mtime}.${ext}")
    }

    fun getTotalSize(): Long {
        return dirSize(baseDir)
    }

    fun clearAll() {
        baseDir.listFiles()?.forEach { deleteRecursive(it) }
        baseDir.mkdirs()
    }

    fun clearFiles() {
        filesDir.listFiles()?.forEach { deleteRecursive(it) }
    }

    fun evictLRU() {
        val limit = BaiduConstants.DEFAULT_CACHE_LIMIT
        var total = getTotalSize()
        if (total <= limit) return

        val allFiles = mutableListOf<File>()
        collectFiles(baseDir, allFiles)
        allFiles.sortBy { it.lastModified() }

        for (file in allFiles) {
            if (total <= limit) break
            val size = file.length()
            file.delete()
            total -= size
        }
    }

    private fun sanitizeKey(key: String): String {
        return key.replace("/", "_").replace("\\", "_").replace(":", "_")
    }

    private fun dirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach {
            if (it.isDirectory) size += dirSize(it)
            else size += it.length()
        }
        return size
    }

    private fun collectFiles(dir: File, out: MutableList<File>) {
        dir.listFiles()?.forEach {
            if (it.isDirectory) collectFiles(it, out)
            else out.add(it)
        }
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }

    private fun serializeDirCache(items: List<BaiduFileEntry>): String {
        val sb = StringBuilder("[")
        items.forEachIndexed { i, item ->
            if (i > 0) sb.append(",")
            sb.append("{\"fsId\":${item.fsId},\"path\":\"${item.path.replace("\"", "\\\"")}\",")
            sb.append("\"name\":\"${item.name.replace("\"", "\\\"")}\",")
            sb.append("\"isDir\":${item.isDir},\"size\":${item.size},\"mtime\":${item.mtime}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun parseDirCache(text: String): List<BaiduFileEntry>? {
        return try {
            val arr = org.json.JSONArray(text)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BaiduFileEntry(
                    fsId = obj.getLong("fsId"),
                    path = obj.getString("path"),
                    name = obj.getString("name"),
                    isDir = obj.getBoolean("isDir"),
                    size = obj.getLong("size"),
                    mtime = obj.getLong("mtime"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}