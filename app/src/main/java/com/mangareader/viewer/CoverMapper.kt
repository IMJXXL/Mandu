package com.mangareader.viewer

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

class CoverFetcher(
    private val engine: ArchiveEngine,
    private val data: String,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        if (!data.startsWith("cv:")) throw IllegalArgumentException("Not a cover request: $data")
        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            try { engine.loadCover(data) } catch (e: Exception) { null }
        }
        if (bitmap == null) throw RuntimeException("Cover load failed: $data")
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val bytes = baos.toByteArray()
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                fileSystem = options.fileSystem,
            ),
            mimeType = "image/png",
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory(private val engine: ArchiveEngine) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.startsWith("cv:")) CoverFetcher(engine, data, options) else null
    }
}
