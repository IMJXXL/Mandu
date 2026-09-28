package com.mangareader.metadata

import android.content.Context
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.withTimeoutOrNull

object ComicInfoReader {

    private const val TIMEOUT_MS = 5000L

    suspend fun read(ctx: Context, bookPath: String): ComicInfo? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            try {
                val engine = ArchiveEngine(ctx)
                val xmlBytes = engine.readComicInfoXml(bookPath)
                if (xmlBytes == null) null else ComicInfoParser.parse(xmlBytes)
            } catch (_: Exception) {
                null
            }
        }
    }
}
