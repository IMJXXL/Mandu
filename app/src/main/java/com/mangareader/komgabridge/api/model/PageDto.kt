package com.mangareader.komgabridge.api.model

import kotlinx.serialization.Serializable

@Serializable
data class PageDto(
    val number: Int = 0,
    val fileName: String = "",
    val sizeBytes: Long = 0L,
)

@Serializable
data class PageableDto<T>(
    val content: List<T> = emptyList(),
    val totalElements: Int = 0,
    val totalPages: Int = 0,
    val number: Int = 0,
)

@Serializable
data class CollectionDto(
    val id: String = "",
    val name: String = "",
    val seriesIds: List<String> = emptyList(),
    val ordered: Boolean = false,
)

@Serializable
data class ReadlistDto(
    val id: String = "",
    val name: String = "",
    val bookIds: List<String> = emptyList(),
    val ordered: Boolean = false,
)