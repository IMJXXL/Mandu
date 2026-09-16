package com.mangareader.opdsbridge.data

import android.content.Context
import android.util.Log
import com.mangareader.opdsbridge.OpdsConstants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OpdsConnectionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        OpdsConstants.SHARED_PREFS_NAME, Context.MODE_PRIVATE
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun connections(): List<OpdsConnection> {
        val raw = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        return try {
            val list = json.decodeFromString<List<OpdsConnection>>(raw)
            list.map { it.copy(
                apiKey = OpdsCredentialCrypto.decryptStored(it.apiKey),
                username = OpdsCredentialCrypto.decryptStored(it.username),
                password = OpdsCredentialCrypto.decryptStored(it.password),
            )}
        } catch (t: Throwable) {
            Log.w(OpdsConstants.LOG_TAG, "connections parse failed", t)
            emptyList()
        }
    }

    fun connection(): OpdsConnection? {
        val activeId = prefs.getString(KEY_ACTIVE_ID, null) ?: return connections().firstOrNull()
        return connections().find { it.id == activeId }
    }

    fun getConnection(id: String): OpdsConnection? = connections().find { it.id == id }

    fun saveConnection(conn: OpdsConnection) {
        if (!conn.baseUrl.startsWith("http://") && !conn.baseUrl.startsWith("https://")) {
            throw IllegalArgumentException("URL must start with http:// or https://")
        }
        val encrypted = conn.copy(
            apiKey = OpdsCredentialCrypto.encrypt(conn.apiKey),
            username = OpdsCredentialCrypto.encrypt(conn.username),
            password = OpdsCredentialCrypto.encrypt(conn.password),
        )
        val list = connections().toMutableList()
        val idx = list.indexOfFirst { it.id == conn.id }
        if (idx >= 0) list[idx] = encrypted else list.add(encrypted)
        prefs.edit().putString(KEY_CONNECTIONS, json.encodeToString(list)).apply()
    }

    fun deleteConnection(id: String) {
        val list = connections().filterNot { it.id == id }
        prefs.edit().putString(KEY_CONNECTIONS, json.encodeToString(list)).apply()
        if (prefs.getString(KEY_ACTIVE_ID, null) == id) {
            prefs.edit().remove(KEY_ACTIVE_ID).apply()
        }
    }

    fun setActiveConnection(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    companion object {
        private const val KEY_CONNECTIONS = "connections"
        private const val KEY_ACTIVE_ID = "active_connection_id"
    }
}