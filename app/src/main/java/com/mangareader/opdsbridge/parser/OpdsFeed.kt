package com.mangareader.opdsbridge.parser

data class OpdsAcquisitionLink(
    val url: String,
    val mimeType: String,
)

data class OpdsFacet(
    val title: String,
    val link: String,
    val isActive: Boolean,
)

data class OpdsCoverLinks(
    val thumbnailLink: String? = null,
    val imageLink: String? = null,
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val summary: String = "",
    val subsectionLink: String? = null,
    val acquisitionLinks: List<OpdsAcquisitionLink> = emptyList(),
    val thumbnailLink: String? = null,
    val imageLink: String? = null,
) {
    val isNavigation: Boolean get() = subsectionLink != null
    val isAcquisition: Boolean get() = acquisitionLinks.isNotEmpty()
}

data class OpdsFeed(
    val entries: List<OpdsEntry>,
    val nextLink: String? = null,
    val previousLink: String? = null,
    val searchLink: String? = null,
    val facets: List<OpdsFacet> = emptyList(),
    val selfLink: String? = null,
)