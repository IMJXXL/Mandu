package com.hippo.ehviewer.library.document

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.util.FileUtils
import okio.Path

/**
 * Image-only EPUB: ZIP + OPF spine order (no WebView / text reflow).
 *
 * Page list:
 * 1. OPF cover meta when it points at an image
 * 2. Spine items that are images, plus images referenced from spine XHTML (document order)
 * 3. Fallback: all ZIP image members, natural-sorted
 */
class EpubEngine private constructor(
    private val zip: ZipCentralDirectory,
    private val pages: List<PageRef>,
    private val remoteSize: Long,
) : DocumentImageEngine {
    data class PageRef(
        val zipName: String,
        val ext: String,
        val uncSize: Long,
    )

    override val pageCount: Int get() = pages.size

    fun memberName(index: Int): String? = pages.getOrNull(index)?.zipName

    override fun extOf(index: Int): String? = pages.getOrNull(index)?.ext

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index = DocumentExtractCache.Index(
        v = DocumentExtractCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = "epub",
        complete = complete,
        members = pages.mapIndexed { i, p ->
            DocumentExtractCache.Member(
                i = i,
                name = p.zipName,
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
        val entry = zip.find(page.zipName) ?: return null
        val bytes = zip.extract(entry) ?: return null
        return DocumentExtractCache.writePage(cacheKey, index, ext, bytes)
    }

    fun extractBytes(index: Int): ByteArray? {
        val page = pages.getOrNull(index) ?: return null
        val entry = zip.find(page.zipName) ?: return null
        return zip.extract(entry)
    }

    companion object {

        fun open(
            source: ArchiveByteSource,
            remoteSize: Long = 0L,
            coverOnly: Boolean = false,
        ): EpubEngine? {
            val zip = ZipCentralDirectory.open(source) ?: return null
            val sizeHint = remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(0L)
            val pages = buildPageList(zip, coverOnly)
            return EpubEngine(zip, pages, sizeHint)
        }

        /**
         * Reuse durable member list (skip OPF/spine walk). Still needs ZIP central directory
         * for local-header offsets when extracting uncached pages.
         */
        fun openFromIndex(
            source: ArchiveByteSource,
            index: DocumentExtractCache.Index,
            remoteSize: Long = 0L,
        ): EpubEngine? {
            if (index.members.isEmpty()) return null
            val zip = ZipCentralDirectory.open(source) ?: return null
            val sizeHint = remoteSize.takeIf { it > 0L }
                ?: index.remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(0L)
            val pages = index.members.sortedBy { it.i }.map { m ->
                PageRef(
                    zipName = m.name,
                    ext = m.ext.ifBlank { "bin" },
                    uncSize = m.uncSize,
                )
            }
            logcat("Epub") { "openFromIndex ok pages=${pages.size}" }
            return EpubEngine(zip, pages, sizeHint)
        }

        private fun buildPageList(zip: ZipCentralDirectory, coverOnly: Boolean): List<PageRef> {
            val access = com.mangareader.zip.ZipCentralDirAccess(zip)
            val resolved = runCatching { com.mangareader.zip.EpubPageOrderResolver.resolve(access) }.getOrNull()
            val pages = if (resolved != null && resolved.pageNames.isNotEmpty()) {
                val refs = resolved.pageNames.mapNotNull { name ->
                    val entry = zip.find(name) ?: return@mapNotNull null
                    if (entry.isDirectory || entry.isEncrypted) return@mapNotNull null
                    val ext = FileUtils.getExtensionFromFilename(entry.name)?.lowercase() ?: return@mapNotNull null
                    if (ext !in IMAGE_EXTENSIONS) return@mapNotNull null
                    PageRef(zipName = entry.name, ext = ext, uncSize = entry.uncompressedSize)
                }
                if (coverOnly) refs.take(1) else refs
            } else {
                val fb = fallbackImagePages(zip)
                if (coverOnly) fb.take(1) else fb
            }
            return pages
        }


        private fun fallbackImagePages(zip: ZipCentralDirectory): List<PageRef> = zip.entries
            .filter { !it.isDirectory && !it.isEncrypted }
            .filter { e ->
                val ext = FileUtils.getExtensionFromFilename(e.name)?.lowercase()
                ext != null && ext in IMAGE_EXTENSIONS
            }
            .filter { e ->
                !e.name.contains("__MACOSX") &&
                    !e.name.substringAfterLast('/').startsWith('.')
            }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .map { e ->
                val ext = FileUtils.getExtensionFromFilename(e.name)?.lowercase() ?: "bin"
                PageRef(zipName = e.name, ext = ext, uncSize = e.uncompressedSize)
            }

    }
}
