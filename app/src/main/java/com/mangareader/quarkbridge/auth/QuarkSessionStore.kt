package com.mangareader.quarkbridge.auth

import android.content.Context
import com.mangareader.quarkbridge.QuarkConstants
import java.util.concurrent.TimeUnit

data class QuarkCredentials(
    val cookie: String,
    val kps: String,
    val sign: String,
    val vcode: String,
    val nickname: String,
    val loginAt: Long
)

class QuarkSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(QuarkConstants.PREFS_SESSION, Context.MODE_PRIVATE)

    fun save(credentials: QuarkCredentials) {
        prefs.edit()
            .putString(KEY_COOKIE, QuarkCredentialCrypto.encrypt(credentials.cookie))
            .putString(KEY_KPS, QuarkCredentialCrypto.encrypt(credentials.kps))
            .putString(KEY_SIGN, QuarkCredentialCrypto.encrypt(credentials.sign))
            .putString(KEY_VCODE, QuarkCredentialCrypto.encrypt(credentials.vcode))
            .putString(KEY_NICKNAME, credentials.nickname)
            .putLong(KEY_LOGIN_AT, credentials.loginAt)
            .apply()
    }

    fun load(): QuarkCredentials? {
        val cookie = QuarkCredentialCrypto.decryptStored(prefs.getString(KEY_COOKIE, "") ?: "")
        if (cookie.isEmpty()) return null
        return QuarkCredentials(
            cookie = cookie,
            kps = QuarkCredentialCrypto.decryptStored(prefs.getString(KEY_KPS, "") ?: ""),
            sign = QuarkCredentialCrypto.decryptStored(prefs.getString(KEY_SIGN, "") ?: ""),
            vcode = QuarkCredentialCrypto.decryptStored(prefs.getString(KEY_VCODE, "") ?: ""),
            nickname = prefs.getString(KEY_NICKNAME, "") ?: "",
            loginAt = prefs.getLong(KEY_LOGIN_AT, 0L)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isValid(): Boolean {
        val creds = load() ?: return false
        if (creds.cookie.isEmpty()) return false
        val maxAgeMs = TimeUnit.DAYS.toMillis(QuarkConstants.SESSION_VALID_DAYS)
        return System.currentTimeMillis() - creds.loginAt < maxAgeMs
    }

    fun updateNickname(nickname: String) {
        prefs.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    companion object {
        private const val KEY_COOKIE = "cookie"
        private const val KEY_KPS = "kps"
        private const val KEY_SIGN = "sign"
        private const val KEY_VCODE = "vcode"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LOGIN_AT = "login_at"
    }
}