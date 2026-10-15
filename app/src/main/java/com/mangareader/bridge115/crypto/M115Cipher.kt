package com.mangareader.bridge115.crypto

import android.util.Base64
import com.mangareader.bridge115.api.Cipher115Exception
import java.math.BigInteger
import java.security.SecureRandom

object M115Cipher {

    private val M115_N = BigInteger(
        "8686980c0f5a24c4b9d43020cd2c22703ff3f450756529058b1cf88f09b86021" +
        "36477198a6e2683149659bd122c33592fdb5ad47944ad1ea4d36c6b172aad633" +
        "8c3bb6ac6227502d010993ac967d1aef00f0c8e038de2e4d3bc2ec368af2e9f1" +
        "0a6f1eda4f7262f136420c07c331b871bf139f74f3010e3c4fe57df3afb71683",
        16
    )

    private val M115_E = BigInteger.valueOf(0x10001)

    private val M115_XOR_KEY_SEED = byteArrayOf(
        0xf0.toByte(), 0xe5.toByte(), 0x69.toByte(), 0xae.toByte(), 0xbf.toByte(), 0xdc.toByte(), 0xbf.toByte(), 0x8a.toByte(),
        0x1a.toByte(), 0x45.toByte(), 0xe8.toByte(), 0xbe.toByte(), 0x7d.toByte(), 0xa6.toByte(), 0x73.toByte(), 0xb8.toByte(),
        0xde.toByte(), 0x8f.toByte(), 0xe7.toByte(), 0xc4.toByte(), 0x45.toByte(), 0xda.toByte(), 0x86.toByte(), 0xc4.toByte(),
        0x9b.toByte(), 0x64.toByte(), 0x8b.toByte(), 0x14.toByte(), 0x6a.toByte(), 0xb4.toByte(), 0xf1.toByte(), 0xaa.toByte(),
        0x38.toByte(), 0x01.toByte(), 0x35.toByte(), 0x9e.toByte(), 0x26.toByte(), 0x69.toByte(), 0x2c.toByte(), 0x86.toByte(), 0x00.toByte(), 0x6b.toByte(), 0x4f.toByte(), 0xa5.toByte(), 0x36.toByte(), 0x34.toByte(), 0x62.toByte(), 0xa6.toByte(),
        0x2a.toByte(), 0x96.toByte(), 0x68.toByte(), 0x18.toByte(), 0xf2.toByte(), 0x4a.toByte(), 0xfd.toByte(), 0xbd.toByte(), 0x6b.toByte(), 0x97.toByte(), 0x8f.toByte(), 0x4d.toByte(), 0x8f.toByte(), 0x89.toByte(), 0x13.toByte(), 0xb7.toByte(),
        0x6c.toByte(), 0x8e.toByte(), 0x93.toByte(), 0xed.toByte(), 0x0e.toByte(), 0x0d.toByte(), 0x48.toByte(), 0x3e.toByte(), 0xd7.toByte(), 0x2f.toByte(), 0x88.toByte(), 0xd8.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0x7e.toByte(), 0x86.toByte(),
        0x50.toByte(), 0x95.toByte(), 0x4f.toByte(), 0xd1.toByte(), 0xeb.toByte(), 0x83.toByte(), 0x26.toByte(), 0x34.toByte(), 0xdb.toByte(), 0x66.toByte(), 0x7b.toByte(), 0x9c.toByte(), 0x7e.toByte(), 0x9d.toByte(), 0x7a.toByte(), 0x81.toByte(),
        0x32.toByte(), 0xea.toByte(), 0xb6.toByte(), 0x33.toByte(), 0xde.toByte(), 0x3a.toByte(), 0xa9.toByte(), 0x59.toByte(), 0x34.toByte(), 0x66.toByte(), 0x3b.toByte(), 0xaa.toByte(), 0xba.toByte(), 0x81.toByte(), 0x60.toByte(), 0x48.toByte(),
        0xb9.toByte(), 0xd5.toByte(), 0x81.toByte(), 0x9c.toByte(), 0xf8.toByte(), 0x6c.toByte(), 0x84.toByte(), 0x77.toByte(), 0xff.toByte(), 0x54.toByte(), 0x78.toByte(), 0x26.toByte(), 0x5f.toByte(), 0xbe.toByte(), 0xe8.toByte(), 0x1e.toByte(),
        0x36.toByte(), 0x9f.toByte(), 0x34.toByte(), 0x80.toByte(), 0x5c.toByte(), 0x45.toByte(), 0x2c.toByte(), 0x9b.toByte(), 0x76.toByte(), 0xd5.toByte(), 0x1b.toByte(), 0x8f.toByte(), 0xcc.toByte(), 0xc3.toByte(), 0xb8.toByte(), 0xf5.toByte(),
    )

    private val M115_XOR_CLIENT_KEY = byteArrayOf(
        0x78.toByte(), 0x06.toByte(), 0xad.toByte(), 0x4c.toByte(), 0x33.toByte(), 0x86.toByte(), 0x5d.toByte(), 0x18.toByte(), 0x4c.toByte(), 0x01.toByte(), 0x3f.toByte(), 0x46.toByte(),
    )

    private val M115_FIXED_KEY = byteArrayOf(0x8d.toByte(), 0xa5.toByte(), 0xa5.toByte(), 0x8d.toByte())

    private const val KEY_LEN = 128
    private const val SLICE_SIZE = 117 // KEY_LEN - 11
    private val random = SecureRandom()

    fun encode(json: String): String {
        val body = json.toByteArray(Charsets.UTF_8).copyOf()
        xorTransform(body, M115_FIXED_KEY)
        body.reverse()
        xorTransform(body, M115_XOR_CLIENT_KEY)

        val buf = ByteArray(16 + body.size)
        System.arraycopy(body, 0, buf, 16, body.size)

        val encrypted = rsaEncrypt(buf)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decode(base64: String): ByteArray {
        val data = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw Cipher115Exception("直链响应解密失败")
        }
        val raw = rsaTransform(data)
        if (raw.size <= 16) {
            throw Cipher115Exception("直链响应解密失败（数据过短）")
        }
        val k = raw.copyOfRange(0, 16)
        val out = raw.copyOfRange(16, raw.size)
        xorTransform(out, deriveKey(k, 12))
        out.reverse()
        xorTransform(out, M115_FIXED_KEY)
        return out
    }

    private fun deriveKey(seed: ByteArray, size: Int): ByteArray {
        val key = ByteArray(size)
        for (i in 0 until size) {
            key[i] = (seed[i].toInt() and 0xFF).let { s ->
                val v = (s + (M115_XOR_KEY_SEED[size * i].toInt() and 0xFF)) and 0xFF
                (v xor (M115_XOR_KEY_SEED[size * (size - i - 1)].toInt() and 0xFF)).toByte()
            }
        }
        return key
    }

    private fun xorTransform(data: ByteArray, key: ByteArray) {
        val dataSize = data.size
        val keySize = key.size
        val m = dataSize % 4
        if (m > 0) {
            for (i in 0 until m) {
                data[i] = (data[i].toInt() xor key[i % keySize].toInt()).toByte()
            }
        }
        for (i in m until dataSize) {
            data[i] = (data[i].toInt() xor key[(i - m) % keySize].toInt()).toByte()
        }
    }

    private fun rsaEncrypt(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val sliceLen = minOf(SLICE_SIZE, input.size - offset)
            val slice = input.copyOfRange(offset, offset + sliceLen)
            offset += sliceLen

            val padSize = KEY_LEN - slice.size - 3
            val msg = ByteArray(KEY_LEN)
            msg[0] = 0
            msg[1] = 2
            for (i in 0 until padSize) {
                msg[2 + i] = (random.nextInt(0xFF) + 1).toByte()
            }
            msg[2 + padSize] = 0
            System.arraycopy(slice, 0, msg, padSize + 3, slice.size)

            val m = BigInteger(1, msg)
            val c = m.modPow(M115_E, M115_N)
            val bytes = c.toByteArray()
            val stripped = if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

            for (i in 0 until KEY_LEN - stripped.size) {
                out.write(0)
            }
            out.write(stripped)
        }
        return out.toByteArray()
    }

    private fun rsaTransform(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val chunkLen = minOf(KEY_LEN, input.size - offset)
            val chunk = input.copyOfRange(offset, offset + chunkLen)
            offset += chunkLen

            val m = BigInteger(1, chunk)
            val ret = m.modPow(M115_E, M115_N).toByteArray()
            val stripped = if (ret.isNotEmpty() && ret[0] == 0.toByte()) ret.copyOfRange(1, ret.size) else ret

            for (i in stripped.indices) {
                if (stripped[i] == 0.toByte() && i != 0) {
                    out.write(stripped, i + 1, stripped.size - i - 1)
                    break
                }
            }
        }
        return out.toByteArray()
    }
}