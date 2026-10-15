package com.mangareader.cloudcommon.folder

object FolderIndexCache {
    private val cache = object : LinkedHashMap<String, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > 32
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = synchronized(cache) { cache[key] as? T }
    fun put(key: String, value: Any) = synchronized(cache) { cache[key] = value }
    fun clear() = synchronized(cache) { cache.clear() }
    fun remove(key: String) = synchronized(cache) { cache.remove(key) }
}