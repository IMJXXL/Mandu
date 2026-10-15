package com.mangareader.quarkbridge.data

import android.content.Context
import com.mangareader.quarkbridge.QuarkConstants
import com.mangareader.quarkbridge.api.QuarkDirResult
import com.mangareader.quarkbridge.api.QuarkFileEntry
import java.io.File

class QuarkCacheManager(context: Context) {

    private val baseDir = File(context.cacheDir, QuarkConstants.CACHE_DIR)
    private val dirsDir = File(baseDir, "dirs")
    private val pagesDir = File(baseDir, "pages")
    private val filesDir = File(baseDir, "files")

    init {
        baseDir.mkdirs()
        dirsDir.mkdirs()
        pagesDir.mkdirs()
        filesDir.mkdirs()
    }

    fun getDirCache(pdirFid: String): List<QuarkFileEntry>? {
        val file = File(dirsDir, "${pdirFid}.json")
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            parseDirCache(text)
        } catch (_: Exception) {
            null
        }
    }

    fun saveDirCache(pdirFid: String, items: List<QuarkFileEntry>) {
        try {
            val file = File(dirsDir, "${pdirFid}.json")
            file.writeText(serializeDirCache(items))
            file.setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {}
    }

    fun getArchiveCache(fid: String, mtime: Long, ext: String): File? {
        val file = File(filesDir, "${fid}_${mtime}.${ext}")
        return if (file.exists() && file.length() > 0) file else null
    }

    fun newArchiveCacheFile(fid: String, mtime: Long, ext: String): File {
        return File(filesDir, "${fid}_${mtime}.${ext}")
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
        val limit = QuarkConstants.DEFAULT_CACHE_LIMIT
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

    private fun serializeDirCache(items: List<QuarkFileEntry>): String {
        val sb = StringBuilder("[")
        items.forEachIndexed { i, item ->
            if (i > 0) sb.append(",")
            sb.append("{\"fid\":\"${item.fid}\",\"name\":\"${item.name.replace("\"", "\\\"")}\",")
            sb.append("\"isDir\":${item.isDir},\"size\":${item.size},")
            sb.append("\"updatedAt\":${item.updatedAt},\"pdirFid\":\"${item.pdirFid}\",\"fileType\":${item.fileType}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun parseDirCache(text: String): List<QuarkFileEntry>? {
        return try {
            val arr = org.json.JSONArray(text)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                QuarkFileEntry(
                    fid = obj.getString("fid"),
                    name = obj.getString("name"),
                    isDir = obj.getBoolean("isDir"),
                    size = obj.getLong("size"),
                    updatedAt = obj.getLong("updatedAt"),
                    pdirFid = obj.getString("pdirFid"),
                    fileType = obj.getInt("fileType"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}