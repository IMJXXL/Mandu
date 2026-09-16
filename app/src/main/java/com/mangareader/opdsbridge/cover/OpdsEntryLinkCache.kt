package com.mangareader.opdsbridge.cover

import com.mangareader.opdsbridge.OpdsConstants
import com.mangareader.opdsbridge.parser.OpdsEntry
import java.util.LinkedHashMap

object OpdsEntryLinkCache {

    private val cache = object : LinkedHashMap<String, OpdsEntry>(OpdsConstants.COVER_LRU_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OpdsEntry>): Boolean {
            return size > OpdsConstants.COVER_LRU_CAPACITY
        }
    }

    @Synchronized
    fun put(serverId: String, entry: OpdsEntry) {
        cache[key(serverId, entry.id)] = entry
    }

    @Synchronized
    fun get(serverId: String, entryId: String): OpdsEntry? {
        return cache[key(serverId, entryId)]
    }

    @Synchronized
    fun evictServer(serverId: String) {
        val prefix = "$serverId:"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    private fun key(serverId: String, entryId: String): String = "$serverId:$entryId"
}
