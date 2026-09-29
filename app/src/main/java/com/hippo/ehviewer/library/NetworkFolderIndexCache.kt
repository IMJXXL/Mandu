package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

object NetworkFolderIndexCache {
    private val lock = Mutex()
    private val memory = HashMap<String, MemoryIndex>()
    private val cacheDir: File
        get() = File(appCtx.noBackupFilesDir, "network_folder_index")
    private val legacyCacheDir: File
        get() = File(appCtx.cacheDir, "network_folder_index")

    private class MemoryIndex(
        val disk: FolderIndexDisk,
        var configKey: String,
        val decoded: HashMap<String, List<BrowseEntryRemote>> = HashMap(),
    ) {
        fun dropDecodedUnder(prefix: String) {
            if (prefix.isEmpty()) return
            val stale = decoded.keys.filter { it == prefix || it.startsWith("$prefix/") }
            stale.forEach { decoded.remove(it) }
        }
    }

    suspend fun loadSmb(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = load("smb", sourceId, configKey, relativeDir)

    suspend fun saveSmb(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String> = emptySet(),
    ): List<BrowseEntryRemote> = save("smb", sourceId, configKey, relativeDir, entries, removedChildDirs)

    suspend fun saveSmbAll(
        sourceId: Long,
        configKey: String,
        folders: Map<String, List<BrowseEntryRemote>>,
    ): Map<String, List<BrowseEntryRemote>> = saveAll("smb", sourceId, configKey, folders)

    suspend fun loadWebDav(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = load("webdav", sourceId, configKey, relativeDir)

    suspend fun saveWebDav(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String> = emptySet(),
    ): List<BrowseEntryRemote> = save("webdav", sourceId, configKey, relativeDir, entries, removedChildDirs)

    suspend fun saveWebDavAll(
        sourceId: Long,
        configKey: String,
        folders: Map<String, List<BrowseEntryRemote>>,
    ): Map<String, List<BrowseEntryRemote>> = saveAll("webdav", sourceId, configKey, folders)

    suspend fun loadLocal(
        rootId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = load("local", rootId, configKey, relativeDir)

    suspend fun saveLocal(
        rootId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String> = emptySet(),
    ): List<BrowseEntryRemote> = save("local", rootId, configKey, relativeDir, entries, removedChildDirs)

    suspend fun loadLocalFolders(
        rootId: Long,
        configKey: String,
    ): Map<String, List<BrowseEntryRemote>> = loadAllFolders("local", rootId, configKey)

    suspend fun saveLocalAll(
        rootId: Long,
        configKey: String,
        folders: Map<String, List<BrowseEntryRemote>>,
    ): Map<String, List<BrowseEntryRemote>> = saveAll("local", rootId, configKey, folders)

    suspend fun deleteSmb(sourceId: Long) = delete("smb", sourceId)

    suspend fun deleteWebDav(sourceId: Long) = delete("webdav", sourceId)

    suspend fun deleteLocal(rootId: Long) = delete("local", rootId)

    suspend fun removeSmbUnder(sourceId: Long, relativeDir: String) = removeUnder("smb", sourceId, relativeDir)

    suspend fun removeWebDavUnder(sourceId: Long, relativeDir: String) = removeUnder("webdav", sourceId, relativeDir)

    suspend fun removeLocalUnder(rootId: Long, relativeDir: String) = removeUnder("local", rootId, relativeDir)

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            memory.clear()
            deleteDirContents(cacheDir)
            deleteDirContents(legacyCacheDir)
        }
        BrowseSession.invalidateLocalListing()
        BrowseSession.invalidateAllSmbListings()
        BrowseSession.invalidateAllWebDavListings()
    }

    private suspend fun load(
        protocol: String,
        sourceId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = withContext(Dispatchers.IO) {
        if (!Settings.networkFolderIndexCache.value) return@withContext null
        lock.withLock {
            val idx = existingMemory(protocol, sourceId, configKey) ?: return@withLock null
            val key = FolderIndexDisk.normalizeDir(relativeDir)
            idx.decoded[key]?.let { return@withLock it }
            val decoded = idx.disk.readListing(key) ?: return@withLock null
            idx.decoded[key] = decoded
            decoded
        }
    }

    private suspend fun loadAllFolders(
        protocol: String,
        sourceId: Long,
        configKey: String,
    ): Map<String, List<BrowseEntryRemote>> = withContext(Dispatchers.IO) {
        if (!Settings.networkFolderIndexCache.value) return@withContext emptyMap()
        lock.withLock {
            val idx = existingMemory(protocol, sourceId, configKey) ?: return@withLock emptyMap()
            val disk = idx.disk.loadAllListings()
            idx.decoded.putAll(disk)
            disk
        }
    }

    private suspend fun save(
        protocol: String,
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String>,
    ): List<BrowseEntryRemote> = withContext(Dispatchers.IO) {
        if (!Settings.networkFolderIndexCache.value) return@withContext entries
        lock.withLock {
            val idx = writableMemory(protocol, sourceId, configKey)
            persistFolder(
                idx,
                relativeDir,
                entries,
                removedChildDirs,
                Settings.browseZipAsDir.value,
                logKeep = "$protocol/$sourceId",
            )
        }
    }

    private suspend fun saveAll(
        protocol: String,
        sourceId: Long,
        configKey: String,
        updates: Map<String, List<BrowseEntryRemote>>,
    ): Map<String, List<BrowseEntryRemote>> = withContext(Dispatchers.IO) {
        if (updates.isEmpty()) return@withContext emptyMap()
        if (!Settings.networkFolderIndexCache.value) return@withContext updates
        lock.withLock {
            val idx = writableMemory(protocol, sourceId, configKey)
            val zipAsDir = Settings.browseZipAsDir.value
            val stored = LinkedHashMap<String, List<BrowseEntryRemote>>(updates.size)
            for ((relativeDir, entries) in updates) {
                stored[FolderIndexDisk.normalizeDir(relativeDir)] = persistFolder(
                    idx,
                    relativeDir,
                    entries,
                    removedChildDirs = emptySet(),
                    zipAsDir = zipAsDir,
                    logKeep = "$protocol/$sourceId",
                )
            }
            stored
        }
    }

    private fun memoryKey(protocol: String, sourceId: Long) = "$protocol:$sourceId"

    private fun dropMemory(protocol: String, sourceId: Long) {
        memory.remove(memoryKey(protocol, sourceId))
    }

    private fun logConfigKeyMismatch(
        protocol: String,
        sourceId: Long,
        storedKey: String,
        configKey: String,
    ) {
        if (storedKey.isNotEmpty() && storedKey != configKey) {
            logcat("FolderIndex") {
                "Keeping $protocol/$sourceId index after source edit (configKey stamp differs)"
            }
        }
    }

    private fun existingMemory(
        protocol: String,
        sourceId: Long,
        configKey: String,
    ): MemoryIndex? {
        val key = memoryKey(protocol, sourceId)
        memory[key]?.let { return it }
        val disk = openDisk(protocol, sourceId, create = false) ?: return null
        val meta = disk.readMeta() ?: return null
        logConfigKeyMismatch(protocol, sourceId, meta.configKey, configKey)
        val idx = MemoryIndex(disk, meta.configKey)
        memory[key] = idx
        return idx
    }

    private fun writableMemory(
        protocol: String,
        sourceId: Long,
        configKey: String,
    ): MemoryIndex {
        val key = memoryKey(protocol, sourceId)
        memory[key]?.let { idx ->
            if (idx.configKey != configKey) {
                idx.disk.writeMeta(configKey)
                idx.configKey = configKey
            }
            return idx
        }
        val disk = openDisk(protocol, sourceId, create = true)!!
        val meta = disk.readMeta()
        logConfigKeyMismatch(protocol, sourceId, meta?.configKey.orEmpty(), configKey)
        val blobPending = meta == null && legacyBlobFile(protocol, sourceId) != null
        if (!blobPending && (meta == null || meta.configKey != configKey)) {
            disk.writeMeta(configKey)
        }
        val idx = MemoryIndex(disk, configKey)
        memory[key] = idx
        return idx
    }

    private data class FolderMerge(
        val stored: List<BrowseEntryRemote>,
        val keepPrevious: Boolean,
        val unchanged: Boolean,
    )

    private fun persistFolder(
        idx: MemoryIndex,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String>,
        zipAsDir: Boolean,
        logKeep: String,
    ): List<BrowseEntryRemote> {
        val key = FolderIndexDisk.normalizeDir(relativeDir)
        val previous = idx.decoded[key] ?: idx.disk.readListing(key)
        if (previous != null) idx.decoded[key] = previous
        val merge = mergeFolderEntry(previous, entries, zipAsDir, logKeep, key)
        if (!merge.keepPrevious && removedChildDirs.isNotEmpty()) {
            val parent = key
            for (child in removedChildDirs) {
                val prefix = listOf(parent, FolderIndexDisk.normalizeDir(child))
                    .filter { it.isNotEmpty() }
                    .joinToString("/")
                if (prefix.isEmpty()) continue
                idx.disk.removeUnder(prefix)
                idx.dropDecodedUnder(prefix)
            }
        }
        if (!merge.unchanged) {
            // 磁盘写失败也保留 RAM 条目：刚合并出的大目录不因一次写失败被丢弃（磁盘留在旧文件，
            // 下次保存重写）；原来这里 remove 会导致写失败即丢清单、触发整源重扫
            if (!idx.disk.writeListing(key, merge.stored)) {
                logcat("FolderIndex") { "Write listing failed dir=$key (kept in memory)" }
            }
        }
        idx.decoded[key] = merge.stored
        return merge.stored
    }

    private fun mergeFolderEntry(
        previous: List<BrowseEntryRemote>?,
        entries: List<BrowseEntryRemote>,
        zipAsDir: Boolean,
        logKeep: String,
        key: String,
    ): FolderMerge {
        val keepPrevious = previous != null &&
            shouldKeepPreviousFolderIndex(previous, entries, zipAsDir)
        val toStore = if (keepPrevious) {
            logcat("FolderIndex") {
                "Keeping $logKeep dir=$key index " +
                    "(new listing empty/shallow or dropped every folder)"
            }
            checkNotNull(previous)
        } else if (previous != null) {
            preferCompleteFolderGalleries(previous, entries)
        } else {
            entries
        }
        val unchanged = previous != null && toStore == previous
        return FolderMerge(toStore, keepPrevious, unchanged)
    }

    private suspend fun removeUnder(
        protocol: String,
        sourceId: Long,
        relativeDir: String,
    ) = withContext(Dispatchers.IO) {
        val prefix = FolderIndexDisk.normalizeDir(relativeDir)
        if (prefix.isEmpty()) return@withContext
        if (!Settings.networkFolderIndexCache.value) return@withContext
        lock.withLock {
            val idx = existingMemory(protocol, sourceId, configKey = "") ?: return@withLock
            idx.disk.removeUnder(prefix)
            idx.dropDecodedUnder(prefix)
        }
    }

    private suspend fun delete(protocol: String, sourceId: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            dropMemory(protocol, sourceId)
            FolderIndexDisk(sourceDir(protocol, sourceId)).deleteSource()
            deleteLegacyBlobs(protocol, sourceId)
        }
    }

    private fun openDisk(protocol: String, sourceId: Long, create: Boolean): FolderIndexDisk? {
        cacheDir.mkdirs()
        val dir = sourceDir(protocol, sourceId)
        val disk = FolderIndexDisk(dir)
        val meta = disk.readMeta()
        if (meta != null) {
            deleteLegacyBlobs(protocol, sourceId)
            return disk
        }
        val blob = legacyBlobFile(protocol, sourceId)
        if (blob != null) {
            if (FolderIndexDisk.parseV5Blob(blob) == null) {
                deleteLegacyBlobs(protocol, sourceId)
            } else if (FolderIndexDisk.migrateFromV5Blob(blob, dir) && disk.readMeta() != null) {
                deleteLegacyBlobs(protocol, sourceId)
                return disk
            } else if (!create) {
                return null
            } else {
                return disk
            }
        }
        if (!create) return null
        dir.mkdirs()
        return disk
    }

    private fun sourceDir(protocol: String, sourceId: Long) = File(cacheDir, FolderIndexDisk.sourceDirName(protocol, sourceId))

    private fun legacyBlobFile(protocol: String, sourceId: Long): File? {
        val name = FolderIndexDisk.legacyBlobName(protocol, sourceId)
        val current = File(cacheDir, name)
        if (current.isFile && current.length() > 0L) return current
        val legacy = File(legacyCacheDir, name)
        if (legacy.isFile && legacy.length() > 0L) return legacy
        return null
    }

    private fun deleteLegacyBlobs(protocol: String, sourceId: Long) {
        val name = FolderIndexDisk.legacyBlobName(protocol, sourceId)
        File(cacheDir, name).delete()
        File(legacyCacheDir, name).delete()
        deleteTmpFiles(cacheDir, name)
        deleteTmpFiles(legacyCacheDir, name)
    }

    private fun deleteDirContents(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
    }

    private fun deleteTmpFiles(dir: File, jsonName: String) {
        if (!dir.isDirectory) return
        val prefix = "$jsonName.tmp."
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix)) f.delete()
        }
    }
}
