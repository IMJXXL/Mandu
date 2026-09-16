package com.mangareader.opdsbridge.cover

import android.util.Log
import com.mangareader.opdsbridge.OpdsBridges
import com.mangareader.opdsbridge.OpdsConstants
import com.mangareader.opdsbridge.api.OpdsApiClient
import com.mangareader.opdsbridge.api.OpdsHttpClient
import com.mangareader.opdsbridge.data.OpdsConnectionStore

object OpdsCoverLoader {

    suspend fun loadCoverRawOpds(coverPath: String): Pair<ByteArray, String>? {
        if (!coverPath.startsWith(OpdsConstants.COVER_SCHEME)) return null
        val rest = coverPath.removePrefix(OpdsConstants.COVER_SCHEME)
        val idx = rest.indexOf(':')
        if (idx <= 0 || idx >= rest.length - 1) return null
        val serverId = rest.substring(0, idx)
        val entryId = rest.substring(idx + 1)

        val conn = OpdsConnectionStore(OpdsBridges.context).getConnection(serverId) ?: return null
        val entry = OpdsEntryLinkCache.get(serverId, entryId) ?: return null

        val coverUrl = entry.thumbnailLink ?: entry.imageLink ?: return null

        return try {
            val client = OpdsHttpClient.client(conn.allowSelfSignedCert)
            val apiClient = OpdsApiClient(conn, client)
            val bytes = apiClient.downloadBytes(coverUrl)
            client.close()
            if (bytes.isEmpty()) null else Pair(bytes, "image/jpeg")
        } catch (t: Throwable) {
            Log.w(OpdsConstants.LOG_TAG, "Cover load failed for $serverId:$entryId", t)
            null
        }
    }
}
