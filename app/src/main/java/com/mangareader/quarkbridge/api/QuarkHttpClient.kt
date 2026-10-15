package com.mangareader.quarkbridge.api

import com.mangareader.quarkbridge.QuarkConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object QuarkHttpClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cached: HttpClient? = null

    fun client(): HttpClient {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return build().also { cached = it }
        }
    }

    private fun build(): HttpClient {
        val okHttpBuilder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpBuilder.build()
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 30_000
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }
}