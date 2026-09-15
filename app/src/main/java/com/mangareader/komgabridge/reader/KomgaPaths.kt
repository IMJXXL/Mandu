package com.mangareader.komgabridge.reader

import com.mangareader.komgabridge.KomgaConstants

data class KomgaBookRef(
    val serverId: String,
    val bookId: String,
)

object KomgaPaths {
    fun parseBookPath(filePath: String): KomgaBookRef? {
        if (!filePath.startsWith(KomgaConstants.SCHEME)) return null
        val body = filePath.removePrefix(KomgaConstants.SCHEME)
        val serverId = body.substringBefore(':', "").trim()
        val bookId = body.substringAfter(':', "").trim()
        if (serverId.isEmpty() || bookId.isEmpty()) return null
        return KomgaBookRef(serverId, bookId)
    }

    fun bookPath(serverId: String, bookId: String): String =
        "${KomgaConstants.SCHEME}$serverId:$bookId"
}