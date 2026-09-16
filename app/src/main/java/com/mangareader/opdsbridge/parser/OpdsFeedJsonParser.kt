package com.mangareader.opdsbridge.parser

import com.mangareader.opdsbridge.api.OpdsParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object OpdsFeedJsonParser {

    private const val REL_SUBSECTION = "subsection"
    private const val REL_ACQUISITION_SUBSECTION = "http://opds-spec.org/acquisition/subsection"
    private const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
    private const val REL_IMAGE_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    private const val REL_IMAGE = "http://opds-spec.org/image"
    private const val REL_NEXT = "next"
    private const val REL_PREVIOUS = "previous"
    private const val REL_SEARCH = "search"
    private const val REL_SELF = "self"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, baseUrl: String): OpdsFeed {
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            parseFeedObject(root, baseUrl)
        } catch (e: OpdsParseException) {
            throw e
        } catch (t: Throwable) {
            throw OpdsParseException("解析 JSON feed 失败", t)
        }
    }

    private fun parseFeedObject(root: JsonObject, baseUrl: String): OpdsFeed {
        val links = root["links"]?.jsonArray ?: JsonArray(emptyList())
        var nextLink: String? = null
        var previousLink: String? = null
        var searchLink: String? = null
        var selfLink: String? = null

        for (linkEl in links) {
            val link = linkEl.jsonObject
            val rel = (link["rel"] as? JsonPrimitive)?.contentOrNull ?: ""
            val href = (link["href"] as? JsonPrimitive)?.contentOrNull ?: ""
            if (href.isEmpty()) continue
            val resolved = resolveUrl(baseUrl, href)
            when (rel) {
                REL_NEXT -> nextLink = resolved
                REL_PREVIOUS -> previousLink = resolved
                REL_SEARCH -> searchLink = resolved
                REL_SELF -> selfLink = resolved
            }
        }

        val entries = mutableListOf<OpdsEntry>()

        root["navigation"]?.jsonArray?.forEach { navEl ->
            val nav = navEl.jsonObject
            val title = (nav["title"] as? JsonPrimitive)?.contentOrNull ?: ""
            val href = (nav["href"] as? JsonPrimitive)?.contentOrNull ?: ""
            val rel = (nav["rel"] as? JsonPrimitive)?.contentOrNull ?: ""
            if (href.isNotEmpty()) {
                val resolved = resolveUrl(baseUrl, href)
                val entryId = (nav["id"] as? JsonPrimitive)?.contentOrNull ?: resolved
                entries.add(OpdsEntry(
                    id = entryId,
                    title = title.ifEmpty { entryId },
                    subsectionLink = resolved,
                ))
            }
        }

        root["publications"]?.jsonArray?.forEach { pubEl ->
            val pub = pubEl.jsonObject
            val metadata = pub["metadata"]?.jsonObject ?: JsonObject(emptyMap())
            val title = (metadata["title"] as? JsonPrimitive)?.contentOrNull ?: ""
            val identifier = (metadata["identifier"] as? JsonPrimitive)?.contentOrNull ?: ""
            val summary = (metadata["description"] as? JsonPrimitive)?.contentOrNull ?: ""

            val pubLinks = pub["links"]?.jsonArray ?: JsonArray(emptyList())
            var subsectionLink: String? = null
            val acquisitionLinks = mutableListOf<OpdsAcquisitionLink>()
            var thumbnailLink: String? = null
            var imageLink: String? = null

            for (linkEl in pubLinks) {
                val link = linkEl.jsonObject
                val rel = (link["rel"] as? JsonPrimitive)?.contentOrNull ?: ""
                val href = (link["href"] as? JsonPrimitive)?.contentOrNull ?: ""
                val type = (link["type"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (href.isEmpty()) continue
                val resolved = resolveUrl(baseUrl, href)
                when {
                    rel == REL_SUBSECTION || rel == REL_ACQUISITION_SUBSECTION -> subsectionLink = resolved
                    rel == REL_ACQUISITION || rel.startsWith("$REL_ACQUISITION/") -> acquisitionLinks.add(OpdsAcquisitionLink(resolved, type))
                    rel == REL_IMAGE_THUMBNAIL -> thumbnailLink = resolved
                    rel == REL_IMAGE -> imageLink = resolved
                }
            }

            val entryId = identifier.ifEmpty { acquisitionLinks.firstOrNull()?.url ?: subsectionLink ?: "" }
            if (entryId.isEmpty()) return@forEach
            entries.add(OpdsEntry(
                id = entryId,
                title = title.ifEmpty { entryId },
                summary = summary,
                subsectionLink = subsectionLink,
                acquisitionLinks = acquisitionLinks,
                thumbnailLink = thumbnailLink,
                imageLink = imageLink,
            ))
        }

        val facets = mutableListOf<OpdsFacet>()
        root["facets"]?.jsonArray?.forEach { facetGroupEl ->
            val facetGroup = facetGroupEl.jsonObject
            val facetLinks = facetGroup["links"]?.jsonArray ?: return@forEach
            for (linkEl in facetLinks) {
                val link = linkEl.jsonObject
                val title = (link["title"] as? JsonPrimitive)?.contentOrNull ?: (facetGroup["title"] as? JsonPrimitive)?.contentOrNull ?: ""
                val href = (link["href"] as? JsonPrimitive)?.contentOrNull ?: continue
                val active = (link["active"] as? JsonPrimitive)?.contentOrNull == "true"
                facets.add(OpdsFacet(title, resolveUrl(baseUrl, href), active))
            }
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

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            java.net.URI(base).resolve(relative).toString()
        } catch (_: Throwable) {
            relative
        }
    }
}