package com.mangareader.komgabridge.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mangareader.komgabridge.KomgaConstants
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

object KomgaCredentialCrypto {

    private fun getOrCreateKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getKey(KomgaConstants.KEYSTORE_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KomgaConstants.KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setKeySize(256)
                    .build()
            )
            keyGen.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = getOrCreateKey() ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = iv + encrypted
            KomgaConstants.ENCRYPT_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }

    fun decryptStored(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(KomgaConstants.ENCRYPT_PREFIX)) return stored
        val key = getOrCreateKey() ?: return ""
        return try {
            val raw = Base64.decode(stored.removePrefix(KomgaConstants.ENCRYPT_PREFIX), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 16)
            val encrypted = raw.copyOfRange(16, raw.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }
}