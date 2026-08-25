package com.mangareader.viewer

import android.graphics.Bitmap
import coil.map.Mapper
import coil.request.Options

class CoverMapper(private val engine: ArchiveEngine) : Mapper<String, Bitmap> {
    override fun map(data: String, options: Options): Bitmap? {
        if (!data.startsWith("cv:")) return null
        return try {
            engine.loadCover(data)
        } catch (e: Exception) {
            null
        }
    }
}
