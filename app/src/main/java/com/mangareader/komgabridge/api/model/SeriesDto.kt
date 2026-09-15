package com.mangareader.komgabridge.api.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthorDto(
    val name: String = "",
    val role: String = "",
)

@Serializable
data class SeriesMetadataDto(
    val title: String = "",
    val summary: String = "",
    val status: String = "",
    val authors: List<AuthorDto> = emptyList(),
    val tags: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val readingDirection: String = "",
)

@Serializable
data class SeriesDto(
    val id: String = "",
    val name: String = "",
    val libraryId: String = "",
    val metadata: SeriesMetadataDto = SeriesMetadataDto(),
    val booksCount: Int = 0,
)