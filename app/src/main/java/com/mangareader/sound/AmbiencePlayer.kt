package com.mangareader.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 阅读氛围音：雨声/篝火/咖啡馆无缝循环（assets/sounds/ambience/ 下的 30s 等功率交叉淡化接缝 WAV）。
 *
 * 用 SoundPool 循环（loop=-1）：一次只解码所选一段（22.05kHz 单声道 WAV ≈1.3MB PCM 常驻），
 * 换音色即 unload 旧的。track=0 表示关闭。退出阅读器 [release] 归还内存。
 * WAV 而非 OGG：SoundPool 循环 OGG 有编码填充间隙风险，WAV 严格无缝。
 */
object AmbiencePlayer {

    private const val MAX_TRACK = 3

    private var pool: SoundPool? = null
    private var loadedId = 0
    private var loadedTrack = 0
    private var playingId = 0
    private var volume = 0.48f   // 默认 60% × 0.8 上限
    private var appContext: Context? = null

    private val loadListener = SoundPool.OnLoadCompleteListener { _, sampleId, status ->
        // 解码是异步的：完成且仍是当前选择才起播（避免换音色竞态把旧音色播出来）
        if (status == 0 && sampleId == loadedId && loadedTrack in 1..MAX_TRACK) {
            playingId = pool?.play(loadedId, volume, volume, 0, -1, 1f) ?: 0
        }
    }

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f * 0.8f
        val p = pool ?: return
        if (playingId != 0) p.setVolume(playingId, volume, volume)
    }

    fun play(context: Context, track: Int) {
        if (track !in 1..MAX_TRACK) {
            stop()
            return
        }
        if (loadedTrack == track && loadedId != 0) {
            // 同一音色：已解码直接播（音量拖动/重组反复调用无副作用）
            if (playingId == 0) {
                playingId = pool()?.play(loadedId, volume, volume, 0, -1, 1f) ?: 0
            }
            return
        }
        val ctx = appContext ?: context.applicationContext.also { appContext = it }
        val p = pool()
        stop()
        if (loadedId != 0) p.unload(loadedId)
        loadedId = try {
            p.load(ctx.assets.openFd(assetsPath(track)), 1)
        } catch (_: Exception) {
            0
        }
        loadedTrack = if (loadedId != 0) track else 0
    }

    fun stop() {
        val p = pool ?: return
        if (playingId != 0) {
            runCatching { p.stop(playingId) }
            playingId = 0
        }
    }

    fun release() {
        stop()
        pool?.release()
        pool = null
        loadedId = 0
        loadedTrack = 0
    }

    private fun pool(): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
        .build()
        .also {
            it.setOnLoadCompleteListener(loadListener)
            pool = it
        }

    private fun assetsPath(track: Int) = when (track) {
        1 -> "sounds/ambience/rain.wav"
        2 -> "sounds/ambience/fire.wav"
        else -> "sounds/ambience/cafe.wav"
    }
}
