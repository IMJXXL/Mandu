package com.mangareader.opdsbridge.parser

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URLEncoder

object OpenSearchParser {

    private val factory: XmlPullParserFactory by lazy {
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
            try { setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) } catch (_: Throwable) {}
            try { setFeature(XmlPullParser.FEATURE_VALIDATION, false) } catch (_: Throwable) {}
        }
    }

    fun parseTemplate(stream: InputStream): String? {
        return try {
            val parser = factory.newPullParser()
            parser.setInput(stream, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Url") {
                    val template = parser.getAttributeValue(null, "template")
                    if (template != null && template.isNotEmpty()) return template
                }
                event = parser.next()
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun buildSearchUrl(template: String, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return template.replace("{searchTerms}", encoded)
            .replace("{searchTerms:encoding=unicode}", encoded)
    }
}