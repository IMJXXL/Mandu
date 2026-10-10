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
    private val pages: MutableList<PageRef>,
    private val remoteSize: Long,
) : DocumentImageEngine, ProgressiveDocumentImageEngine {
    data class PageRef(
        val imageIndex: Int,
        val ext: String,
        val uncSize: Long,
    )

    override val pageCount: Int get() = pages.size

    override val structureComplete: Boolean get() = mobi.structureComplete

    override fun ensureListedThrough(index: Int): Int {
        val target = index + 1
        if (target > pages.size || !mobi.structureComplete) {
            val discovered = mobi.probeMore(ByteSourceSeekable(source), target)

            while (pages.size < discovered) {
                val i = pages.size
                pages.add(
                    PageRef(
                        imageIndex = i,
                        ext = mobi.imageExt(i) ?: "bin",
                        uncSize = mobi.imageSize(i) ?: 0L,
                    ),
                )
            }
        }
        return pages.size
    }

    fun memberName(index: Int): String? = pages.getOrNull(index)?.let { "record-${it.imageIndex}" }

    override fun extOf(index: Int): String? = pages.getOrNull(index)?.ext

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index = DocumentExtractCache.Index(
        v = DocumentExtractCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = "mobi",
        complete = complete,
        structureComplete = mobi.structureComplete,
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
         * @param probeLimit null = coverOnly 映射（封面 1 / 首开 3）；-1 = 全量（回退）；
         *   0 = 只读头；> 0 = 探测到该条图片数即停。
         * @return engine or null if not a readable MOBI (encrypted / bad structure / no images)。
         */
        fun open(
            source: ArchiveByteSource,
            remoteSize: Long = 0L,
            coverOnly: Boolean = false,
            probeLimit: Int? = null,
        ): MobiEngine? {
            val sizeHint = remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(0L)
            val limit = probeLimit ?: if (coverOnly) 1 else 3
            val mobi = try {
                MobiFile.open(ByteSourceSeekable(source), probeLimit = limit)
            } catch (e: Exception) {
                MobiFile.lastReject = "open exception: ${e::class.java.simpleName}"
                null
            } ?: return null
            val pages = (0 until mobi.imageCount).map { i ->
                PageRef(
                    imageIndex = i,
                    ext = mobi.imageExt(i) ?: "bin",
                    uncSize = mobi.imageSize(i) ?: 0L,
                )
            }.toMutableList()
            if (pages.isEmpty()) return null
            return MobiEngine(source, mobi, if (coverOnly) pages.take(1).toMutableList() else pages, sizeHint)
        }

        /**
         * Reuse durable page list; still needs the PDB record table (header few KB) for extraction.
         * 轻探测指纹比对：只读头+记录表（零图片探测流量），逐成员 uncSize 校验位置映射，
         * 首条记录 12B 魔数校验扩展名。结构与缓存不一致返回 null，调用方落回 [open]。
         */
        fun openFromIndex(
            source: ArchiveByteSource,
            index: DocumentExtractCache.Index,
            remoteSize: Long = 0L,
        ): MobiEngine? {
            if (index.members.isEmpty()) {
                MobiFile.lastReject = "cached index empty"

                return null
            }
            val seekable = ByteSourceSeekable(source)
            val head = try {
                MobiFile.open(seekable, probeLimit = 0)
            } catch (e: Exception) {
                MobiFile.lastReject = "open exception: ${e::class.java.simpleName}"
                null
            } ?: return null
            val freshSize = runCatching { seekable.size }.getOrDefault(0L)
            if (index.remoteSize > 0L && freshSize > 0L && index.remoteSize != freshSize) {
                MobiFile.lastReject = "cached index mismatch (size ${index.remoteSize} vs $freshSize)"
                return null
            }
            val regionRecords = head.resourceEnd - head.resourceStart
            if (index.members.size > regionRecords ||
                (index.structureComplete && index.members.size != regionRecords)
            ) {
                MobiFile.lastReject =
                    "cached index mismatch (${index.members.size} members vs $regionRecords region recs)"
                return null
            }
            for ((i, m) in index.members.withIndex()) {
                if (head.recordSizeAt(head.resourceStart + i) != m.uncSize) {
                    MobiFile.lastReject = "cached index mismatch (member $i uncSize ${m.uncSize})"
                    return null
                }
            }
            val first = index.members.first()
            if (head.probeRecordExt(seekable, head.resourceStart) != first.ext) {
                MobiFile.lastReject = "cached index mismatch (first ext vs ${first.ext})"
                return null
            }
            head.hydrateFromCache(index.members.map { it.ext }, index.structureComplete)
            val pages = index.members.map { m ->
                PageRef(imageIndex = m.i, ext = m.ext, uncSize = m.uncSize)
            }.toMutableList()
            val size = remoteSize.takeIf { it > 0L }
                ?: freshSize.takeIf { it > 0L }
                ?: index.remoteSize

            return MobiEngine(source, head, pages, size)
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
