package com.mangareader.panel

import android.graphics.Bitmap
import android.util.LruCache

internal object PanelFoldJoin {

    internal object Cache {
        private const val MAX_BOOKS = 4
        private const val MAX_PAGES_PER_BOOK = 32
        private val books = object : LruCache<String, BookCache>(MAX_BOOKS) {
            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: BookCache,
                newValue: BookCache?,
            ) {
                oldValue.evictAll()
            }
        }

        fun get(treePath: String, page: Int): Bitmap? = books[treePath]?.get(page)

        fun put(treePath: String, page: Int, bmp: Bitmap) {
            val bc = books[treePath] ?: BookCache().also { books.put(treePath, it) }
            bc.put(page, bmp)
        }

        fun clearBook(treePath: String) {
            books.remove(treePath)
        }
    }

    private class BookCache : LruCache<Int, Bitmap>(32) {
        override fun entryRemoved(
            evicted: Boolean,
            key: Int,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (!oldValue.isRecycled) oldValue.recycle()
        }
    }
}