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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Buffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Blocking cover loads park their calling thread (remote branches runBlocking on
 * network reads inside ArchiveEngine). A flood of concurrent covers on
 * [Dispatchers.IO] parks one worker per request and starves the pool the HTTP
 * engine needs — a small dedicated pool caps the fan-out instead.
 */
private val coverIoDispatcher: CoroutineDispatcher by lazy {
    val counter = AtomicInteger()
    Executors.newFixedThreadPool(2) { r ->
        Thread(r, "cover-io-" + counter.incrementAndGet())
    }.asCoroutineDispatcher()
}

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
        //   2026-09-26 放行 custom:（自定义封面）。
        if (!coverPath.startsWith("cv:") && !coverPath.startsWith("z:") && !coverPath.startsWith("custom:")) throw IllegalArgumentException("Not a cover request: $coverPath")

        // ★ 自定义封面（2026-09-26）：custom:<fileName> → filesDir/covers 私有文件直读。
        //   文件缺失（签名降级清 covers/异常）→ 抛错走 Coil 占位；Library 启动孤儿自愈会把
        //   孤儿 customCoverPath 置回 null，随后的请求即走默认封面链路。
        if (coverPath.startsWith("custom:")) {
            val f = java.io.File(engine.customCoverFile("").parentFile, coverPath.removePrefix("custom:"))
            if (!f.exists()) throw java.io.FileNotFoundException("custom cover missing: $coverPath")
            val bytes = withContext(Dispatchers.IO) { f.readBytes() }
            return SourceFetchResult(
                source = ImageSource(source = Buffer().write(bytes), fileSystem = options.fileSystem),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }

        if (coverPath.startsWith("cv:") && !coverPath.startsWith("cv:smb:") && !coverPath.startsWith("cv:webdav:") && !coverPath.startsWith("cv:komga:") && !coverPath.startsWith("cv:opds:")) {
            // ★ 2026-09-19 封面动图封存：恢复 JPEG 缩略图优先（快、静态、无变形）；
            //   动图原始字节路径保留在下方但不再被缩略图命中挡住时才会走到
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
            val thumbBytes = withContext(Dispatchers.IO) { engine.loadCoverThumbBytes(coverPath) }
            if (thumbBytes != null) {
                return SourceFetchResult(
                    source = ImageSource(source = Buffer().write(thumbBytes), fileSystem = options.fileSystem),
                    mimeType = "image/jpeg",
                    dataSource = DataSource.DISK,
                )
            }
            val raw = withContext(Dispatchers.IO) {
                try {
                    com.mangareader.opdsbridge.cover.OpdsCoverLoader.loadCoverRawOpds(coverPath)
                } catch (e: Exception) {
                    android.util.Log.w("CoverFetch", "opds cover err: ${e.message}")
                    null
                }
            } ?: throw RuntimeException("OPDS cover load failed: $coverPath")
            val (bytes, mime) = raw
            withContext(Dispatchers.IO) { engine.saveCoverThumbBytes(coverPath, bytes) }
            return SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(bytes),
                    fileSystem = options.fileSystem,
                ),
                mimeType = mime,
                dataSource = DataSource.NETWORK,
            )
        }

        if (coverPath.startsWith("cv:smb:") || coverPath.startsWith("cv:webdav:")) {
            val thumbBytes = withContext(Dispatchers.IO) { engine.loadCoverThumbBytes(coverPath) }
            if (thumbBytes != null) {
                return SourceFetchResult(
                    source = ImageSource(source = Buffer().write(thumbBytes), fileSystem = options.fileSystem),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
            val raw = withContext(Dispatchers.IO) {
                try {
                    if (coverPath.startsWith("cv:smb:")) {
                        com.mangareader.smbbridge.SmbCoverLoader.loadCoverRawSmb(coverPath, wantRaw = data.dynOn)
                    } else {
                        com.mangareader.webdavbridge.WebDavCoverLoader.loadCoverRawWebDav(coverPath, wantRaw = data.dynOn)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CoverFetch", "remote cover err: ${e.message}")
                    null
                }
            } ?: throw RuntimeException("Remote cover load failed: $coverPath")
            val (bytes, mime) = raw
            android.util.Log.d("CoverFetch", "remote raw OK mime=$mime size=${bytes.size} path=$coverPath")
            withContext(Dispatchers.IO) { engine.saveCoverThumbBytes(coverPath, bytes) }
            return SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(bytes),
                    fileSystem = options.fileSystem,
                ),
                mimeType = mime,
                dataSource = DataSource.NETWORK,
            )
        }

        if (coverPath.startsWith("cv:komga:")) {
            android.util.Log.d("CoverFetch", "komga cover request: $coverPath")
            val thumbBytes = withContext(Dispatchers.IO) { engine.loadCoverThumbBytes(coverPath) }
            if (thumbBytes != null) {
                return SourceFetchResult(
                    source = ImageSource(source = Buffer().write(thumbBytes), fileSystem = options.fileSystem),
                    mimeType = "image/jpeg",
                    dataSource = DataSource.DISK,
                )
            }
            val raw = withContext(Dispatchers.IO) {
                try {
                    val body = coverPath.removePrefix("cv:komga:")
                    val serverId = body.substringBefore(':', "")
                    val bookId = body.substringAfter(':', "")
                    if (serverId.isEmpty() || bookId.isEmpty()) {
                        android.util.Log.w("CoverFetch", "komga cover parse failed: serverId=$serverId bookId=$bookId")
                        return@withContext null
                    }
                    val store = com.mangareader.komgabridge.data.KomgaConnectionStore(com.mangareader.komgabridge.KomgaBridges.context)
                    val conn = store.getConnection(serverId)
                    if (conn == null) {
                        android.util.Log.w("CoverFetch", "komga connection not found: $serverId")
                        return@withContext null
                    }
                    val client = com.mangareader.komgabridge.api.KomgaHttpClient.client(conn.allowSelfSignedCert)
                    val api = com.mangareader.komgabridge.api.KomgaApiClient(conn, client)
                    val thumbUrl = api.bookThumbnailUrl(bookId)
                    android.util.Log.d("CoverFetch", "komga thumb url: $thumbUrl")
                    val bytes = api.downloadBytes(thumbUrl)
                    client.close()
                    val hex = bytes.takeIf { it.isNotEmpty() }?.let { "%02X%02X".format(it[0], it[1]) } ?: "empty"
                    android.util.Log.d("CoverFetch", "komga cover OK size=${bytes.size} hex=$hex path=$coverPath")
                    Pair(bytes, "image/jpeg")
                } catch (e: Exception) {
                    android.util.Log.w("CoverFetch", "komga cover err: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            } ?: throw RuntimeException("Komga cover load failed: $coverPath")
            val (bytes, mime) = raw
            withContext(Dispatchers.IO) { engine.saveCoverThumbBytes(coverPath, bytes) }
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
            android.util.Log.d("CoverFetch", "stream OK mime=$mime path=$coverPath")
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
            android.util.Log.d("CoverFetch", "raw OK mime=$mime size=${bytes.size} path=$coverPath")
            return SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(bytes),
                    fileSystem = options.fileSystem,
                ),
                mimeType = mime,
                dataSource = DataSource.NETWORK,
            )
        }
        android.util.Log.w("CoverFetch", "raw null, fallback to Bitmap for $coverPath")

        // fallback：mobi/pdf 等无法返回原始字节的格式，用 Bitmap → PNG
        val bitmap: Bitmap? = withContext(coverIoDispatcher) {
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
            // ★ custom: 必须在此放行——fetch() 里的 custom: 分支只有工厂返回 Fetcher 才会被执行，
            //   漏放行时 Coil 找不到 Fetcher 直接加载失败（更换封面后显示空白，2026-09-26 实测）。
            if (data.path.startsWith("cv:") || data.path.startsWith("z:") || data.path.startsWith("custom:")) CoverFetcher(engine, data, options) else null
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
        // ★ 2026-09-19 封面动图封存：子项封面恢复静态位图路径
        //   （loadItemCoverAnimatedRaw 保留未调用，需要动图子项封面时恢复此段）
        val bitmap = withContext(coverIoDispatcher) {
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
