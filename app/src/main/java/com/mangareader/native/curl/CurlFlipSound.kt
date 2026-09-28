package com.mangareader.native.curl

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/**
 * 落页纸声：单页/双页 curl 共用。设置页「翻页纸声」可关（读实时 pref 免重建视图）。
 * 每次播放音调 ±12% 微随机，避免连续翻页时机器重复感。
 */
class CurlFlipSound(private val context: Context) {

    private var pool: SoundPool? = null
    private var soundId = 0

    fun play() {
        val on = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
            .getBoolean("page_curl_sound", true)
        if (!on) return
        val p = pool ?: SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            .build()
            .also { pool = it }
        if (soundId == 0) {
            soundId = try {
                p.load(context.assets.openFd("sounds/page_flip.wav"), 1)
            } catch (_: Exception) { 0 }
        }
        if (soundId != 0) p.play(soundId, 0.6f, 0.6f, 1, 0, 0.88f + Random.nextFloat() * 0.24f)
    }

    fun release() {
        pool?.release()
        pool = null
        soundId = 0
    }
}
