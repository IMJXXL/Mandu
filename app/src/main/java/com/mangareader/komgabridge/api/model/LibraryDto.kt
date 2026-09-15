package com.mangareader.komgabridge.api.model

import kotlinx.serialization.Serializable

@Serializable
data class LibraryDto(
    val id: String = "",
    val name: String = "",
)