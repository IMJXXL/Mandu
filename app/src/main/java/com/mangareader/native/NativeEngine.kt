package com.mangareader.native

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import dalvik.annotation.optimization.FastNative

object NativeEngine {
    init {
        System.loadLibrary("native_renderer")
        System.loadLibrary("native_engine")
        System.loadLibrary("native_reader")
        try { System.loadLibrary("archive-jni") } catch (_: Throwable) {}
    }

    private external fun nativeOpenArchive(path: String): Long
    private external fun nativeCloseArchive(handle: Long)
    private external fun nativeGetPageCount(handle: Long): Int
    private external fun nativeGetPageInfo(handle: Long, pageIndex: Int): IntArray?
    private external fun nativeReadPageRaw(handle: Long, pageIndex: Int): ByteArray?
    private external fun nativeHasCachedPage(handle: Long, pageIndex: Int): Boolean
    private external fun nativeSubmitPredecode(handle: Long, pageIndex: Int, maxW: Int, maxH: Int)
    private external fun nativeWaitForPredecodes()
    private external fun nativeCancelPredecodes()
    private external fun nativeSetCacheSize(maxPages: Int)
    private external fun nativeClearCache()
    private external fun nativeCacheDecodedBitmap(handle: Long, pageIndex: Int, bitmap: Bitmap): Boolean
    private external fun nativeCreateBitmapFromCache(handle: Long, pageIndex: Int): Bitmap?
    private external fun nativeReadAndDecodeToBitmap(handle: Long, pageIndex: Int, maxWidth: Int, maxHeight: Int): Bitmap?

    class Archive(val handle: Long, val path: String) {
        val pageCount: Int get() = nativeGetPageCount(handle)
        fun close() = nativeCloseArchive(handle)
    }

    fun openArchive(path: String): Archive? {
        val handle = nativeOpenArchive(path)
        return if (handle > 0) Archive(handle, path) else null
    }

    fun decodeRaw(raw: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            var sampleSize = 1
            while (opts.outWidth / sampleSize > maxWidth || opts.outHeight / sampleSize > maxHeight) sampleSize *= 2
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply {
                inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return null
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
            } else bmp
        } catch (e: Exception) {
            Log.e("NativeEngine", "Decode failed: ${e.message}")
            null
        }
    }

    // Zero-copy: read ZIP entry + decode to Bitmap in one step (no Java byte[] middleman)
    fun readAndDecodeToBitmap(archive: Archive, pageIndex: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        return nativeReadAndDecodeToBitmap(archive.handle, pageIndex, maxWidth, maxHeight)
    }

    fun submitPredecode(archive: Archive, pageIndex: Int, maxWidth: Int, maxHeight: Int) {
        nativeSubmitPredecode(archive.handle, pageIndex, maxWidth, maxHeight)
    }

    fun readPageRaw(archive: Archive, pageIndex: Int): ByteArray? {
        return nativeReadPageRaw(archive.handle, pageIndex)
    }

    fun hasCachedPage(archive: Archive, pageIndex: Int): Boolean {
        return nativeHasCachedPage(archive.handle, pageIndex)
    }

    fun waitForPredecodes() = nativeWaitForPredecodes()
    fun cancelPredecodes() = nativeCancelPredecodes()
    fun setCacheSize(maxPages: Int) = nativeSetCacheSize(maxPages)
    fun clearCache() = nativeClearCache()

    fun cacheDecodedBitmap(archive: Archive, pageIndex: Int, bitmap: Bitmap): Boolean {
        return nativeCacheDecodedBitmap(archive.handle, pageIndex, bitmap)
    }

    fun createBitmapFromCache(archive: Archive, pageIndex: Int): Bitmap? {
        return nativeCreateBitmapFromCache(archive.handle, pageIndex)
    }

    fun readMobiCover(path: String): ByteArray? = nativeReadMobiCover(path)
    fun countMobiPages(path: String): Int = nativeCountMobiPages(path)
    fun readMobiPage(path: String, pageIndex: Int): ByteArray? = nativeReadMobiPage(path, pageIndex)
    fun openMobiWithFd(path: String, fd: Int) = nativeOpenMobiWithFd(path, fd)
    fun closeMobiHandle(path: String) = nativeCloseMobiHandle(path)
    // Combined: returns [4 bytes pageCount | coverData bytes]
    fun getMobiInfo(path: String): Pair<Int, ByteArray?>? {
        val data = nativeGetMobiInfo(path) ?: return null
        if (data.size < 4) return null
        val pageCount = (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 24)
        val coverData = if (data.size > 4) data.copyOfRange(4, data.size) else null
        return pageCount to coverData
    }
    fun clearMobiCache() = nativeClearMobiCache()
    fun readArchiveEntry(path: String, targetIndex: Int, extension: String): ByteArray? = nativeReadArchiveEntry(path, targetIndex, extension)
    fun countArchiveEntries(path: String): Int = nativeCountArchiveEntries(path)

    @FastNative private external fun nativeReadMobiCover(path: String): ByteArray?
    @FastNative private external fun nativeCountMobiPages(path: String): Int
    @FastNative private external fun nativeReadMobiPage(path: String, pageIndex: Int): ByteArray?
    @FastNative private external fun nativeOpenMobiWithFd(path: String, fd: Int)
    @FastNative private external fun nativeCloseMobiHandle(path: String)
    @FastNative private external fun nativeGetMobiInfo(path: String): ByteArray?
    private external fun nativeClearMobiCache()
    private external fun nativeReadArchiveEntry(path: String, targetIndex: Int, extension: String): ByteArray?
    private external fun nativeCountArchiveEntries(path: String): Int

    // pdfium PDF API
    fun pdfOpen(key: String, fd: Int): Boolean = nativePdfOpen(key, fd)
    fun pdfClose(key: String) = nativePdfClose(key)
    fun pdfIsOpen(key: String): Boolean = nativePdfIsOpen(key)
    fun pdfGetPageCount(key: String): Int = nativePdfGetPageCount(key)
    fun pdfOpenPage(key: String, pageIndex: Int): Boolean = nativePdfOpenPage(key, pageIndex)
    fun pdfClosePage(key: String) = nativePdfClosePage(key)
    fun pdfGetPageSize(key: String, pageIndex: Int): IntArray? = nativePdfGetPageSize(key, pageIndex)
    fun pdfUnlockDoc(key: String, pwd: String): Boolean = nativePdfUnlockDoc(key, pwd)
    fun pdfRenderPage(key: String, pageIndex: Int, width: Int, height: Int, dpi: Int, isArgb: Boolean): ByteArray? = nativePdfRenderPage(key, pageIndex, width, height, dpi, isArgb)
    fun pdfRenderPageBitmap(key: String, pageIndex: Int, width: Int, height: Int, dpi: Int): Bitmap? = nativePdfRenderPageBitmap(key, pageIndex, width, height, dpi)
    fun pdfCloseAll() = nativePdfCloseAll()

    private external fun nativePdfOpen(key: String, fd: Int): Boolean
    private external fun nativePdfClose(key: String)
    private external fun nativePdfIsOpen(key: String): Boolean
    private external fun nativePdfGetPageCount(key: String): Int
    private external fun nativePdfOpenPage(key: String, pageIndex: Int): Boolean
    private external fun nativePdfClosePage(key: String)
    private external fun nativePdfGetPageSize(key: String, pageIndex: Int): IntArray?
    private external fun nativePdfUnlockDoc(key: String, pwd: String): Boolean
    private external fun nativePdfRenderPage(key: String, pageIndex: Int, width: Int, height: Int, dpi: Int, isArgb: Boolean): ByteArray?
    private external fun nativePdfRenderPageBitmap(key: String, pageIndex: Int, width: Int, height: Int, dpi: Int): Bitmap?
    private external fun nativePdfCloseAll()

    // libzip operations (for EPUB/ZIP with garbled filenames)
    fun openZipWithFd(path: String, fd: Int, realPath: String = "") = nativeOpenZipWithFd(path, fd, realPath)
    fun readZipEntry(path: String, index: Int): ByteArray? = nativeReadZipEntry(path, index)
    fun getZipEntryCount(path: String): Int = nativeGetZipEntryCount(path)
    fun findZipImageEntry(path: String, targetIndex: Int): Int = nativeFindZipImageEntry(path, targetIndex)
    fun closeZip(path: String) = nativeCloseZip(path)
    fun isOpenZip(path: String): Boolean = nativeIsOpenZip(path)
    fun getZipEntryNames(path: String): Array<String> = nativeGetZipEntryNames(path)
    fun findZipEntryByName(path: String, name: String): Int = nativeFindZipEntryByName(path, name)
    fun readArchiveEntryFd(fd: Int, targetIndex: Int): ByteArray? = nativeReadArchiveEntryFd(fd, targetIndex)
    fun countArchiveEntriesFd(fd: Int): Int = nativeCountArchiveEntriesFd(fd)

    private external fun nativeOpenZipWithFd(path: String, fd: Int, realPath: String)
    @FastNative private external fun nativeReadZipEntry(path: String, index: Int): ByteArray?
    private external fun nativeGetZipEntryCount(path: String): Int
    @FastNative private external fun nativeFindZipImageEntry(path: String, targetIndex: Int): Int
    private external fun nativeCloseZip(path: String)
    private external fun nativeIsOpenZip(path: String): Boolean
    @FastNative private external fun nativeGetZipEntryNames(path: String): Array<String>
    @FastNative private external fun nativeFindZipEntryByName(path: String, name: String): Int
    private external fun nativeReadArchiveEntryFd(fd: Int, targetIndex: Int): ByteArray?
    private external fun nativeCountArchiveEntriesFd(fd: Int): Int
    external fun nativeForceExit(code: Int)
}
