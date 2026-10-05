package com.mangareader.sound

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * 阅读氛围音：内置预置（assets/sounds/ambience/<id>.wav，稳态段等功率交叉淡化无缝循环）
 * + 用户经 SAF 导入的自选音频（拷贝到 filesDir/ambience/，mp3/ogg/flac/m4a/wav 均可）。
 *
 * 用 MediaPlayer（setLooping）而非 SoundPool：v1 的 SoundPool 异步解码链路在用户设备上无声
 * （无法远程定位），MediaPlayer 直接流式播放、setLooping 对 PCM 严格无缝，且 MIUI 等
 * 国产系统上久经考验。selection="" 关闭；换音色/退出即释放。
 */
object AmbiencePlayer {

    private const val TAG = "Ambience"

    /**
     * ★ 内置预置表（2026-10-05 从单 rain 扩到五个；selection 值即 assets 文件名去扩展名）。
     *   两处设置 UI（ReaderSettingsSheet / ReadingPrefsScreen）的芯片直接引用本表，
     *   新增预置 = 放 wav 资产 + 本表加一行，别处不用动；已发布的 selection 值不许改名
     *   （它是持久化配置值）。来源与循环剪辑配方见 assets/sounds/ambience/CREDITS.txt。
     */
    val PRESETS: List<Pair<String, String>> = listOf(
        "rain" to "下雨",
        "waves" to "海浪",
        "stream" to "溪流",
        "campfire" to "篝火",
        "crickets" to "夏夜",
    )

    private fun presetAsset(selection: String): String? =
        if (PRESETS.any { it.first == selection }) "sounds/ambience/$selection.wav" else null

    private var player: MediaPlayer? = null
    private var currentTrack = ""
    private var generation = 0
    private var volume = 0.48f   // 默认 60% × 0.8 上限
    private var appContext: Context? = null
    @Volatile private var pausedByBackground = false

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f * 0.8f
        runCatching { player?.setVolume(volume, volume) }
    }

    /** ★ 进后台暂停（阅读页 ON_PAUSE）：MediaPlayer 单例不会自动让路，不暂停则退到后台持续出声 */
    fun pause() {
        pausedByBackground = true
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    /** ★ 回前台恢复：只复活因进后台暂停的播放；用户主动停止过的不复活 */
    fun resume() {
        if (!pausedByBackground) return
        pausedByBackground = false
        if (currentTrack.isNotEmpty()) {
            runCatching { player?.takeIf { !it.isPlaying }?.start() }
        }
    }

    fun play(context: Context, selection: String) {
        if (selection.isEmpty()) {
            stop()
            return
        }
        if (selection == currentTrack) {
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
                val preset = presetAsset(selection)
                if (preset != null) {
                    ctx.assets.openFd(preset).use { afd ->
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                } else {
                    // 用户导入：filesDir/ambience/<文件名>，setDataSource 接受绝对路径
                    mp.setDataSource(File(ctx.filesDir, "ambience/$selection").absolutePath)
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
                    currentTrack = selection
                    // prepare 期间已进后台：备好但不起播，回前台 resume 接管
                    if (!pausedByBackground) mp.start()
                    Log.d(TAG, "playing selection=$selection vol=$volume")
                }
            } catch (e: Exception) {
                // 日志中性化：用户导入文件名不外泄，只报预置名或 <custom>
                Log.e(TAG, "play failed sel=${if (presetAsset(selection) != null) selection else "<custom>"}: ${e.message}")
                // ★ 2026-10-05 回退：预置资产缺失（旧包升级后选了新音色）或导入文件被系统清理
                //   → 原先静默无声（工单实锤 ENOENT），现兜底回退内置 rain；rain 自身缺失不再递归
                if (selection != "rain") play(context, "rain")
            }
        }.start()
    }

    fun stop() {
        generation++
        pausedByBackground = false
        player?.run {
            runCatching {
                if (isPlaying) stop()
            }
            release()
        }
        player = null
        currentTrack = ""
    }

    fun release() = stop()
}
