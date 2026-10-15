package com.mangareader.cloud

import com.ehviewer.core.database.model.CloudAccountEntity
import com.ehviewer.core.util.withIOContext
import com.mangareader.baidubridge.auth.BaiduCredentialMultiStore
import com.mangareader.baidubridge.auth.BaiduSession
import com.mangareader.baidubridge.auth.BaiduSessionStore
import com.mangareader.bridge115.auth.Credential115MultiStore
import com.mangareader.bridge115.auth.ModeStore115
import com.mangareader.bridge115.auth.Session115Official
import com.mangareader.bridge115.auth.Session115Web
import com.mangareader.bridge115.auth.SessionStore115
import com.mangareader.data.MangaDatabase
import com.mangareader.quarkbridge.auth.QuarkCredentialMultiStore
import com.mangareader.quarkbridge.auth.QuarkCredentials
import com.mangareader.quarkbridge.auth.QuarkSessionStore
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import splitties.init.appCtx

object CloudAccountRepository {
    private val dao get() = MangaDatabase.getDatabase(appCtx).cloudAccountDao()

    fun listFlow(panType: String): Flow<List<CloudAccountEntity>> = dao.listFlowByPanType(panType)

    suspend fun load(id: Long): CloudAccountEntity? = dao.load(id)

    suspend fun addQuarkAccount(displayName: String, credentials: QuarkCredentials): Long = withIOContext {
        val now = Clock.System.now().toEpochMilliseconds()
        val count = dao.countByPanType("夸克")
        val id = dao.insert(
            CloudAccountEntity(
                panType = "夸克",
                displayName = displayName.ifBlank { credentials.nickname.ifBlank { "夸克账号" } },
                isActive = count == 0,
                addedAt = now,
            )
        )
        QuarkCredentialMultiStore.save(id, credentials)
        id
    }

    suspend fun addBaiduAccount(displayName: String, session: BaiduSession): Long = withIOContext {
        val now = Clock.System.now().toEpochMilliseconds()
        val count = dao.countByPanType("百度")
        val id = dao.insert(
            CloudAccountEntity(
                panType = "百度",
                displayName = displayName.ifBlank { session.nickname.ifBlank { "百度账号" } },
                isActive = count == 0,
                addedAt = now,
            )
        )
        BaiduCredentialMultiStore.save(id, session)
        id
    }

    suspend fun add115Account(displayName: String, mode: String, official: Session115Official? = null, web: Session115Web? = null): Long = withIOContext {
        val now = Clock.System.now().toEpochMilliseconds()
        val count = dao.countByPanType("115")
        val nick = if (mode == "official") official?.nickname ?: "115账号" else "115账号"
        val id = dao.insert(
            CloudAccountEntity(
                panType = "115",
                displayName = displayName.ifBlank { nick },
                isActive = count == 0,
                mode115 = mode,
                addedAt = now,
            )
        )
        if (official != null) Credential115MultiStore.saveOfficial(id, official)
        if (web != null) Credential115MultiStore.saveWeb(id, web)
        id
    }

    suspend fun updateDisplayName(id: Long, newName: String) = withIOContext {
        val entity = dao.load(id) ?: return@withIOContext
        dao.update(entity.copy(displayName = newName))
    }

    suspend fun delete(account: CloudAccountEntity) = withIOContext {
        when (account.panType) {
            "夸克" -> QuarkCredentialMultiStore.remove(account.id)
            "百度" -> BaiduCredentialMultiStore.remove(account.id)
            "115" -> Credential115MultiStore.remove(account.id)
        }
        dao.delete(account)
    }

    suspend fun switchActive(panType: String, id: Long) = withIOContext {
        dao.setActive(panType, id)
    }

    suspend fun getActive(panType: String): CloudAccountEntity? = withIOContext {
        dao.getActiveByPanType(panType)
    }

    suspend fun countByPanType(panType: String): Int = withIOContext {
        dao.countByPanType(panType)
    }

    suspend fun testQuark(id: Long): Boolean = withIOContext {
        val valid = QuarkCredentialMultiStore.isValid(id)
        if (valid) markOk(id) else markError(id, "凭证已失效或过期")
        valid
    }

    suspend fun testBaidu(id: Long): Boolean = withIOContext {
        val valid = BaiduCredentialMultiStore.isValid(id)
        if (valid) markOk(id) else markError(id, "凭证已失效或过期")
        valid
    }

    suspend fun test115(id: Long): Boolean = withIOContext {
        val entity = dao.load(id) ?: return@withIOContext false
        val valid = if (entity.mode115 == "official") {
            Credential115MultiStore.isValidOfficial(id)
        } else {
            Credential115MultiStore.isValidWeb(id)
        }
        if (valid) markOk(id) else markError(id, "凭证已失效或过期")
        valid
    }

    suspend fun markOk(id: Long) = withIOContext {
        val entity = dao.load(id) ?: return@withIOContext
        dao.update(
            entity.copy(
                lastOkAt = Clock.System.now().toEpochMilliseconds(),
                lastError = null,
                loginStatus = "已登录",
            )
        )
    }

    suspend fun markError(id: Long, message: String) = withIOContext {
        val entity = dao.load(id) ?: return@withIOContext
        dao.update(entity.copy(lastError = message.take(500), loginStatus = "已失效"))
    }

    suspend fun syncQuarkToLegacy(accountId: Long) = withIOContext {
        val creds = QuarkCredentialMultiStore.load(accountId) ?: return@withIOContext
        val store = QuarkSessionStore(appCtx)
        store.save(creds)
    }

    suspend fun syncBaiduToLegacy(accountId: Long) = withIOContext {
        val session = BaiduCredentialMultiStore.load(accountId) ?: return@withIOContext
        val store = BaiduSessionStore(appCtx)
        store.save(session)
    }

    suspend fun sync115ToLegacy(accountId: Long) = withIOContext {
        val entity = dao.load(accountId) ?: return@withIOContext
        val sessionStore = SessionStore115(appCtx)
        val modeStore = ModeStore115(appCtx)
        if (entity.mode115 == "official") {
            val official = Credential115MultiStore.loadOfficial(accountId) ?: return@withIOContext
            sessionStore.saveOfficial(official)
            modeStore.setMode("official")
        } else {
            val web = Credential115MultiStore.loadWeb(accountId) ?: return@withIOContext
            sessionStore.saveWeb(web)
            modeStore.setMode("web")
        }
    }
}