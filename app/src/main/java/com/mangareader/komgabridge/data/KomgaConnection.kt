package com.mangareader.komgabridge.data

import kotlinx.serialization.Serializable

@Serializable
enum class KomgaAuthType { API_KEY, BASIC }

@Serializable
data class KomgaConnection(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authType: KomgaAuthType,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val allowSelfSignedCert: Boolean = false,
)