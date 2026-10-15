package com.mangareader.quarkbridge.auth

import com.mangareader.quarkbridge.QuarkConstants
import com.mangareader.quarkbridge.api.QuarkApiClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class QrState {
    object Generating : QrState()
    data class Waiting(val qrContentUrl: String, val token: String, val requestId: String) : QrState()
    object Scanned : QrState()
    data class Confirmed(val credentials: QuarkCredentials) : QrState()
    object Expired : QrState()
    data class Failed(val message: String) : QrState()
}

class QuarkLoginFlow(
    private val apiClient: QuarkApiClient,
    private val sessionStore: QuarkSessionStore
) {
    private var pendingTicket: String? = null
    private val cookieJar = mutableMapOf<String, String>()

    suspend fun generateQrCode(): QrState {
        return try {
            cookieJar.clear()
            pendingTicket = null
            apiClient.rateLimiter.acquire()
            val requestId = UUID.randomUUID().toString()
            val response = apiClient.httpClient.get(QuarkConstants.API_QR_TOKEN) {
                parameter("client_id", QuarkConstants.QR_CLIENT_ID)
                parameter("v", QuarkConstants.QR_API_VERSION)
                parameter("request_id", requestId)
            }
            collectSetCookies(response)
            val json = response.body<JsonObject>()
            val token = json.jsonPointer("data/members/token")
                ?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?: return QrState.Failed(
                    "获取二维码失败: ${json["message"]?.jsonPrimitive?.contentOrNull ?: "未知"}"
                )

            val qrContentUrl = buildQrContentUrl(token)
            QrState.Waiting(qrContentUrl, token, requestId)
        } catch (e: Exception) {
            QrState.Failed("网络错误: ${e.message ?: "未知"}")
        }
    }

    suspend fun pollStatus(token: String, requestId: String): QrState {
        return try {
            apiClient.rateLimiter.acquire()
            val response = apiClient.httpClient.get(QuarkConstants.API_QR_TICKET) {
                parameter("client_id", QuarkConstants.QR_CLIENT_ID)
                parameter("v", QuarkConstants.QR_API_VERSION)
                parameter("token", token)
                parameter("request_id", requestId)
            }
            collectSetCookies(response)
            val json = response.body<JsonObject>()

            val ticket = json.jsonPointer("data/members/service_ticket")
                ?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() }

            if (ticket != null) {
                pendingTicket = ticket
                QrState.Scanned
            } else {
                val status = json["status"]?.jsonPrimitive?.intOrNull
                when (status) {
                    50004002, 50004003, 50004004 -> QrState.Failed("扫码失败或已取消")
                    else -> QrState.Waiting("", token, requestId)
                }
            }
        } catch (e: Exception) {
            QrState.Failed("网络错误: ${e.message ?: "未知"}")
        }
    }

    suspend fun pollUntilConfirmed(
        token: String,
        requestId: String,
        onState: (QrState) -> Unit
    ): QrState {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < QuarkConstants.QR_TIMEOUT_MS) {
            val state = pollStatus(token, requestId)
            when (state) {
                is QrState.Scanned -> {
                    onState(state)
                    val cookie = exchangeCookie()
                    if (cookie.isEmpty()) {
                        val failed = QrState.Failed("登录成功但未获取到Cookie")
                        onState(failed)
                        return failed
                    }
                    val creds = QuarkCredentials(
                        cookie = cookie,
                        kps = "",
                        sign = "",
                        vcode = "",
                        nickname = "",
                        loginAt = System.currentTimeMillis()
                    )
                    sessionStore.save(creds)
                    val confirmed = QrState.Confirmed(creds)
                    onState(confirmed)
                    return confirmed
                }
                is QrState.Failed -> {
                    onState(state)
                    return state
                }
                else -> delay(QuarkConstants.QR_POLL_INTERVAL_MS)
            }
        }
        return QrState.Expired
    }

    private fun exchangeCookie(): String {
        val ticket = pendingTicket ?: return ""

        val noRedirectClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val cookies = mutableMapOf<String, String>()
        cookies.putAll(cookieJar)

        var url = "${QuarkConstants.API_ACCOUNT_INFO}?st=$ticket&lw=scan&platform=pc"
        for (hop in 0 until 5) {
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader)
                .build()
            val resp = noRedirectClient.newCall(request).execute()

            resp.headers("Set-Cookie").forEach { setCookie ->
                val pair = setCookie.substringBefore(";")
                val eqIdx = pair.indexOf('=')
                if (eqIdx > 0) {
                    val name = pair.substring(0, eqIdx).trim()
                    val value = pair.substring(eqIdx + 1).trim()
                    if (name.isNotEmpty()) {
                        cookies[name] = value
                    }
                }
            }

            val location = resp.header("Location")
            resp.close()

            if (location != null) {
                url = if (location.startsWith("http")) {
                    location
                } else {
                    val schemeEnd = url.indexOf("://")
                    if (schemeEnd > 0) {
                        val hostStart = schemeEnd + 3
                        val pathStart = url.indexOf('/', hostStart)
                        val base = if (pathStart > 0) url.substring(0, pathStart) else url
                        if (location.startsWith("/")) base + location else "$base/$location"
                    } else {
                        location
                    }
                }
            } else {
                break
            }
        }

        return if (cookies.isEmpty()) ""
        else cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun buildQrContentUrl(token: String): String {
        val params = listOf(
            "token" to token,
            "client_id" to QuarkConstants.QR_CLIENT_ID,
            "ssb" to "weblogin",
            "uc_param_str" to "",
            "uc_biz_str" to "S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0"
        )
        val query = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "${QuarkConstants.QR_BASE_URL}?$query"
    }

    private fun collectSetCookies(response: HttpResponse) {
        response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
            val pair = setCookie.substringBefore(";")
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                val name = pair.substring(0, eqIdx).trim()
                val value = pair.substring(eqIdx + 1).trim()
                if (name.isNotEmpty()) {
                    cookieJar[name] = value
                }
            }
        }
    }

    private fun JsonObject.jsonPointer(path: String): JsonElement? {
        val parts = path.trim('/').split('/')
        var current: JsonElement = this
        for (part in parts) {
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return current
    }
}