package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import com.mangareader.mobi.MobiFile
import com.mangareader.mobi.SeekableSource
import okio.Path

/**
 * 图页型 MOBI/AZW/AZW3 远程流式引擎（2026-10-05 M2）：[ArchiveByteSource] 区间读 +
 * [MobiFile] 记录级解析，与 EpubEngine 同走 DocumentExtractCache 管线。
 *
 * 页列表 = 资源区图片记录（记录序，与本地快路径同口径）；开书 = 头几 KB + 每记录 8B
 * 魔数探测（远程连续探测命中字节源块缓存），翻页 = 单记录一次区间读。DRM/坏结构
 * 返回 null，上层报"无法打开"。
 *
 * 不关闭 [source]：生命周期归 useDocumentExtractPageLoader 的 install(source) 会话。
 */
class MobiEngine private constructor(
    private val source: ArchiveByteSource,
    private val mobi: MobiFile,
    private val pages: List<PageRef>,
    private val remoteSize: Long,
) : DocumentImageEngine {
    data class PageRef(
        val imageIndex: Int,
        val ext: String,
        val uncSize: Long,
    )

    override val pageCount: Int get() = pages.size

    fun memberName(index: Int): String? = pages.getOrNull(index)?.let { "record-${it.imageIndex}" }

    override fun extOf(index: Int): String? = pages.getOrNull(index)?.ext

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index = DocumentExtractCache.Index(
        v = DocumentExtractCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = "mobi",
        complete = complete,
        members = pages.mapIndexed { i, p ->
            DocumentExtractCache.Member(
                i = i,
                name = "record-${p.imageIndex}",
                ext = p.ext,
                uncSize = p.uncSize,
            )
        },
    )

    /** Extract page [index] into [DocumentExtractCache]; returns path or null. */
    override fun extractToCache(cacheKey: String, index: Int): Path? {
        val page = pages.getOrNull(index) ?: return null
        val ext = page.ext
        if (DocumentExtractCache.isPageCached(cacheKey, index, ext)) {
            return DocumentExtractCache.pagePath(cacheKey, index, ext)
        }
        val bytes = try {
            mobi.readImage(ByteSourceSeekable(source), page.imageIndex)
        } catch (_: Exception) {
            null
        } ?: return null
        return DocumentExtractCache.writePage(cacheKey, index, ext, bytes)
    }

    companion object {
        /**
         * @param coverOnly if true, keep only the first image in the list.
         * @return engine or null if not a readable MOBI (encrypted / bad structure / no images)。
         */
        fun open(
            source: ArchiveByteSource,
            remoteSize: Long = 0L,
            coverOnly: Boolean = false,
        ): MobiEngine? {
            val sizeHint = remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(0L)
            val mobi = try {
                MobiFile.open(ByteSourceSeekable(source))
            } catch (_: Exception) {
                null
            } ?: return null
            val pages = (0 until mobi.imageCount).map { i ->
                PageRef(
                    imageIndex = i,
                    ext = mobi.imageExt(i) ?: "bin",
                    uncSize = mobi.imageSize(i) ?: 0L,
                )
            }
            if (pages.isEmpty()) return null
            return MobiEngine(source, mobi, if (coverOnly) pages.take(1) else pages, sizeHint)
        }

        /**
         * Reuse durable page list; still needs the PDB record table (header few KB) for extraction.
         * 结构与缓存不一致（页数/ext 变化）返回 null，调用方落回 [open]。
         */
        fun openFromIndex(
            source: ArchiveByteSource,
            index: DocumentExtractCache.Index,
            remoteSize: Long = 0L,
        ): MobiEngine? {
            if (index.members.isEmpty()) return null
            val fresh = open(source, remoteSize, coverOnly = false) ?: return null
            val matches = fresh.pages.size == index.members.size &&
                fresh.pages.withIndex().all { (i, p) ->
                    index.members.getOrNull(i)?.ext == p.ext
                }
            if (!matches) return null
            return fresh
        }
    }
}

/** [ArchiveByteSource] → [SeekableSource] 直通适配（签名同构）。不拥有源，生命周期归加载会话。 */
private class ByteSourceSeekable(private val src: ArchiveByteSource) : SeekableSource {
    override val size: Long get() = src.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
        src.readAt(offset, buf, off, len)

    override fun close() {}
}
