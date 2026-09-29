package com.mangareader.sound

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * 阅读氛围音：雨声/篝火/咖啡馆无缝循环（assets/sounds/ambience/ 下 30s 等功率交叉淡化 WAV）。
 *
 * 用 MediaPlayer（setLooping）而非 SoundPool：v1 的 SoundPool 异步解码链路在用户设备上无声
 * （无法远程定位），MediaPlayer 直接流式播放 WAV、setLooping 对 PCM 严格无缝，且 MIUI 等
 * 国产系统上久经考验。track=0 关闭；换音色/退出即释放。
 */
object AmbiencePlayer {

    private const val MAX_TRACK = 3
    private const val TAG = "Ambience"

    private var player: MediaPlayer? = null
    private var currentTrack = 0
    private var generation = 0
    private var volume = 0.48f   // 默认 60% × 0.8 上限
    private var appContext: Context? = null

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f * 0.8f
        runCatching { player?.setVolume(volume, volume) }
    }

    fun play(context: Context, track: Int) {
        if (track !in 1..MAX_TRACK) {
            stop()
            return
        }
        if (track == currentTrack) {
            // 同一音色：已存在则确保在播（音量拖动/重组反复调用无副作用）
            runCatching { player?.takeIf { !it.isPlaying }?.start() }
            return
        }
        val ctx = appContext ?: context.applicationContext.also { appContext = it }
        stop()
        val gen = ++generation
        Thread {
            try {
                val mp = MediaPlayer()
                ctx.assets.openFd(assetsPath(track)).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                mp.isLooping = true
                mp.setVolume(volume, volume)
                mp.prepare()
                synchronized(this) {
                    // prepare 期间用户又换了音色/退出：废弃本次
                    if (gen != generation) {
                        mp.release()
                        return@Thread
                    }
                    player = mp
                    currentTrack = track
                    mp.start()
                    Log.d(TAG, "playing track=$track vol=$volume")
                }
            } catch (e: Exception) {
                Log.e(TAG, "play track=$track failed", e)
            }
        }.start()
    }

    fun stop() {
        generation++
        player?.run {
            runCatching {
                if (isPlaying) stop()
            }
            release()
        }
        player = null
        currentTrack = 0
    }

    fun release() = stop()

    private fun assetsPath(track: Int) = when (track) {
        1 -> "sounds/ambience/rain.wav"
        2 -> "sounds/ambience/fire.wav"
        else -> "sounds/ambience/cafe.wav"
    }
}
