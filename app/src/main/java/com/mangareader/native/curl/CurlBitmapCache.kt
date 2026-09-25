package com.mangareader.native.curl

import android.graphics.Bitmap
import android.util.LruCache

class CurlBitmapCache(maxSize: Int = 12) {
    private val lru = object : LruCache<Int, Bitmap>(maxSize) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }
    private var protectedSet: MutableSet<Int> = mutableSetOf()

    fun get(idx: Int): Bitmap? = lru.get(idx)

    fun put(idx: Int, bmp: Bitmap) {
        if (idx in protectedSet) lru.remove(idx)
        lru.put(idx, bmp)
    }

    fun remove(idx: Int) {
        lru.remove(idx)
    }

    fun protectThreeLayers(center: Int) {
        protectedSet = mutableSetOf(center - 1, center, center + 1)
    }

    /** 双页路径：按 spread 页集合设置保护集（trimNonProtected 只清保护集之外的位图） */
    fun protectPages(pages: Set<Int>) {
        protectedSet = pages.toMutableSet()
    }

    fun drainAll(): Map<Int, Bitmap> {
        val out = LinkedHashMap<Int, Bitmap>()
        for (k in lru.snapshot().keys) {
            val v = lru.remove(k) ?: continue
            if (!v.isRecycled) out[k] = v
        }

        return out
    }

    fun adopt(map: Map<Int, Bitmap>) {
        for ((k, v) in map) if (!v.isRecycled) lru.put(k, v)
    }

    fun trimNonProtected(level: Int) {
        val snapshot = lru.snapshot()
        for (k in snapshot.keys) {
            if (k !in protectedSet) lru.remove(k)
        }

    }

    fun size(): Int = lru.size()
}