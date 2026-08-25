package com.mangareader.zip

import android.content.Context
import android.net.Uri
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * EPUB结构解析器 - 单次遍历解析container.xml+OPF
 * 同时记录每个spine条目对应的ZIP entry位置
 *
 * ★ A 提速修复（2026-08-25）：
 *   旧 parse/resolveImageHrefs 即使 libzip 句柄已开（scanEpub :openZipWithFd 之后），
 *   仍各自走一遍 SAF openInputStream + ZipInputStream 全流顺序解压找 XML/HTML——打开慢根因。
 *   现增加 libzip 快路径：getZipEntryNames 定位 → readZipEntry 按名直读目标条目；
 *   原 ZipInputStream 实现整函数保留为兜底（loadArchiveCover 等句柄未开场景自动落入）。
 */
object EpubParser {
    private const val TAG = "EpubParser"

    // ★ A 修复④：Regex 提为静态 val（旧写法每次调用重新编译两个正则）
    private val IMG_SRC_PATTERNS = listOf(
        Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE),
        Regex("""<img[^>]+src='([^']+)'""", RegexOption.IGNORE_CASE)
    )

    data class EpubManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String = "",
        val zipEntryIndex: Int = -1  // ZIP中的物理位置（注：全项目无读取方，仅为兼容保留）
    )

    data class EpubResult(
        val spineItems: List<EpubManifestItem>,
        val coverItemId: String?,
        val coverHref: String?,
        val imageHrefs: List<String> = emptyList(),
        val opfDir: String = ""
    )

    /**
     * ★ A 修复③辅助：构建"完整路径→首个 index"+"文件名段→首个 index"双索引。
     * containsKey 保护 = putIfAbsent 语义，严格等价于旧线性查找的"取首个匹配"。
     * （endsWith("/x") || name==x ⟺ lastSegment==x，故 basename 索引与旧回退谓词数学等价）
     */
    private fun buildNameIndex(names: List<String>): Pair<HashMap<String, Int>, HashMap<String, Int>> {
        val full = HashMap<String, Int>(names.size * 2)
        val base = HashMap<String, Int>(names.size * 2)
        names.forEachIndexed { i, n ->
            if (!full.containsKey(n)) full[n] = i
            val b = n.substringAfterLast('/')
            if (b.isNotEmpty() && !base.containsKey(b)) base[b] = i
        }
        return full to base
    }

    /**
     * 单次遍历EPUB：读container.xml找OPF→读OPF解析manifest+spine→记录ZIP entry位置
     * @param cacheKey libzip 句柄 key（NativeEngine.isOpenZip(cacheKey) 为 true 时走快路径；null 走兜底）
     */
    fun parse(context: Context, uri: Uri, cacheKey: String? = null): EpubResult? {
        if (!cacheKey.isNullOrEmpty() &&
            com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
            try {
                parseViaLibZip(cacheKey)?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "parseViaLibZip failed, falling back to stream: ${e.message}")
            }
        }
        return parseLegacy(context, uri)
    }

    /** ★ A 修复①：libzip 按名直读 container.xml/OPF，零全流解压 */
    private fun parseViaLibZip(cacheKey: String): EpubResult? {
        val names = com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey) ?: return null
        val (fullIdx, baseIdx) = buildNameIndex(names.toList())
        fun locate(path: String): Int? =
            fullIdx[path] ?: baseIdx[path.substringAfterLast('/')]

        val contIdx = locate("META-INF/container.xml") ?: return null
        val contData = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, contIdx) ?: return null
        val opfPath = parseContainerXml(contData) ?: return null
        val opfIdx = locate(opfPath) ?: return null
        val opfData = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, opfIdx) ?: return null
        // entryPositions 仅喂 findEntryPosition 记 zipEntryIdx（该字段全项目零引用），快路径传空表即可
        val result = parseOpfContent(opfData, emptyMap()) ?: return null
        val opfDir = opfPath.substringBeforeLast("/", "")
        Log.d(TAG, "parse(libzip) OK: spine=${result.spineItems.size} cover=${result.coverHref} opfDir=$opfDir")
        return EpubResult(result.spineItems, result.coverItemId, result.coverHref, opfDir = opfDir)
    }

    /** 原 ZipInputStream 全流实现，整段保留为兜底 */
    private fun parseLegacy(context: Context, uri: Uri): EpubResult? {
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

            // ★ A 修复⑤：basename→pos 索引一次构建（旧 findEntryPosition 每 item O(N) 回退遍历）
            val basePositions = HashMap<String, Int>(entryPositions.size * 2)
            for ((name, pos) in entryPositions) {
                val b = name.substringAfterLast('/')
                if (b.isNotEmpty() && !basePositions.containsKey(b)) basePositions[b] = pos
            }

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
                            val zipIdx = findEntryPosition(href, entryPositions, basePositions)
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
    private fun findEntryPosition(href: String, entryPositions: Map<String, Int>, basePositions: Map<String, Int>): Int {
        // 精确匹配
        entryPositions[href]?.let { return it }
        // 匹配文件名（href可能是相对路径，ZIP中是完整路径）
        return basePositions[href.substringAfterLast("/")] ?: -1
    }

    /**
     * 解析EPUB中HTML页面引用的图片路径
     * 用于kmoe等EPUB：spine → HTML → <img src="image/xxx.jpg">
     */
    fun resolveImageHrefs(context: Context, uri: Uri, opfDir: String, spineItems: List<EpubManifestItem>, cacheKey: String? = null): Map<String, String> {
        val hrefSet = spineItems.map { resolveHref(opfDir, it.href) }.toSet()

        // ★ A 修复②：libzip 按名直读 spine 引用的 HTML（旧版全流解压整个 ZIP）
        if (!cacheKey.isNullOrEmpty() &&
            com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
            try {
                val names = com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey)
                if (names != null) {
                    val (fullIdx, baseIdx) = buildNameIndex(names.toList())
                    val fast = HashMap<String, String>()
                    var matched = 0
                    for (href in hrefSet) {
                        val idx = fullIdx[href] ?: baseIdx[href.substringAfterLast('/')] ?: continue
                        val html = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, idx)
                            ?.toString(Charsets.UTF_8) ?: continue
                        extractImgSrc(html)?.let { fast[href] = it; matched++ }
                    }
                    Log.d(TAG, "resolveImageHrefs(libzip): matched $matched/${hrefSet.size}")
                    // 全 miss 时仍落兜底（防个别 EPUB 的 libzip 名字视图与流式视图不一致）
                    if (matched > 0) return fast
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolveImageHrefs libzip failed, falling back to stream: ${e.message}")
            }
        }
        return resolveImageHrefsLegacy(context, uri, opfDir, spineItems)
    }

    /** 原 ZipInputStream 全流实现，整段保留为兜底 */
    private fun resolveImageHrefsLegacy(context: Context, uri: Uri, opfDir: String, spineItems: List<EpubManifestItem>): Map<String, String> {
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
        for (pattern in IMG_SRC_PATTERNS) {
            pattern.find(html)?.let { return it.groupValues[1] }
        }
        return null
    }
}
