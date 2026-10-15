package com.mangareader.bridge115

import android.content.Context
import com.mangareader.data.MangaDatabase

object Bridges115 {
    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var db: MangaDatabase? = null

    fun init(context: Context, database: MangaDatabase) {
        appCtx = context.applicationContext
        db = database
    }

    val context: Context get() = appCtx!!
    val database: MangaDatabase get() = db!!
}