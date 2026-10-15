package com.mangareader.cloudcommon.folder

object FolderIndexCache {
    private const val TTL_MS = 5L * 60 * 1000

    private data class Entry(val value: Any, val createdAt: Long)

    private val cache = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 32
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = synchronized(cache) {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) {
            cache.remove(key)
            return null
        }
        entry.value as? T
    }
    fun put(key: String, value: Any) = synchronized(cache) { cache[key] = Entry(value, System.currentTimeMillis()) }
    fun clear() = synchronized(cache) { cache.clear() }
    fun remove(key: String) { synchronized(cache) { cache.remove(key) } }
}