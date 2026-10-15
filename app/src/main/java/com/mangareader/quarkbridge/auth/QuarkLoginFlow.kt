package com.mangareader.quarkbridge.auth

import com.mangareader.quarkbridge.QuarkConstants
import com.mangareader.quarkbridge.api.QuarkApiClient
import com.mangareader.quarkbridge.api.QuarkAuthException
import com.mangareader.quarkbridge.api.QuarkException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.regex.Pattern

sealed class QrState {
    object Generating : QrState()
    data class Waiting(val qrImageBytes: ByteArray, val token: String) : QrState()
    object Scanned : QrState()
    data class Confirmed(val credentials: QuarkCredentials) : QrState()
    object Expired : QrState()
    data class Failed(val message: String) : QrState()
}

class QuarkLoginFlow(
    private val apiClient: QuarkApiClient,
    private val sessionStore: QuarkSessionStore
) {
    private val kpsPattern = Pattern.compile("""(?<!\w)kps=([a-zA-Z0-9%+/=]+)[;&]?""")
    private val signPattern = Pattern.compile("""(?<!\w)sign=([a-zA-Z0-9%+/=]+)[;&]?""")
    private val vcodePattern = Pattern.compile("""(?<!\w)vcode=([a-zA-Z0-9%+/=]+)[;&]?""")

    suspend fun generateQrCode(): QrState {
        return try {
            apiClient.rateLimiter.acquire()
            val response = apiClient.httpClient.get("${QuarkConstants.API_BASE_PAN}/account/token") {
                parameter("fr", "pc")
                parameter("platform", "pc")
            }
            val json = response.body<JsonObject>()
            val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) {
                QrState.Failed("生成二维码失败: ${json["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"}")
            } else {
                val data = json["data"]?.jsonObject ?: return QrState.Failed("响应数据为空")
                val token = data["token"]?.jsonPrimitive?.contentOrNull ?: return QrState.Failed("token为空")
                val qrUrl = data["qr_image_url"]?.jsonPrimitive?.contentOrNull
                    ?: data["qr_code_url"]?.jsonPrimitive?.contentOrNull
                    ?: return QrState.Failed("二维码URL为空")

                apiClient.rateLimiter.acquire()
                val imgResponse = apiClient.httpClient.get(qrUrl)
                val imgBytes = imgResponse.body<ByteArray>()
                QrState.Waiting(imgBytes, token)
            }
        } catch (e: QuarkAuthException) {
            QrState.Failed("认证失败: ${e.message}")
        } catch (e: Exception) {
            QrState.Failed("网络错误: ${e.message ?: "未知"}")
        }
    }

    suspend fun pollStatus(token: String): QrState {
        return try {
            apiClient.rateLimiter.acquire()
            val response = apiClient.httpClient.get("${QuarkConstants.API_BASE_PAN}/account/status") {
                parameter("token", token)
                parameter("fr", "pc")
                parameter("platform", "pc")
            }
            val json = response.body<JsonObject>()
            val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) {
                return QrState.Failed("查询状态失败: ${json["message"]?.jsonPrimitive?.contentOrNull ?: "未知"}")
            }
            val data = json["data"]?.jsonObject ?: return QrState.Failed("响应数据为空")
            val status = data["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            when (status) {
                "waiting" -> QrState.Waiting(ByteArray(0), token)
                "scanned" -> QrState.Scanned
                "confirmed", "success" -> {
                    val cookie = extractCookieFromResponse(response)
                    if (cookie.isEmpty()) {
                        QrState.Failed("登录成功但未获取到Cookie")
                    } else {
                        val creds = buildCredentials(cookie, data["nickname"]?.jsonPrimitive?.contentOrNull ?: "")
                        sessionStore.save(creds)
                        QrState.Confirmed(creds)
                    }
                }
                "expired" -> QrState.Expired
                else -> QrState.Waiting(ByteArray(0), token)
            }
        } catch (e: Exception) {
            QrState.Failed("网络错误: ${e.message ?: "未知"}")
        }
    }

    suspend fun pollUntilConfirmed(token: String, onState: (QrState) -> Unit): QrState {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < QuarkConstants.QR_TIMEOUT_MS) {
            val state = pollStatus(token)
            onState(state)
            when (state) {
                is QrState.Confirmed -> return state
                is QrState.Expired -> return state
                is QrState.Failed -> return state
                else -> delay(QuarkConstants.QR_POLL_INTERVAL_MS)
            }
        }
        return QrState.Expired
    }

    private fun extractCookieFromResponse(response: HttpResponse): String {
        val setCookieHeaders = response.headers.getAll("Set-Cookie") ?: return ""
        return setCookieHeaders.joinToString("; ") { it.substringBefore(";") }
    }

    private fun buildCredentials(cookie: String, nickname: String): QuarkCredentials {
        val kps = extractPattern(kpsPattern, cookie)
        val sign = extractPattern(signPattern, cookie)
        val vcode = extractPattern(vcodePattern, cookie)
        return QuarkCredentials(
            cookie = cookie,
            kps = kps,
            sign = sign,
            vcode = vcode,
            nickname = nickname,
            loginAt = System.currentTimeMillis()
        )
    }

    private fun extractPattern(pattern: Pattern, text: String): String {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }
}