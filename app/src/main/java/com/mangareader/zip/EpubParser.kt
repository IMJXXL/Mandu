package com.mangareader.zip

import android.content.Context
import android.net.Uri
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * EPUB结构解析器 - 单次遍历解析container.xml+OPF
 * 同时记录每个spine条目对应的ZIP entry位置
 */
object EpubParser {
    private const val TAG = "EpubParser"

    data class EpubManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String = "",
        val zipEntryIndex: Int = -1  // ZIP中的物理位置
    )

    data class EpubResult(
        val spineItems: List<EpubManifestItem>,
        val coverItemId: String?,
        val coverHref: String?,
        val imageHrefs: List<String> = emptyList(),
        val opfDir: String = ""
    )

    /**
     * 单次遍历EPUB：读container.xml找OPF→读OPF解析manifest+spine→记录ZIP entry位置
     */
    fun parse(context: Context, uri: Uri): EpubResult? {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val zipInput = java.util.zip.ZipInputStream(input)
                var opfPath: String? = null
                var opfData: ByteArray? = null
                val entryPositions = mutableMapOf<String, Int>()
                var zipEntryIdx = 0

                var entry = zipInput.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        entryPositions[name] = zipEntryIdx
                    }

                    if (opfPath == null && name == "META-INF/container.xml") {
                        opfData = zipInput.readBytes()
                    } else if (opfPath != null && opfData == null && (name == opfPath || name.endsWith("/$opfPath"))) {
                        opfData = zipInput.readBytes()
                    }

                    if (opfData != null && opfPath == null) {
                        opfPath = parseContainerXml(opfData!!)
                        opfData = null
                    }

                    if (opfData != null && opfPath != null) {
                        val result = parseOpfContent(opfData!!, entryPositions)
                        zipInput.close()
                        if (result != null) {
                            val opfDir = opfPath!!.substringBeforeLast("/", "")
                            Log.d(TAG, "parse OK: spine=${result.spineItems.size} cover=${result.coverHref} opfDir=$opfDir")
                            return EpubResult(result.spineItems, result.coverItemId, result.coverHref, opfDir = opfDir)
                        }
                        return null
                    }
                    zipEntryIdx++
                    entry = zipInput.nextEntry
                }
                zipInput.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}")
        }
        return null
    }

    private fun parseContainerXml(data: ByteArray): String? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(data.inputStream(), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (fullPath != null) return fullPath
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { Log.e(TAG, "parseContainerXml: ${e.message}") }
        return null
    }

    private data class OpfResult(
        val manifestItems: Map<String, EpubManifestItem>,
        val spineItemIds: List<String>,
        val coverItemId: String?,
        val coverHref: String?
    )

    private fun parseOpfContent(data: ByteArray, entryPositions: Map<String, Int>): EpubResult? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(data.inputStream(), "UTF-8")

            val manifestItems = mutableMapOf<String, EpubManifestItem>()
            val spineItemIds = mutableListOf<String>()
            var coverItemId: String? = null
            var coverHref: String? = null
            var inManifest = false
            var inSpine = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "manifest" -> inManifest = true
                        "spine" -> { inManifest = false; inSpine = true }
                        "item" -> if (inManifest) {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            val properties = parser.getAttributeValue(null, "properties") ?: ""
                            // 找到href对应的ZIP entry位置
                            val zipIdx = findEntryPosition(href, entryPositions)
                            manifestItems[id] = EpubManifestItem(id, href, mediaType, properties, zipIdx)
                            if (properties.contains("cover-image")) { coverItemId = id; coverHref = href }
                        }
                        "itemref" -> if (inSpine) {
                            val idref = parser.getAttributeValue(null, "idref") ?: ""
                            if (idref.isNotEmpty()) spineItemIds.add(idref)
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                }
                eventType = parser.next()
            }

            if (spineItemIds.isEmpty()) return null
            // 保留所有 spine 项（包括 XHTML），不再只过滤图片
            // XHTML spine 项会在 resolveImageHrefs 中从 HTML 提取图片顺序
            val spineItems = spineItemIds.mapNotNull { manifestItems[it] }

            return EpubResult(spineItems, coverItemId, coverHref)
        } catch (e: Exception) { Log.e(TAG, "parseOpfContent: ${e.message}") }
        return null
    }

    /**
     * 找到href对应的ZIP entry位置
     * OPF的href可能是相对路径（如OEBPS/image001.jpg），需要匹配ZIP中的完整路径
     */
    private fun findEntryPosition(href: String, entryPositions: Map<String, Int>): Int {
        // 精确匹配
        entryPositions[href]?.let { return it }
        // 匹配文件名（href可能是相对路径，ZIP中是完整路径）
        val fileName = href.substringAfterLast("/")
        for ((name, pos) in entryPositions) {
            if (name.endsWith("/$fileName") || name == fileName) return pos
        }
        return -1
    }

    /**
     * 解析EPUB中HTML页面引用的图片路径
     * 用于kmoe等EPUB：spine → HTML → <img src="image/xxx.jpg">
     */
    fun resolveImageHrefs(context: Context, uri: Uri, opfDir: String, spineItems: List<EpubManifestItem>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val zipInput = java.util.zip.ZipInputStream(input)
                val hrefSet = spineItems.map { resolveHref(opfDir, it.href) }.toSet()
                Log.d(TAG, "resolveImageHrefs: opfDir='$opfDir', hrefSet first5=${hrefSet.take(5)}")
                var matched = 0
                var entry = zipInput.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name in hrefSet) {
                        val html = zipInput.readBytes().toString(Charsets.UTF_8)
                        extractImgSrc(html)?.let { result[entry.name] = it; matched++ }
                    }
                    entry = zipInput.nextEntry
                }
                Log.d(TAG, "resolveImageHrefs: matched $matched/${hrefSet.size} HTML files, result keys first5=${result.keys.take(5)}")
                zipInput.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveImageHrefs failed: ${e.message}")
        }
        return result
    }

    fun resolveHref(opfDir: String, href: String): String {
        return if (opfDir.isNotEmpty()) "$opfDir/$href" else href
    }

    private fun extractImgSrc(html: String): String? {
        // 匹配 <img ... src="xxx" ...> 或 <img ... src='xxx' ...>
        val patterns = listOf(
            Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE),
            Regex("""<img[^>]+src='([^']+)'""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(html)?.let { return it.groupValues[1] }
        }
        return null
    }
}
