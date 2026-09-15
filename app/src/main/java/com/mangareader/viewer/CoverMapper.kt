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

        // ★ 动态封面开关（阅读设置）：关闭时由请求级 StaticFirstFrameDecoder 解首帧，
        //   开启时走 Coil 全局动图解码器播放动画（对齐参考项目 coverThumb 管线）

        // ★ ItemCard 动图封面（文件夹子卷）：z: 路径走 loadItemCoverRaw（首页原始字节）
        if (coverPath.startsWith("z:")) {
            val raw = withContext(Dispatchers.IO) {
                try {
                    val r = engine.loadItemCoverRaw(coverPath)
                    android.util.Log.i("CoverFetch", "item cover raw=${r?.first?.size ?: -1}B path=$coverPath")
                    r
                } catch (e: Exception) {
                    android.util.Log.w("CoverFetch", "item cover err: ${e.message}"); null
                }
            } ?: throw RuntimeException("Item cover load failed: $coverPath")
            val (bytes, mime) = raw
            return SourceFetchResult(
                source = ImageSource(source = Buffer().write(bytes), fileSystem = options.fileSystem),
                mimeType = mime,
                dataSource = DataSource.MEMORY,
            )
        }

        // ★ SMB/WebDAV 封面分流（任务5.2/15.2）：远程封面委托对应 CoverLoader，
        //   `cv:` 本地前缀逻辑零改动（仅新增分支；远程路径被 ArchiveEngine 守卫拦截，严禁落入下方 engine 调用）
        //   dynOn=true（动态封面开）→ wantRaw：原始字节路径，GIF/WebP 动图封面可播放
        if (coverPath.startsWith("cv:smb:") || coverPath.startsWith("cv:webdav:")) {
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
            android.util.Log.i("CoverFetch", "remote raw OK mime=$mime size=${bytes.size} path=$coverPath")
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
            android.util.Log.i("CoverFetch", "komga cover request: $coverPath")
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
                    android.util.Log.i("CoverFetch", "komga thumb url: $thumbUrl")
                    val bytes = api.downloadBytes(thumbUrl)
                    client.close()
                    val hex = bytes.takeIf { it.isNotEmpty() }?.let { "%02X%02X".format(it[0], it[1]) } ?: "empty"
                    android.util.Log.i("CoverFetch", "komga cover OK size=${bytes.size} hex=$hex path=$coverPath")
                    Pair(bytes, "image/jpeg")
                } catch (e: Exception) {
                    android.util.Log.w("CoverFetch", "komga cover err: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            } ?: throw RuntimeException("Komga cover load failed: $coverPath")
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
