package com.mangareader.komgabridge.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

object KomgaHttpClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ★ P7（2026-10-04）：client 进程内共享缓存——旧版每次开书 new 整套 OkHttp+Ktor，
    //   TCP+TLS 握手重付、浏览与阅读连接池互不复用。按 allowSelfSigned 缓存两套；
    //   生命周期=进程，所有调用点禁止 close()（KomgaApiClient.close 已改为 no-op）。
    @Volatile private var cachedSecure: HttpClient? = null
    @Volatile private var cachedInsecure: HttpClient? = null

    fun client(allowSelfSigned: Boolean): HttpClient {
        if (allowSelfSigned) {
            cachedInsecure?.let { return it }
            synchronized(this) {
                cachedInsecure?.let { return it }
                return build(allowSelfSigned).also { cachedInsecure = it }
            }
        }
        cachedSecure?.let { return it }
        synchronized(this) {
            cachedSecure?.let { return it }
            return build(false).also { cachedSecure = it }
        }
    }

    private fun build(allowSelfSigned: Boolean): HttpClient {
        val okHttpBuilder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (allowSelfSigned) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
            okHttpBuilder.sslSocketFactory(sslContext.socketFactory, trustAll)
            okHttpBuilder.hostnameVerifier { _, _ -> true }
        }

        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpBuilder.build()
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 30_000
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }
}