package com.mangareader.opdsbridge.api

import android.util.Base64
import com.mangareader.opdsbridge.OpdsConstants
import com.mangareader.opdsbridge.data.OpdsAuthType
import com.mangareader.opdsbridge.data.OpdsConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess

import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI

class OpdsApiClient(
    private val connection: OpdsConnection,
    private val client: HttpClient,
) {

    private fun authHeaders(): Map<String, String> {
        return when (connection.authType) {
            OpdsAuthType.NONE -> emptyMap()
            OpdsAuthType.API_KEY -> mapOf(connection.apiKeyHeaderName to connection.apiKey)
            OpdsAuthType.BASIC -> {
                val raw = "${connection.username}:${connection.password}"
                val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                mapOf("Authorization" to "Basic $encoded")
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {
        authHeaders().forEach { (k, v) -> header(k, v) }
    }

    private fun mapError(code: Int): OpdsException {
        return when (code) {
            401, 403 -> OpdsAuthException("认证失败")
            404 -> OpdsException("资源不存在")
            in 500..599 -> OpdsException("服务器错误 $code")
            else -> OpdsException("请求失败 $code")
        }
    }

    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = client.get(connection.baseUrl) { applyAuth() }
            if (resp.status.isSuccess()) Result.success(Unit)
            else Result.failure(mapError(resp.status.value))
        } catch (t: Throwable) {
            Result.failure(OpdsNetworkException("连接失败", t))
        }
    }

    suspend fun fetchFeed(url: String): HttpResponse = withContext(Dispatchers.IO) {
        val resp = client.get(url) { applyAuth() }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        resp
    }

    suspend fun fetchFeedBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val resp = client.get(url) { applyAuth() }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        resp.bodyAsBytes()
    }

    suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val resp = client.get(url) {
            applyAuth()
            header("Accept", "*/*")
        }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        resp.bodyAsBytes()
    }

    suspend fun downloadToFile(
        url: String,
        dest: File,
        onProgress: ((downloadedBytes: Long) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        val resp = client.get(url) {
            applyAuth()
            header("Accept", "*/*")
        }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        val channel = resp.bodyAsChannel()
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                total += read
                if (total > OpdsConstants.LARGE_FILE_WARN_BYTES && total == read.toLong()) {
                    android.util.Log.i(OpdsConstants.LOG_TAG, "Large file download: $url")
                }
                onProgress?.invoke(total)
            }
            out.flush()
            total
        }
    }

    fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            val baseUri = URI(base)
            baseUri.resolve(relative).toString()
        } catch (_: Throwable) {
            relative
        }
    }
}