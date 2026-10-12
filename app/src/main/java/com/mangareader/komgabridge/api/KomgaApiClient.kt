package com.mangareader.komgabridge.api

import android.util.Base64
import com.mangareader.komgabridge.KomgaConstants
import com.mangareader.komgabridge.api.model.BookDto
import com.mangareader.komgabridge.api.model.LibraryDto
import com.mangareader.komgabridge.api.model.PageDto
import com.mangareader.komgabridge.api.model.PageableDto
import com.mangareader.komgabridge.api.model.SeriesDto
import com.mangareader.komgabridge.data.KomgaAuthType
import com.mangareader.komgabridge.data.KomgaConnection
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class KomgaApiClient(
    private val connection: KomgaConnection,
    private val client: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun authHeaders(): Map<String, String> {
        return when (connection.authType) {
            KomgaAuthType.API_KEY -> mapOf("X-API-Key" to connection.apiKey)
            KomgaAuthType.BASIC -> {
                val raw = "${connection.username}:${connection.password}"
                val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                mapOf("Authorization" to "Basic $encoded")
            }
        }
    }

    private fun HttpRequestBuilder.applyAuth() {
        authHeaders().forEach { (k, v) -> header(k, v) }
    }

    private suspend fun <T> request(
        method: suspend () -> HttpResponse,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val resp = method()
        if (!resp.status.isSuccess()) {
            throw mapError(resp.status.value)
        }
        val text = resp.bodyAsBytes().toString(Charsets.UTF_8)
        json.decodeFromString(deserializer, text)
    }

    private fun mapError(code: Int): KomgaException {
        return when (code) {
            401, 403 -> KomgaAuthException("认证失败")
            404 -> KomgaException("资源不存在")
            406, 415 -> KomgaException("服务端不支持PDF转图($code)")
            in 500..599 -> KomgaException("服务器错误 $code")
            else -> KomgaException("请求失败 $code")
        }
    }

    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = client.get("${connection.baseUrl}/api/v1/libraries") { applyAuth() }
            if (resp.status.isSuccess()) Result.success(Unit)
            else Result.failure(mapError(resp.status.value))
        } catch (t: Throwable) {
            Result.failure(KomgaException("连接失败", t))
        }
    }

    suspend fun getLibraries(): List<LibraryDto> = withContext(Dispatchers.IO) {
        val resp = client.get("${connection.baseUrl}/api/v1/libraries") { applyAuth() }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        val text = resp.bodyAsBytes().toString(Charsets.UTF_8)
        json.decodeFromString(ListSerializer(LibraryDto.serializer()), text)
    }

    suspend fun getSeries(
        libraryId: String? = null,
        search: String? = null,
        readStatus: String? = null,
        page: Int = 0,
        size: Int = KomgaConstants.PAGE_SIZE,
        sort: String? = null,
    ): PageableDto<SeriesDto> = withContext(Dispatchers.IO) {
        val resp = client.get("${connection.baseUrl}/api/v1/series") {
            applyAuth()
            if (libraryId != null) parameter("library_id", libraryId)
            if (search != null) parameter("search", search)
            if (readStatus != null) parameter("read_status", readStatus)
            parameter("page", page)
            parameter("size", size)
            if (sort != null) parameter("sort", sort)
        }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        val text = resp.bodyAsBytes().toString(Charsets.UTF_8)
        json.decodeFromString(PageableDto.serializer(SeriesDto.serializer()), text)
    }

    suspend fun getSeriesDetail(seriesId: String): SeriesDto = withContext(Dispatchers.IO) {
        val resp = client.get("${connection.baseUrl}/api/v1/series/$seriesId") { applyAuth() }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        val text = resp.bodyAsBytes().toString(Charsets.UTF_8)
        json.decodeFromString(SeriesDto.serializer(), text)
    }

    suspend fun getSeriesBooks(
        seriesId: String,
        page: Int = 0,
        size: Int = KomgaConstants.PAGE_SIZE,
        readStatus: String? = null,
        sort: String? = null,
    ): PageableDto<BookDto> = withContext(Dispatchers.IO) {
        val resp = client.get("${connection.baseUrl}/api/v1/series/$seriesId/books") {
            applyAuth()
            parameter("page", page)
            parameter("size", size)
            if (readStatus != null) parameter("read_status", readStatus)
            if (sort != null) parameter("sort", sort)
        }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        val text = resp.bodyAsBytes().toString(Charsets.UTF_8)
        json.decodeFromString(PageableDto.serializer(BookDto.serializer()), text)
    }

    suspend fun getBook(bookId: String): BookDto = withContext(Dispatchers.IO) {
        val resp = client.get("${connection.baseUrl}/api/v1/books/$bookId") { applyAuth() }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        val text = resp.bodyAsBytes().toString(Charsets.UTF_8)
        json.decodeFromString(BookDto.serializer(), text)
    }

    suspend fun getBookPages(bookId: String): List<PageDto> = withContext(Dispatchers.IO) {
        val resp = client.get("${connection.baseUrl}/api/v1/books/$bookId/pages") { applyAuth() }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        val text = resp.bodyAsBytes().toString(Charsets.UTF_8)
        json.decodeFromString(ListSerializer(PageDto.serializer()), text)
    }

    fun pageImageUrl(bookId: String, pageNumber: Int, isPdf: Boolean = false): String {
        val base = "${connection.baseUrl}/api/v1/books/$bookId/pages/$pageNumber"
        return if (isPdf) "$base?convert=jpeg" else base
    }

    /** 页缩略图（约 320px，封面编辑网格取材用，流量约为原图 1/10）；端点不可用时由调用方回退 pageImageUrl */
    fun pageThumbnailUrl(bookId: String, pageNumber: Int): String =
        "${connection.baseUrl}/api/v1/books/$bookId/pages/$pageNumber/thumbnail"

    fun seriesThumbnailUrl(seriesId: String): String =
        "${connection.baseUrl}/api/v1/series/$seriesId/thumbnail"

    fun bookThumbnailUrl(bookId: String): String =
        "${connection.baseUrl}/api/v1/books/$bookId/thumbnail"

    suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val resp = client.get(url) {
            applyAuth()
            header("Accept", "*/*")
        }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
        resp.bodyAsBytes()
    }

    suspend fun updateReadProgress(bookId: String, page: Int, completed: Boolean) = withContext(Dispatchers.IO) {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("page", kotlinx.serialization.json.JsonPrimitive(page))
            put("completed", kotlinx.serialization.json.JsonPrimitive(completed))
        }.toString()
        val resp = client.patch("${connection.baseUrl}/api/v1/books/$bookId/read-progress") {
            applyAuth()
            header("Content-Type", "application/json")
            setBody(body)
        }
        if (!resp.status.isSuccess()) throw mapError(resp.status.value)
    }

    suspend fun searchSeries(search: String, libraryId: String? = null, page: Int = 0): PageableDto<SeriesDto> =
        getSeries(libraryId = libraryId, search = search, page = page)

    /** ★ P7（2026-10-04）：client 已改为进程内共享（KomgaHttpClient 缓存），close 改 no-op
     *  防止任何调用点关掉共享连接池。保留函数签名兼容既有调用。 */
    fun close() = Unit
}