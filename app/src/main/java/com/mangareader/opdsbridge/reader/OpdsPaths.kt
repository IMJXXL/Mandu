package com.mangareader.opdsbridge.reader

object OpdsPaths {

    private const val PREFIX = "opds:"

    fun parseBookPath(filePath: String): OpdsBookRef? {
        if (!filePath.startsWith(PREFIX)) return null
        val rest = filePath.removePrefix(PREFIX)
        val idx = rest.indexOf(':')
        if (idx <= 0 || idx >= rest.length - 1) return null
        val serverId = rest.substring(0, idx)
        val entryId = rest.substring(idx + 1)
        if (serverId.isEmpty() || entryId.isEmpty()) return null
        return OpdsBookRef(serverId, entryId)
    }

    fun bookPath(serverId: String, entryId: String): String {
        return "$PREFIX$serverId:$entryId"
    }
}