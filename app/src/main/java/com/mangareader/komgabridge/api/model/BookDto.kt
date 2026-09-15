package com.mangareader.komgabridge.api.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaDto(
    val mediaType: String = "",
    val pagesCount: Int = 0,
    val mediaProfile: String? = null,
)

@Serializable
data class BookMetadataDto(
    val title: String = "",
    val summary: String = "",
)

@Serializable
data class ReadProgressDto(
    val page: Int = 0,
    val completed: Boolean = false,
)

@Serializable
data class BookDto(
    val id: String = "",
    val name: String = "",
    val number: Int = 0,
    val seriesId: String = "",
    val media: MediaDto = MediaDto(),
    val metadata: BookMetadataDto = BookMetadataDto(),
    val readProgress: ReadProgressDto? = null,
)