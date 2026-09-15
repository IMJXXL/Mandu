package com.mangareader.backup.export

import java.security.MessageDigest

object BackupChecksumCalculator {
    fun calculate(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}