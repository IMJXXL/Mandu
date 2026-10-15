package com.mangareader.cloud

import android.content.Context
import com.mangareader.baidubridge.auth.BaiduSessionStore
import com.mangareader.bridge115.auth.ModeStore115
import com.mangareader.bridge115.auth.SessionStore115
import com.mangareader.quarkbridge.auth.QuarkSessionStore
import splitties.init.appCtx

object CloudAccountMigrator {
    private const val PREFS = "cloud_account_migration"
    private const val KEY_MIGRATED = "cloud_account_migrated"

    private val prefs by lazy {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    suspend fun ensureMigrated() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        if (CloudAccountRepository.countByPanType("夸克") > 0 ||
            CloudAccountRepository.countByPanType("百度") > 0 ||
            CloudAccountRepository.countByPanType("115") > 0
        ) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }

        val ctx = appCtx

        val quarkStore = QuarkSessionStore(ctx)
        if (quarkStore.isValid()) {
            val creds = quarkStore.load()
            if (creds != null) {
                CloudAccountRepository.addQuarkAccount(creds.nickname.ifBlank { "夸克账号" }, creds)
            }
        }

        val baiduStore = BaiduSessionStore(ctx)
        if (baiduStore.isValid()) {
            val session = baiduStore.load()
            if (session != null) {
                CloudAccountRepository.addBaiduAccount(session.nickname.ifBlank { "百度账号" }, session)
            }
        }

        val session115 = SessionStore115(ctx)
        val mode115 = ModeStore115(ctx)
        if (mode115.hasMode()) {
            val mode = mode115.getMode()!!
            if (mode == "official" && session115.isValidOfficial()) {
                val official = session115.loadOfficial()
                if (official != null) {
                    CloudAccountRepository.add115Account(
                        official.nickname.ifBlank { "115账号" },
                        mode = "official",
                        official = official
                    )
                }
            } else if (mode == "web" && session115.isValidWeb()) {
                val web = session115.loadWeb()
                if (web != null) {
                    CloudAccountRepository.add115Account(
                        "115账号",
                        mode = "web",
                        web = web
                    )
                }
            }
        }

        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }
}