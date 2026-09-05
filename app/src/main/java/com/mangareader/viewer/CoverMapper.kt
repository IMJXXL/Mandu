package com.mangareader.viewer

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/** ★ LV119：自定义数据类型，避免 Coil3 内置 String Fetcher 把 cv: 当 URI 解析 */
data class CoverData(val path: String)

class CoverFetcher(
    private val engine: ArchiveEngine,
    private val data: CoverData,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val coverPath = data.path
        if (!coverPath.startsWith("cv:")) throw IllegalArgumentException("Not a cover request: $coverPath")

        // ★ LV119 对齐：先尝试返回原始字节流，让 Coil3 decoders 自动识别动图（GIF/WebP/APNG）
        val raw = withContext(Dispatchers.IO) {
            try { engine.loadCoverRaw(coverPath) } catch (e: Exception) { android.util.Log.w("CoverFetch", "loadCoverRaw err: ${e.message}"); null }
        }
        if (raw != null) {
            val (bytes, mime) = raw
            android.util.Log.i("CoverFetch", "raw OK mime=$mime size=${bytes.size} path=$coverPath")
            return SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(bytes),
                    fileSystem = options.fileSystem,
                ),
                mimeType = mime,
                dataSource = DataSource.MEMORY,
            )
        }
        android.util.Log.w("CoverFetch", "raw null, fallback to Bitmap for $coverPath")

        // fallback：mobi/pdf 等无法返回原始字节的格式，用 Bitmap → PNG
        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            try { engine.loadCover(coverPath) } catch (e: Exception) { null }
        }
        if (bitmap == null) throw RuntimeException("Cover load failed: $coverPath")
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

    class Factory(private val engine: ArchiveEngine) : Fetcher.Factory<CoverData> {
        override fun create(data: CoverData, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.path.startsWith("cv:")) CoverFetcher(engine, data, options) else null
    }
}

object CoverDataKeyer : Keyer<CoverData> {
    override fun key(data: CoverData, options: Options): String = data.path
}
