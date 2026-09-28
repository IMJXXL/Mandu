package com.mangareader.metadata

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

object ComicInfoParser {

    fun parse(xmlBytes: ByteArray): ComicInfo? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {}
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            } catch (_: Exception) {}
            try {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (_: Exception) {}
            factory.isNamespaceAware = false

            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

            var series: String? = null
            var number: String? = null
            var volume: String? = null
            var title: String? = null
            var writer: String? = null
            var penciller: String? = null
            var summary: String? = null
            var genre: String? = null
            var publisher: String? = null
            val extraFields = HashMap<String, String>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tagName = parser.name
                    val text = readElementText(parser)
                    when (tagName) {
                        "Series" -> series = text
                        "Number" -> number = text
                        "Volume" -> volume = text
                        "Title" -> title = text
                        "Writer" -> writer = text
                        "Penciller" -> penciller = text
                        "Summary" -> summary = text
                        "Genre" -> genre = text
                        "Publisher" -> publisher = text
                        else -> { if (text != null && tagName != "ComicInfo") extraFields[tagName] = text }
                    }
                }
                eventType = parser.next()
            }

            ComicInfo(series, number, volume, title, writer, penciller, summary, genre, publisher, extraFields)
        } catch (_: Exception) {
            null
        }
    }

    private fun readElementText(parser: XmlPullParser): String? {
        return try {
            val text = parser.nextText()
            text?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}