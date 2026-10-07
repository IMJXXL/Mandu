package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 固定四边裁剪（2026-10-04，仅本书功能）：给单本书记四个边的裁剪比例（相对原图宽高，
 * 各 0~0.40），解码每页时裁掉四边再铺满显示——画面里不存在"放大态"，翻页手势不受影响。
 *
 * ★ 刻意不走 ReaderScope 作用域系统：它是书的数据（同阅读进度一类），不是阅读偏好——
 *   不进偏好页、不落全局键、不进会话桶、不受「本书改动单独记」总开关影响，永远按书记住。
 *   bookId<=0（未入库临时打开）不持久化，偏好页「恢复默认」也不清除它。
 */
data class BookCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val active: Boolean
        get() = left > 0.001f || top > 0.001f || right > 0.001f || bottom > 0.001f

    companion object {
        val OFF = BookCrop()
        /** 单边上限：两对边合计最多 80%，保证至少留 20% 画面（防误操作裁没） */
        const val MAX = 0.40f
    }
}

object BookCropStore {

    private const val PREFS = "manga_reader"

    fun load(prefs: SharedPreferences, bookId: Long): BookCrop {
        // ★ stableBookId 路径哈希可正可负（Long 符号位与入库无关），仅 0=无身份
        if (bookId == 0L) return BookCrop.OFF
        fun f(suffix: String): Float =
            try { prefs.getFloat("book_${bookId}_$suffix", 0f) } catch (_: Exception) { 0f }
        val c = BookCrop(f("cropL"), f("cropT"), f("cropR"), f("cropB"))
        return if (c.active) c else BookCrop.OFF
    }

    fun load(ctx: Context, bookId: Long): BookCrop =
        load(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE), bookId)

    fun save(ctx: Context, bookId: Long, crop: BookCrop) {
        if (bookId == 0L) return
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val p = "book_${bookId}_"
        e.putFloat("${p}cropL", crop.left.coerceIn(0f, BookCrop.MAX))
        e.putFloat("${p}cropT", crop.top.coerceIn(0f, BookCrop.MAX))
        e.putFloat("${p}cropR", crop.right.coerceIn(0f, BookCrop.MAX))
        e.putFloat("${p}cropB", crop.bottom.coerceIn(0f, BookCrop.MAX))
        e.apply()
    }

    fun clear(ctx: Context, bookId: Long) = save(ctx, bookId, BookCrop.OFF)
}
