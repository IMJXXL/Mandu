package com.mangareader.mobi

import java.io.RandomAccessFile

/** 内存字节源（单测/小数据通用）。 */
class ByteArraySeekable(private val data: ByteArray) : SeekableSource {
    override val size: Long get() = data.size.toLong()

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (offset < 0 || offset >= data.size || len <= 0) return -1
        val n = minOf(len.toLong(), data.size - offset).toInt()
        if (n <= 0) return -1
        System.arraycopy(data, offset.toInt(), buf, off, n)
        return n
    }

    override fun close() {}
}

/** 本地文件随机读源（close 会关闭底层 RandomAccessFile，调用方用 use 持有）。 */
class RafSeekable(private val raf: RandomAccessFile) : SeekableSource {
    override val size: Long get() = raf.length()

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return -1
        raf.seek(offset)
        return raf.read(buf, off, len)
    }

    override fun close() = raf.close()
}

/**
 * SAF/ParcelFileDescriptor 随机读源（android.system.Os.lseek）。
 * provider 返回管道 fd（不可 seek）时抛 ErrnoException → 调用方回退既有路径。
 * 不拥有 fd（close 为空），pfd 生命周期归调用方 use{}。
 */
class FdSeekable(private val fd: java.io.FileDescriptor) : SeekableSource {
    override val size: Long
        get() = android.system.Os.lseek(fd, 0L, android.system.OsConstants.SEEK_END)

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return -1
        android.system.Os.lseek(fd, offset, android.system.OsConstants.SEEK_SET)
        return android.system.Os.read(fd, buf, off, len)
    }

    override fun close() {}
}
