package com.mangareader.opdsbridge.api

import com.mangareader.opdsbridge.OpdsConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

object OpdsHttpClient {

    fun client(allowSelfSigned: Boolean): HttpClient {
        val okHttpBuilder = OkHttpClient.Builder()
            .connectTimeout(OpdsConstants.CONNECT_TIMEOUT_MS / 1000, TimeUnit.SECONDS)
            .readTimeout(OpdsConstants.SOCKET_TIMEOUT_MS / 1000, TimeUnit.SECONDS)
            .writeTimeout(OpdsConstants.SOCKET_TIMEOUT_MS / 1000, TimeUnit.SECONDS)

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
                connectTimeoutMillis = OpdsConstants.CONNECT_TIMEOUT_MS
                requestTimeoutMillis = OpdsConstants.REQUEST_TIMEOUT_MS
                socketTimeoutMillis = OpdsConstants.SOCKET_TIMEOUT_MS
            }
        }
    }
}