package com.mangareader.eink

import org.junit.Test

/**
 * 「256 级模拟看起来无效」量化取证（纯 JVM）：
 * 在 manga 型内容（≈92% 纯白 + 黑线 + 少量中间调网点区）上测各算法×档位
 * 实际改动的像素占比与幅度——用户报告"看不出变化"，用数字回答是链路没通
 * 还是感知固有属性（误差扩散设计目标=视觉透明 + 端点直通跳过纯白纯黑）。
 */
class EinkDitherDeltaTest {

    /** 模拟漫画页：白底 92%、黑线/字条、右下角一块 8% 面积的中间调渐变（网点区） */
    private fun mangaPixels(w: Int, h: Int): IntArray {
        val px = IntArray(w * h) { -0x1000000 or (0xFF shl 16) or (0xFF shl 8) or 0xFF } // 纯白
        // 黑色横线（分格框）与文字条
        for (y in 0 until h step 64) for (x in 0 until w) px[y * w + x] = -0x1000000
        for (y in 0 until h step 64) for (x in 0 until w) if ((x / 7 + y / 11) % 3 == 0) px[y * w + x] = -0x1000000
        // 右下 8% 面积：灰度渐变 64..192（中间调）
        val gw = w / 4; val gh = h / 4
        for (y in 0 until gh) for (x in 0 until gw) {
            val g = 64 + (x + y) * 128 / (gw + gh)
            px[(h - gh + y) * w + (w - gw + x)] = -0x1000000 or (g shl 16) or (g shl 8) or g
        }
        return px
    }

    private fun grayOf(p: Int): Int = ((p ushr 16 and 0xFF) * 77 + (p ushr 8 and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8

    private fun deltaStats(name: String, orig: IntArray, out: IntArray) {
        var changed = 0; var changedMid = 0; var midTotal = 0; var absSum = 0L
        for (i in orig.indices) {
            val g0 = grayOf(orig[i]); val g1 = grayOf(out[i])
            val isMid = g0 in 1..254
            if (isMid) midTotal++
            if (g0 != g1) {
                changed++
                absSum += Math.abs(g0 - g1)
                if (isMid) changedMid++
            }
        }
        val pctAll = 100.0 * changed / orig.size
        val pctMid = if (midTotal > 0) 100.0 * changedMid / midTotal else 0.0
        val mean = if (changed > 0) absSum.toDouble() / changed else 0.0
        println("$name: 改动像素 ${"%.2f".format(pctAll)}%（全画面）/ ${"%.1f".format(pctMid)}%（中间调），改动像素平均幅度 ${"%.1f".format(mean)} 灰阶")
    }

    @Test
    fun `manga 型内容各算法实际改动幅度`() {
        val w = 1000; val h = 1400
        val orig = mangaPixels(w, h)
        val algos = listOf("FS细腻", "Atkinson", "Stucki", "Bayer规则纹理")
        for (levels in intArrayOf(16, 32, 64)) {
            for (a in 0..3) {
                val src = orig.copyOf()
                EinkDither.apply(src, w, h, a, levels)
                deltaStats("${algos[a]} L=$levels", orig, src)
            }
        }
        // 对照组：全画面线性渐变（断层最敏感内容）——证明算法本身在"该起作用的地方"起作用
        val grad = IntArray(w * h) { i ->
            val g = (i % w) * 255 / w
            -0x1000000 or (g shl 16) or (g shl 8) or g
        }
        val gOut = grad.copyOf()
        EinkDither.apply(gOut, w, h, EinkDither.ALGO_FLOYD_STEINBERG, 16)
        deltaStats("对照·全幅渐变 FS L=16", grad, gOut)
    }
}
