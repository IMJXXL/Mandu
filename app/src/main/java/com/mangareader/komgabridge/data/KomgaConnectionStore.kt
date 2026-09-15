package com.mangareader.komgabridge.data

import android.content.Context
import android.util.Log
import com.mangareader.komgabridge.KomgaConstants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KomgaConnectionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        KomgaConstants.SHARED_PREFS_NAME, Context.MODE_PRIVATE
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun connections(): List<KomgaConnection> {
        val raw = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        return try {
            val list = json.decodeFromString<List<KomgaConnection>>(raw)
            list.map { it.copy(
                apiKey = KomgaCredentialCrypto.decryptStored(it.apiKey),
                username = KomgaCredentialCrypto.decryptStored(it.username),
                password = KomgaCredentialCrypto.decryptStored(it.password),
            )}
        } catch (t: Throwable) {
            Log.w(KomgaConstants.LOG_TAG, "connections parse failed", t)
            emptyList()
        }
    }

    fun connection(): KomgaConnection? {
        val activeId = prefs.getString(KEY_ACTIVE_ID, null) ?: return connections().firstOrNull()
        return connections().find { it.id == activeId }
    }

    fun getConnection(id: String): KomgaConnection? = connections().find { it.id == id }

    fun saveConnection(conn: KomgaConnection) {
        if (!conn.baseUrl.startsWith("http://") && !conn.baseUrl.startsWith("https://")) {
            throw IllegalArgumentException("URL must start with http:// or https://")
        }
        val encrypted = conn.copy(
            apiKey = KomgaCredentialCrypto.encrypt(conn.apiKey),
            username = KomgaCredentialCrypto.encrypt(conn.username),
            password = KomgaCredentialCrypto.encrypt(conn.password),
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

    fun getRawConnections(): String? = prefs.getString(KEY_CONNECTIONS, null)

    fun setRawConnections(rawJson: String) {
        prefs.edit().putString(KEY_CONNECTIONS, rawJson).apply()
    }

    fun getRawActiveConnectionId(): String? = prefs.getString(KEY_ACTIVE_ID, null)

    fun setRawActiveConnectionId(id: String?) {
        if (id != null) prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        else prefs.edit().remove(KEY_ACTIVE_ID).apply()
    }

    companion object {
        private const val KEY_CONNECTIONS = "connections"
        private const val KEY_ACTIVE_ID = "active_connection_id"
    }
}