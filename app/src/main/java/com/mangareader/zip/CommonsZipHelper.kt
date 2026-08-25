package com.mangareader.zip

import android.content.Context
import android.net.Uri
import android.util.Log
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.File

object CommonsZipHelper {
    private const val TAG = "CommonsZipHelper"

    fun compareNatural(a: String, b: String): Int {
        // 自然排序：数字按数值比较，其他按字符比较
        val pa = a.toCharArray()
        val pb = b.toCharArray()
        var i = 0; var j = 0
        while (i < pa.size && j < pb.size) {
            val ca = pa[i]; val cb = pb[j]
            if (ca.isDigit() && cb.isDigit()) {
                var numA = 0L; var numB = 0L
                while (i < pa.size && pa[i].isDigit()) { numA = numA * 10 + (pa[i] - '0'); i++ }
                while (j < pb.size && pb[j].isDigit()) { numB = numB * 10 + (pb[j] - '0'); j++ }
                if (numA != numB) return numA.compareTo(numB)
            } else {
                val lowA = Character.toLowerCase(ca.code); val lowB = Character.toLowerCase(cb.code)
                if (lowA != lowB) return lowA.compareTo(lowB)
                i++; j++
            }
        }
        return pa.size.compareTo(pb.size)
    }

    fun listImageEntries(context: Context, uri: Uri): List<Pair<Int, String>> {
        var results = tryCharset(context, uri, "UTF-8")
        if (results.isEmpty()) {
            results = tryCharset(context, uri, "GBK")
        }
        if (results.isEmpty()) {
            results = tryCharset(context, uri, "GB2312")
        }
        return results
    }

    private fun tryCharset(context: Context, uri: Uri, charsetName: String): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipArchiveInputStream(inputStream, charsetName, false, true).use { zipIn ->
                    var idx = 0
                    var entry: ZipArchiveEntry? = zipIn.nextZipEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageFile(entry.name)) {
                            results.add(idx to entry.name)
                            idx++
                        }
                        entry = try { zipIn.nextZipEntry } catch (_: Exception) { null }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryCharset($charsetName): ${e.message}")
        }
        results.sortWith { a, b -> compareNatural(a.second, b.second) }
        return results
    }

    fun readImageEntry(context: Context, uri: Uri, targetIndex: Int, maxBytes: Int = 10 * 1024 * 1024): ByteArray? {
        try {
            val imageEntries = mutableListOf<Pair<String, Int>>()
            var charsetIdx = 0
            while (charsetIdx < 3) {
                val charsetName = when (charsetIdx) { 0 -> "UTF-8"; 1 -> "GBK"; else -> "GB2312" }
                imageEntries.clear()
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        ZipArchiveInputStream(inputStream, charsetName, false, true).use { zipIn ->
                            var idx = 0
                            var entry: ZipArchiveEntry? = zipIn.nextZipEntry
                            while (entry != null) {
                                if (!entry.isDirectory && isImageFile(entry.name)) {
                                    imageEntries.add(entry.name to idx)
                                    idx++
                                }
                                entry = try { zipIn.nextZipEntry } catch (_: Exception) { null }
                            }
                        }
                    }
                } catch (_: Exception) {}
                if (imageEntries.isNotEmpty()) break
                charsetIdx++
            }

            if (imageEntries.isEmpty() || targetIndex >= imageEntries.size) return null
            imageEntries.sortWith { a, b -> compareNatural(a.first, b.first) }
            val targetZipIdx = imageEntries[targetIndex].second

            var readCharset = 0
            while (readCharset < 3) {
                val charsetName = when (readCharset) { 0 -> "UTF-8"; 1 -> "GBK"; else -> "GB2312" }
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        ZipArchiveInputStream(inputStream, charsetName, false, true).use { zipIn ->
                            var idx = 0
                            var entry: ZipArchiveEntry? = zipIn.nextZipEntry
                            while (entry != null) {
                                if (!entry.isDirectory && isImageFile(entry.name)) {
                                    if (idx == targetZipIdx) {
                                        if (entry.size > maxBytes) return null
                                        val buffer = java.io.ByteArrayOutputStream()
                                        val chunk = ByteArray(8192)
                                        var totalRead = 0
                                        var bytesRead: Int
                                        while (totalRead < maxBytes) {
                                            bytesRead = zipIn.read(chunk, 0, minOf(chunk.size, maxBytes - totalRead))
                                            if (bytesRead == -1) break
                                            buffer.write(chunk, 0, bytesRead)
                                            totalRead += bytesRead
                                        }
                                        return buffer.toByteArray()
                                    }
                                    idx++
                                }
                                entry = try { zipIn.nextZipEntry } catch (_: Exception) { null }
                            }
                        }
                    }
                } catch (_: Exception) {}
                readCharset++
            }
        } catch (e: Exception) {
            Log.e(TAG, "readImageEntry: ${e.message}")
        }
        return null
    }

    private fun tryReadCharset(context: Context, uri: Uri, targetIndex: Int, charsetName: String, maxBytes: Int): ByteArray? {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipArchiveInputStream(inputStream, charsetName, false, true).use { zipIn ->
                    var idx = 0
                    var entry: ZipArchiveEntry? = zipIn.nextZipEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageFile(entry.name)) {
                            if (idx == targetIndex) {
                                if (entry.size > maxBytes) return null
                                val buffer = java.io.ByteArrayOutputStream()
                                val chunk = ByteArray(8192)
                                var totalRead = 0
                                var bytesRead: Int
                                while (totalRead < maxBytes) {
                                    bytesRead = zipIn.read(chunk, 0, minOf(chunk.size, maxBytes - totalRead))
                                    if (bytesRead == -1) break
                                    buffer.write(chunk, 0, bytesRead)
                                    totalRead += bytesRead
                                }
                                return buffer.toByteArray()
                            }
                            idx++
                        }
                        entry = try { zipIn.nextZipEntry } catch (_: Exception) { null }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryReadCharset($charsetName): ${e.message}")
        }
        return null
    }

    fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                lower.endsWith(".tiff") || lower.endsWith(".tif") || lower.endsWith(".heic") ||
                lower.endsWith(".heif") || lower.endsWith(".avif")
    }

    fun isZipEncrypted(context: Context, uri: Uri): Boolean {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipArchiveInputStream(inputStream, "UTF-8", false, true).use { zipIn ->
                    var entry: ZipArchiveEntry? = zipIn.nextZipEntry
                    while (entry != null) {
                        if (entry.method == 99) return true
                        if (entry.size > 0 && entry.compressedSize == 0L) return true
                        entry = try { zipIn.nextZipEntry } catch (_: Exception) { null }
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun getZipEntryCount(context: Context, uri: Uri): Int {
        var count = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipArchiveInputStream(inputStream, "UTF-8", false, true).use { zipIn ->
                    var entry: ZipArchiveEntry? = zipIn.nextZipEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageFile(entry.name)) count++
                        entry = try { zipIn.nextZipEntry } catch (_: Exception) { null }
                    }
                }
            }
        } catch (_: Exception) {}
        return count
    }
}
