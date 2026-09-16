package com.mangareader.opdsbridge.data

import kotlinx.serialization.Serializable

@Serializable
enum class OpdsAuthType { NONE, BASIC, API_KEY }

@Serializable
data class OpdsConnection(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authType: OpdsAuthType,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val apiKeyHeaderName: String = "X-API-Key",
    val allowSelfSignedCert: Boolean = false,
)