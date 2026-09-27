package com.mangareader.eink

/**
 * 墨水屏灰阶抖动（「256 级模拟」）核心。
 *
 * 原理：黑白墨水屏面板硬件实际只有 levels 档灰度（主流 4bit 面板即 16 档），把 8bit 灰度
 * 图通过误差扩散/有序抖动映射到这 levels 档上，靠人眼空间混色感知出远超面板档位的
 * 平滑灰阶（视觉上接近 256 级）。算法为 1976 年公开的 Floyd-Steinberg 及同族误差扩散核、
 * 有序 Bayer 矩阵，均为公开数学，本实现为纯自研。
 *
 * 超越常见同类实现（kindle-eink256 / dither256.koplugin）的点：
 * - 纯白(255)/纯黑(0) 端点直通：漫画大面积白底/黑线不参与抖动，背景不被噪点污染；
 * - 误差扩散默认蛇形扫描（serpentine），消除逐行同向扫描的经典水平蠕虫纹；
 * - Floyd-Steinberg / Atkinson / Stucki / Bayer 4×4 四算法可选，灰阶档位 16/32/64 可调；
 * - 全定点整数运算（无浮点），单次 O(n) 遍历，独立两行误差缓冲不污染原图。
 *
 * ★ 本类保持零 Android 依赖（纯 Kotlin stdlib），JVM 侧直接复用做样张仿真与性能基准。
 */
object EinkDither {

    const val ALGO_FLOYD_STEINBERG = 0
    const val ALGO_ATKINSON = 1
    const val ALGO_STUCKI = 2
    const val ALGO_BAYER_4X4 = 3

    /** 误差扩散算子 (dx, dy, num, den)：dx 为正 = 指向正向扫描时的"右"，serpentine 反向行取镜像。 */
    private val FS = arrayOf(
        intArrayOf(1, 0, 7, 16), intArrayOf(-1, 1, 3, 16), intArrayOf(0, 1, 5, 16), intArrayOf(1, 1, 1, 16),
    )
    private val ATKINSON = arrayOf(
        intArrayOf(1, 0, 1, 8), intArrayOf(2, 0, 1, 8),
        intArrayOf(-1, 1, 1, 8), intArrayOf(0, 1, 1, 8), intArrayOf(1, 1, 1, 8),
        intArrayOf(0, 2, 1, 8),
    )
    private val STUCKI = arrayOf(
        intArrayOf(1, 0, 8, 42), intArrayOf(2, 0, 4, 42),
        intArrayOf(-2, 1, 2, 42), intArrayOf(-1, 1, 4, 42), intArrayOf(0, 1, 8, 42),
        intArrayOf(1, 1, 4, 42), intArrayOf(2, 1, 2, 42),
        intArrayOf(-2, 2, 1, 42), intArrayOf(-1, 2, 2, 42), intArrayOf(0, 2, 4, 42),
        intArrayOf(1, 2, 2, 42), intArrayOf(2, 2, 1, 42),
    )

    private val BAYER_4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )

    /**
     * 对 [pixels]（ARGB 打包像素，行主序，宽 [width] 高 [height]）就地应用抖动。
     * 输入可为任意彩色/灰度图，内部先转灰度（BT.601 定点）再抖动；输出像素
     * 为 levels 档纯灰度（R=G=B）。
     *
     * @param levels 量化档位（16/32/64），必须 ≥2
     */
    fun apply(pixels: IntArray, width: Int, height: Int, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        if (width <= 0 || height <= 0 || pixels.size < width * height || levels < 2) return
        val ep = endpointPassthrough.coerceIn(0, 8)
        val whiteThreshold = 255 - ep
        val blackThreshold = ep
        val maxIdx = levels - 1
        val n = width * height

        val quantTbl = IntArray(256) { v ->
            val idx = (v * maxIdx + 127) / 255
            (if (idx > maxIdx) maxIdx else idx) * 255 / maxIdx
        }

        var midtoneCount = 0
        for (i in 0 until n) {
            val g = grayscalize(pixels[i])
            pixels[i] = g
            if (g >= 32 && g <= 223) midtoneCount++
        }

        if (midtoneCount * 100 < n * 5) {
            for (i in 0 until n) {
                val q = quantTbl[pixels[i]]
                pixels[i] = -0x1000000 or (q shl 16) or (q shl 8) or q
            }
            return
        }

        if (algorithm == ALGO_BAYER_4X4) {
            bayerDither(pixels, width, height, maxIdx, whiteThreshold, blackThreshold)
        } else {
            val kernel = when (algorithm) {
                ALGO_ATKINSON -> ATKINSON
                ALGO_STUCKI -> STUCKI
                else -> FS
            }
            errorDiffusion(pixels, width, height, maxIdx, kernel, quantTbl, whiteThreshold, blackThreshold)
        }

        // 打包：灰度值 → 同值 RGB（不透明）
        for (i in 0 until n) {
            val g = pixels[i]
            pixels[i] = -0x1000000 or (g shl 16) or (g shl 8) or g
        }
    }

    /** BT.601 定点灰度：(77R + 150G + 29B) ÷ 256，结果 0..255。 */
    private inline fun grayscalize(p: Int): Int =
        ((p ushr 16 and 0xFF) * 77 + (p ushr 8 and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8

    private fun bayerDither(pixels: IntArray, width: Int, height: Int, maxIdx: Int, whiteThreshold: Int, blackThreshold: Int) {
        for (y in 0 until height) {
            val rowBase = (y and 3) shl 2
            var i = y * width
            for (x in 0 until width) {
                val g = pixels[i]
                pixels[i] = if (g >= whiteThreshold) 255 else if (g <= blackThreshold) 0 else {
                    val b = BAYER_4[rowBase or (x and 3)]
                    (32 * g * maxIdx + (2 * b + 1) * 255).floorDiv(32 * 255)
                        .coerceIn(0, maxIdx) * 255 / maxIdx
                }
                i++
            }
        }
    }

    /**
     * 误差扩散（FS/Atkinson/Stucki 共用），蛇形扫描：偶数行左→右，奇数行右→左（dx 镜像）。
     * 累计误差存独立两行缓冲（当前行/下一行），不污染未处理的原始像素。
     */
    private fun errorDiffusion(pixels: IntArray, width: Int, height: Int, maxIdx: Int, kernel: Array<IntArray>, quantTbl: IntArray, whiteThreshold: Int, blackThreshold: Int) {
        val errMin = -1024
        val errMax = 1024
        val errRange = errMax - errMin + 1
        val errTbl = Array(kernel.size) { IntArray(errRange) }
        for (k in kernel.indices) {
            val num = kernel[k][2]
            val den = kernel[k][3]
            for (e in 0 until errRange) {
                errTbl[k][e] = (e + errMin) * num / den
            }
        }

        var cur = IntArray(width)
        var next = IntArray(width)
        for (y in 0 until height) {
            val ltr = (y and 1) == 0
            val rowBase = y * width
            var x = if (ltr) 0 else width - 1
            val xEnd = if (ltr) width else -1
            val step = if (ltr) 1 else -1
            while (x != xEnd) {
                val v = pixels[rowBase + x] + cur[x]
                val out: Int
                val err: Int
                if (v >= whiteThreshold) { out = 255; err = 0 }
                else if (v <= blackThreshold) { out = 0; err = 0 }
                else {
                    out = quantTbl[v]
                    err = v - out
                }
                pixels[rowBase + x] = out
                if (err != 0) {
                    for (k in kernel.indices) {
                        val nx = x + if (ltr) kernel[k][0] else -kernel[k][0]
                        if (nx < 0 || nx >= width) continue
                        val ny = y + kernel[k][1]
                        if (ny >= height) continue
                        val e = if (err >= errMin && err <= errMax) errTbl[k][err - errMin] else err * kernel[k][2] / kernel[k][3]
                        if (ny == y) cur[nx] += e else next[nx] += e
                    }
                }
                x += step
            }
            val t = cur; cur = next; next = t
            java.util.Arrays.fill(next, 0)
        }
    }
}
