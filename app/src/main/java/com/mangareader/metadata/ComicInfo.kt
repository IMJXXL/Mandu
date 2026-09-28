package com.mangareader.metadata

data class ComicInfo(
    val series: String? = null,
    val number: String? = null,
    val volume: String? = null,
    val title: String? = null,
    val writer: String? = null,
    val penciller: String? = null,
    val summary: String? = null,
    val genre: String? = null,
    val publisher: String? = null,
    val extraFields: Map<String, String> = emptyMap(),
) {
    fun isEmpty(): Boolean = series == null && number == null && volume == null &&
        title == null && writer == null && penciller == null &&
        summary == null && genre == null && publisher == null && extraFields.isEmpty()
}