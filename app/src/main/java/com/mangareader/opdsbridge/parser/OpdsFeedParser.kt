package com.mangareader.opdsbridge.parser

import com.mangareader.opdsbridge.api.OpdsParseException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

object OpdsFeedParser {

    private const val NS_ATOM = "http://www.w3.org/2005/Atom"
    private const val REL_SUBSECTION = "subsection"
    private const val REL_ACQUISITION_SUBSECTION = "http://opds-spec.org/acquisition/subsection"
    private const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
    private const val REL_IMAGE_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    private const val REL_IMAGE = "http://opds-spec.org/image"
    private const val REL_FACET = "http://opds-spec.org/facet"
    private const val REL_SEARCH = "search"
    private const val REL_NEXT = "next"
    private const val REL_PREVIOUS = "previous"
    private const val REL_SELF = "self"
    private const val REL_FIRST = "first"

    private val factory: XmlPullParserFactory by lazy {
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
            try { setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) } catch (_: Throwable) {}
            try { setFeature(XmlPullParser.FEATURE_VALIDATION, false) } catch (_: Throwable) {}
        }
    }

    fun parse(stream: InputStream, baseUrl: String): OpdsFeed {
        return try {
            val parser = factory.newPullParser()
            parser.setInput(stream, null)
            parseFeed(parser, baseUrl)
        } catch (e: OpdsParseException) {
            throw e
        } catch (t: Throwable) {
            throw OpdsParseException("解析 feed 失败", t)
        }
    }

    private fun parseFeed(parser: XmlPullParser, baseUrl: String): OpdsFeed {
        var currentBase = baseUrl
        val entries = mutableListOf<OpdsEntry>()
        var nextLink: String? = null
        var previousLink: String? = null
        var searchLink: String? = null
        var selfLink: String? = null
        val facets = mutableListOf<OpdsFacet>()

        var event = parser.eventType
        if (event != XmlPullParser.START_TAG) {
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = parser.next()
            }
        }
        if (event == XmlPullParser.END_DOCUMENT) {
            throw OpdsParseException("空文档")
        }
        val rootName = parser.name
        val rootNs = parser.namespace
        if (rootName != "feed" || (rootNs.isNotEmpty() && rootNs != NS_ATOM)) {
            throw OpdsParseException("非 Atom feed 文档: $rootName")
        }

        currentBase = parser.getAttributeValue(null, "base") ?: currentBase

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == NS_ATOM || (event == XmlPullParser.START_TAG && parser.namespace.isEmpty())) {
                when (parser.name) {
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val title = parser.getAttributeValue(null, "title") ?: ""
                        val active = parser.getAttributeValue(null, "active")
                        if (href.isNotEmpty()) {
                            val resolved = resolveUrl(currentBase, href)
                            when {
                                rel == REL_NEXT -> nextLink = resolved
                                rel == REL_PREVIOUS -> previousLink = resolved
                                rel == REL_SELF -> selfLink = resolved
                                rel == REL_SEARCH -> searchLink = resolved
                                rel == REL_FACET -> facets.add(OpdsFacet(title, resolved, active == "true"))
                            }
                        }
                    }
                    "entry" -> {
                        val entry = parseEntry(parser, currentBase)
                        if (entry != null) entries.add(entry)
                    }
                    "base" -> {
                        val newBase = parser.getAttributeValue(null, "href")
                        if (newBase != null) currentBase = newBase
                    }
                }
            }
            event = parser.next()
        }

        return OpdsFeed(
            entries = entries,
            nextLink = nextLink,
            previousLink = previousLink,
            searchLink = searchLink,
            facets = facets,
            selfLink = selfLink,
        )
    }

    private fun parseEntry(parser: XmlPullParser, base: String): OpdsEntry? {
        var entryBase = base
        var id: String? = null
        var title = ""
        var summary = ""
        var subsectionLink: String? = null
        val acquisitionLinks = mutableListOf<OpdsAcquisitionLink>()
        var thumbnailLink: String? = null
        var imageLink: String? = null

        val depth = parser.depth
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "entry") break
            if (event == XmlPullParser.START_TAG) {
                val ns = parser.namespace
                when (parser.name) {
                    "base" -> {
                        val newBase = parser.getAttributeValue(null, "href")
                        if (newBase != null) entryBase = newBase
                    }
                    "id" -> {
                        if (ns == NS_ATOM || ns.isEmpty()) id = readText(parser)
                    }
                    "title" -> {
                        if (ns == NS_ATOM || ns.isEmpty()) title = readText(parser)
                    }
                    "summary" -> {
                        if (ns == NS_ATOM || ns.isEmpty()) summary = readText(parser)
                    }
                    "content" -> {
                        if (ns == NS_ATOM || ns.isEmpty() && summary.isEmpty()) summary = readText(parser)
                    }
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        if (href.isNotEmpty()) {
                            val resolved = resolveUrl(entryBase, href)
                            when {
                                rel == REL_SUBSECTION || rel == REL_ACQUISITION_SUBSECTION -> subsectionLink = resolved
                                rel == REL_ACQUISITION || rel.startsWith("$REL_ACQUISITION/") -> acquisitionLinks.add(OpdsAcquisitionLink(resolved, type))
                                rel == REL_IMAGE_THUMBNAIL -> thumbnailLink = resolved
                                rel == REL_IMAGE -> imageLink = resolved
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        val finalId = id ?: acquisitionLinks.firstOrNull()?.url ?: return null
        return OpdsEntry(
            id = finalId,
            title = title.ifEmpty { finalId },
            summary = summary,
            subsectionLink = subsectionLink,
            acquisitionLinks = acquisitionLinks,
            thumbnailLink = thumbnailLink,
            imageLink = imageLink,
        )
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            java.net.URI(base).resolve(relative).toString()
        } catch (_: Throwable) {
            relative
        }
    }
}