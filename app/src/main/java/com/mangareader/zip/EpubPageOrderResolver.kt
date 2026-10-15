package com.mangareader.zip

import android.util.Log
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.naturalCompare
import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

object EpubPageOrderResolver {
    private const val TAG = "EpubPageOrder"

    interface ZipAccess {
        fun listEntryNames(): List<String>
        fun readEntry(name: String): ByteArray?
    }

    data class Result(
        val pageNames: List<String>,
        val fromSpine: Boolean,
    )

    fun resolve(access: ZipAccess): Result? {
        val allNames = access.listEntryNames()
        if (allNames.isEmpty()) return null

        val nameSet = HashSet(allNames)
        val spineOrder = trySpineOrder(access, nameSet)
        if (spineOrder.isNotEmpty()) {
            Log.d(TAG, "resolve: spine order, ${spineOrder.size} pages, first5=${spineOrder.take(5).map { it.substringAfterLast("/") }}")
            return Result(spineOrder, true)
        }

        val fallback = allNames
            .filter { isImageName(it) }
            .filter { !it.contains("__MACOSX") && !it.substringAfterLast('/').startsWith('.') }
            .sortedWith { a, b -> naturalCompare(a, b) }

        if (fallback.isEmpty()) return null
        Log.d(TAG, "resolve: fallback sort, ${fallback.size} pages")
        return Result(fallback, false)
    }

    private fun trySpineOrder(access: ZipAccess, nameSet: Set<String>): List<String> {
        val containerData = access.readEntry("META-INF/container.xml") ?: return emptyList()
        val containerDoc = runCatching {
            Jsoup.parse(containerData.inputStream(), "UTF-8", "", Parser.xmlParser())
        }.getOrNull() ?: return emptyList()

        val opfPath = containerDoc.select("rootfile").firstOrNull()
            ?.attr("full-path")
            ?.replace('\\', '/')
            ?.trimStart('/')
            ?: return emptyList()

        val opfData = access.readEntry(opfPath) ?: return emptyList()
        val opfDoc = runCatching {
            Jsoup.parse(opfData.inputStream(), "UTF-8", "", Parser.xmlParser())
        }.getOrNull() ?: return emptyList()

        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = LinkedHashMap<String, ManifestItem>()
        for (item in opfDoc.select("manifest > item")) {
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isEmpty() || href.isEmpty()) continue
            manifest[id] = ManifestItem(
                id = id,
                href = resolveZipPath(opfDir, decodeHref(href)),
                mediaType = item.attr("media-type").lowercase(),
                properties = item.attr("properties").lowercase(),
            )
        }
        if (manifest.isEmpty()) return emptyList()

        val spineIds = opfDoc.select("spine > itemref").mapNotNull { el ->
            el.attr("idref").takeIf { it.isNotEmpty() }
        }
        if (spineIds.isEmpty()) return emptyList()

        val ordered = LinkedHashSet<String>()

        for (idref in spineIds) {
            val item = manifest[idref] ?: continue
            when {
                isImageMediaType(item.mediaType) || isImageName(item.href) -> {
                    if (item.href in nameSet) ordered.add(item.href)
                }
                isHtmlMediaType(item.mediaType) || isHtmlName(item.href) -> {
                    val xhtmlData = access.readEntry(item.href) ?: continue
                    val xhtmlDoc = runCatching {
                        Jsoup.parse(xhtmlData.inputStream(), "UTF-8", item.href, Parser.xmlParser())
                    }.getOrNull() ?: continue
                    val baseDir = item.href.substringBeforeLast('/', "")

                    for (img in xhtmlDoc.select("img")) {
                        val src = img.attr("src")
                        if (src.isNotEmpty()) addImageRef(src, baseDir, nameSet, ordered)
                    }
                    for (img in xhtmlDoc.select("image")) {
                        val ref = img.attr("xlink:href").ifEmpty { img.attr("href") }
                        if (ref.isNotEmpty()) addImageRef(ref, baseDir, nameSet, ordered)
                    }
                }
            }
        }

        if (ordered.isEmpty()) {
            manifest.values
                .filter { isImageMediaType(it.mediaType) || isImageName(it.href) }
                .filter { it.href in nameSet }
                .sortedWith { a, b -> naturalCompare(a.href, b.href) }
                .forEach { ordered.add(it.href) }
        }

        return ordered.toList()
    }

    private fun addImageRef(rawRef: String, baseDir: String, nameSet: Set<String>, ordered: MutableSet<String>) {
        val ref = rawRef.trim()
        if (ref.isEmpty()) return
        if (ref.startsWith("data:", ignoreCase = true)) return
        if (ref.startsWith("http://", ignoreCase = true) || ref.startsWith("https://", ignoreCase = true)) return

        val cleanRef = ref.substringBefore('#').substringBefore('?')
        val resolved = resolveZipPath(baseDir, decodeHref(cleanRef))
        if (resolved in nameSet) ordered.add(resolved)
    }

    private fun decodeHref(href: String): String =
        try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }

    private fun resolveZipPath(baseDir: String, href: String): String {
        var h = href.replace('\\', '/').trim()
        if (h.startsWith("/")) return h.trimStart('/')
        val base = baseDir.trim('/')
        val parts = ArrayList<String>()
        if (base.isNotEmpty()) parts += base.split('/').filter { it.isNotEmpty() }
        for (seg in h.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun isImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    private fun isImageMediaType(mt: String): Boolean = mt.startsWith("image/")

    private fun isHtmlMediaType(mt: String): Boolean = mt.contains("html") || mt.contains("xml")

    private fun isHtmlName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("xhtml", "html", "htm", "xml")
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )
}
class NativeZipAccess(private val cacheKey: String) : EpubPageOrderResolver.ZipAccess {
    private val names: List<String> =
        com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey)?.toList() ?: emptyList()
    private val nameToIdx: Map<String, Int> = names.withIndex().associate { it.value to it.index }

    override fun listEntryNames(): List<String> = names
    override fun readEntry(name: String): ByteArray? {
        val idx = nameToIdx[name] ?: return null
        return com.mangareader.native.NativeEngine.readZipEntry(cacheKey, idx)
    }
}

class ZipCentralDirAccess(private val zip: com.hippo.ehviewer.library.ZipCentralDirectory) : EpubPageOrderResolver.ZipAccess {
    override fun listEntryNames(): List<String> = zip.entries.map { it.name }
    override fun readEntry(name: String): ByteArray? {
        val entry = zip.find(name) ?: return null
        return zip.extract(entry)
    }
}

class JavaZipFileAccess(private val zipFile: java.util.zip.ZipFile) : EpubPageOrderResolver.ZipAccess {
    override fun listEntryNames(): List<String> =
        zipFile.entries().toList().map { it.name }
    override fun readEntry(name: String): ByteArray? {
        val entry = zipFile.getEntry(name) ?: return null
        return zipFile.getInputStream(entry).use { it.readBytes() }
    }
}