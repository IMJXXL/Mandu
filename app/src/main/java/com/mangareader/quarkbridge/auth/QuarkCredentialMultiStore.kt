package com.mangareader.quarkbridge.auth

import android.content.Context
import com.mangareader.quarkbridge.QuarkConstants
import java.util.concurrent.TimeUnit
import splitties.init.appCtx

object QuarkCredentialMultiStore {
    private const val PREFS = "quark_multi_creds"
    private const val PREFIX = "acc_"

    private val prefs by lazy {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun save(accountId: Long, credentials: QuarkCredentials) {
        val p = PREFIX + accountId + "_"
        prefs.edit()
            .putString(p + "cookie", QuarkCredentialCrypto.encrypt(credentials.cookie))
            .putString(p + "kps", QuarkCredentialCrypto.encrypt(credentials.kps))
            .putString(p + "sign", QuarkCredentialCrypto.encrypt(credentials.sign))
            .putString(p + "vcode", QuarkCredentialCrypto.encrypt(credentials.vcode))
            .putString(p + "nickname", credentials.nickname)
            .putLong(p + "login_at", credentials.loginAt)
            .apply()
    }

    fun load(accountId: Long): QuarkCredentials? {
        val p = PREFIX + accountId + "_"
        val cookie = QuarkCredentialCrypto.decryptStored(prefs.getString(p + "cookie", "") ?: "")
        if (cookie.isEmpty()) return null
        return QuarkCredentials(
            cookie = cookie,
            kps = QuarkCredentialCrypto.decryptStored(prefs.getString(p + "kps", "") ?: ""),
            sign = QuarkCredentialCrypto.decryptStored(prefs.getString(p + "sign", "") ?: ""),
            vcode = QuarkCredentialCrypto.decryptStored(prefs.getString(p + "vcode", "") ?: ""),
            nickname = prefs.getString(p + "nickname", "") ?: "",
            loginAt = prefs.getLong(p + "login_at", 0L)
        )
    }

    fun remove(accountId: Long) {
        val p = PREFIX + accountId + "_"
        val keys = listOf("cookie", "kps", "sign", "vcode", "nickname", "login_at")
        val editor = prefs.edit()
        keys.forEach { editor.remove(p + it) }
        editor.apply()
    }

    fun isValid(accountId: Long): Boolean {
        val creds = load(accountId) ?: return false
        if (creds.cookie.isEmpty()) return false
        val maxAgeMs = TimeUnit.DAYS.toMillis(QuarkConstants.SESSION_VALID_DAYS)
        return System.currentTimeMillis() - creds.loginAt < maxAgeMs
    }
}