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
data class CoverData(val path: String, val dynOn: Boolean = false)

class CoverFetcher(
    private val engine: ArchiveEngine,
    private val data: CoverData,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val coverPath = data.path
        // ★ 修复（2026-09-06 实测）：守卫必须放行 z: —— 原写法 `!startsWith("cv:")` 位于 z: 分支之前，
        //   子文件夹封面请求在进入 z: 分支前就被抛 IllegalArgumentException（空白且无日志）。
        if (!coverPath.startsWith("cv:") && !coverPath.startsWith("z:")) throw IllegalArgumentException("Not a cover request: $coverPath")

        if (coverPath.startsWith("cv:") && !coverPath.startsWith("cv:smb:") && !coverPath.startsWith("cv:webdav:") && !coverPath.startsWith("cv:komga:") && !coverPath.startsWith("cv:opds:")) {
            val thumbBytes = withContext(Dispatchers.IO) { engine.loadCoverThumbBytes(coverPath) }
            if (thumbBytes != null) {
                return SourceFetchResult(
                    source = ImageSource(source = Buffer().write(thumbBytes), fileSystem = options.fileSystem),
                    mimeType = "image/jpeg",
                    dataSource = DataSource.DISK,
                )
            }
        }

        if (coverPath.startsWith("cv:opds:")) {
            val raw = withContext(Dispatchers.IO) {
                try {
                    com.mangareader.opdsbridge.cover.OpdsCoverLoader.loadCoverRawOpds(coverPath)
                } catch (e: Exception) {
                    android.util.Log.w("CoverFetch", "opds cover err: ${e.message}")
                    null
                }
            } ?: throw RuntimeException("OPDS cover load failed: $coverPath")
            val (bytes, mime) = raw
            return SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(bytes),
                    fileSystem = options.fileSystem,
                ),
                mimeType = mime,
                dataSource = DataSource.NETWORK,
            )
        }


        // ★ LV119 对齐：先尝试返回原始字节流，让 Coil3 decoders 自动识别动图（GIF/WebP/APNG）
        val streamResult = withContext(Dispatchers.IO) {
            try { engine.getCoverSource(coverPath) } catch (e: Exception) { android.util.Log.w("CoverFetch", "getCoverSource err: ${e.message}"); null }
        }
        if (streamResult != null) {
            val (source, mime) = streamResult
            android.util.Log.i("CoverFetch", "stream OK mime=$mime path=$coverPath")
            return SourceFetchResult(
                source = ImageSource(source = source, fileSystem = options.fileSystem),
                mimeType = mime,
                dataSource = DataSource.DISK,
            )
        }

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
            if (data.path.startsWith("cv:") || data.path.startsWith("z:")) CoverFetcher(engine, data, options) else null
    }
}

object CoverDataKeyer : Keyer<CoverData> {
    override fun key(data: CoverData, options: Options): String = "${data.path}#dyn=${data.dynOn}"
}

class ItemCoverFetcher(
    private val engine: ArchiveEngine,
    private val item: ArchiveEngine.Item,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bitmap = withContext(Dispatchers.IO) {
            try { engine.loadCoverForItem(item) } catch (e: Exception) { null }
        } ?: throw RuntimeException("Item cover load failed: ${item.name}")
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val bytes = baos.toByteArray()
        return SourceFetchResult(
            source = ImageSource(source = Buffer().write(bytes), fileSystem = options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory(private val engine: ArchiveEngine) : Fetcher.Factory<ArchiveEngine.Item> {
        override fun create(data: ArchiveEngine.Item, options: Options, imageLoader: ImageLoader): Fetcher? =
            ItemCoverFetcher(engine, data, options)
    }
}

object ItemCoverKeyer : Keyer<ArchiveEngine.Item> {
    override fun key(data: ArchiveEngine.Item, options: Options): String =
        "itemcover:${data.auth}|${data.tree}|${data.doc}|${data.subPath}"
}
