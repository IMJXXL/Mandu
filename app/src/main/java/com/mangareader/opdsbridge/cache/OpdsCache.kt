package com.mangareader.opdsbridge.cache

import com.mangareader.opdsbridge.OpdsBridges
import com.mangareader.opdsbridge.OpdsConstants
import java.io.File
import java.net.URLEncoder

object OpdsCache {

    private val baseDir: File get() = File(OpdsBridges.context.cacheDir, OpdsConstants.CACHE_DIR_NAME)

    fun cachePathFor(serverId: String, entryId: String, ext: String): File {
        val serverDir = File(baseDir, sanitize(serverId))
        serverDir.mkdirs()
        return File(serverDir, "${sanitize(entryId)}.$ext")
    }

    fun serverDir(serverId: String): File = File(baseDir, sanitize(serverId))

    fun isCachedOnDisk(path: File): Boolean = path.exists() && path.length() > 0

    fun touch(path: File) {
        if (path.exists()) path.setLastModified(System.currentTimeMillis())
    }

    fun evictEntry(serverId: String, entryId: String) {
        val serverDir = serverDir(serverId)
        if (!serverDir.exists()) return
        val prefix = sanitize(entryId) + "."
        serverDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix)) f.delete()
        }
    }

    fun evictServer(serverId: String) {
        val dir = serverDir(serverId)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    fun ensureSpace(requiredBytes: Long): Boolean {
        val stat = android.os.StatFs(baseDir.absolutePath)
        return stat.availableBytes >= requiredBytes
    }

    private fun sanitize(s: String): String {
        return try {
            URLEncoder.encode(s, "UTF-8")
        } catch (_: Throwable) {
            s.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        }
    }
}