package com.mangareader.baidubridge.auth

import android.content.Context
import splitties.init.appCtx

object BaiduCredentialMultiStore {
    private const val PREFS = "baidu_multi_creds"
    private const val PREFIX = "acc_"

    private val prefs by lazy {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun save(accountId: Long, session: BaiduSession) {
        val p = PREFIX + accountId + "_"
        prefs.edit()
            .putString(p + "app_key", BaiduCredentialCrypto.encrypt(session.appKey))
            .putString(p + "secret_key", BaiduCredentialCrypto.encrypt(session.secretKey))
            .putString(p + "access_token", BaiduCredentialCrypto.encrypt(session.accessToken))
            .putString(p + "refresh_token", BaiduCredentialCrypto.encrypt(session.refreshToken))
            .putLong(p + "expires_at", session.expiresAt)
            .putString(p + "nickname", session.nickname)
            .putLong(p + "login_at", session.loginAt)
            .apply()
    }

    fun load(accountId: Long): BaiduSession? {
        val p = PREFIX + accountId + "_"
        val accessToken = BaiduCredentialCrypto.decryptStored(prefs.getString(p + "access_token", "") ?: "")
        if (accessToken.isEmpty()) return null
        return BaiduSession(
            appKey = BaiduCredentialCrypto.decryptStored(prefs.getString(p + "app_key", "") ?: ""),
            secretKey = BaiduCredentialCrypto.decryptStored(prefs.getString(p + "secret_key", "") ?: ""),
            accessToken = accessToken,
            refreshToken = BaiduCredentialCrypto.decryptStored(prefs.getString(p + "refresh_token", "") ?: ""),
            expiresAt = prefs.getLong(p + "expires_at", 0L),
            nickname = prefs.getString(p + "nickname", "") ?: "",
            loginAt = prefs.getLong(p + "login_at", 0L)
        )
    }

    fun remove(accountId: Long) {
        val p = PREFIX + accountId + "_"
        val keys = listOf("app_key", "secret_key", "access_token", "refresh_token", "expires_at", "nickname", "login_at")
        val editor = prefs.edit()
        keys.forEach { editor.remove(p + it) }
        editor.apply()
    }

    fun isValid(accountId: Long): Boolean {
        val session = load(accountId) ?: return false
        return session.accessToken.isNotEmpty() && session.refreshToken.isNotEmpty()
    }
}