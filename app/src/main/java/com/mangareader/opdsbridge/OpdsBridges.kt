package com.mangareader.opdsbridge

import android.content.Context
import com.mangareader.data.MangaDatabase

object OpdsBridges {
    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var db: MangaDatabase? = null

    fun init(context: Context, database: MangaDatabase) {
        appCtx = context.applicationContext
        db = database
    }

    val context: Context get() = appCtx ?: throw IllegalStateException("OpdsBridges not initialized")
    val database: MangaDatabase get() = db ?: throw IllegalStateException("OpdsBridges not initialized")
}