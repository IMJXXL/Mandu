package com.mangareader.eink

import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 墨水屏抖动样张仿真 + 性能基准（纯 JVM，无真机依赖）。
 *
 * 生成 4 算法（FS/Atkinson/Stucki/Bayer）× 16 档的对比样张 PNG 到
 * [OUT]：每张样张横向排列 原图|FS|Atkinson|Stucki|Bayer。
 * 样张覆盖：线性渐变（断层消除）、径向渐变、网点纹理（漫画网点）、
 * 文字渲染（毛边评估）、纯白页（端点直通纯净度）。
 * 另跑 1920×1600 基准打印各算法耗时（手机折算 ×2~3）。
 */
class EinkDitherSampleTest {

    private val outDir = File("build/eink-samples")

    private fun bi(w: Int, h: Int): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

    private fun save(img: BufferedImage, name: String) {
        outDir.mkdirs()
        ImageIO.write(img, "png", File(outDir, name))
    }

    private fun toPixels(img: BufferedImage): IntArray {
        val w = img.width; val h = img.height
        return img.getRGB(0, 0, w, h, null, 0, w)
    }

    private fun fromPixels(px: IntArray, w: Int, h: Int): BufferedImage {
        val img = bi(w, h)
        img.setRGB(0, 0, w, h, px, 0, w)
        return img
    }

    /** 横向拼接：原图 + 4 算法输出，上方留标签条 */
    private fun composeGrid(title: String, original: BufferedImage, variants: List<BufferedImage>): BufferedImage {
        val labelH = 28
        val iw = original.width; val ih = original.height
        val n = variants.size + 1
        val grid = bi(iw * n + 8 * (n + 1), ih + labelH + 16)
        val g = grid.createGraphics()
        g.color = Color(0xFFEEEEEE.toInt())
        g.fillRect(0, 0, grid.width, grid.height)
        g.color = Color.BLACK
        g.font = Font("SansSerif", Font.BOLD, 14)
        g.drawString(title, 8, 18)
        val labels = listOf("原图", "Floyd-Steinberg", "Atkinson", "Stucki", "Bayer 4×4")
        var x = 8
        val all = listOf(original) + variants
        all.forEachIndexed { i, img ->
            g.drawImage(img, x, labelH, null)
            g.font = Font("SansSerif", Font.PLAIN, 12)
            g.drawString(labels[i], x, labelH + ih + 12)
            x += iw + 8
        }
        g.dispose()
        return grid
    }

    private fun processVariants(original: BufferedImage, levels: Int = 16): List<BufferedImage> {
        val w = original.width; val h = original.height
        val src = toPixels(original)
        return listOf(
            EinkDither.ALGO_FLOYD_STEINBERG,
            EinkDither.ALGO_ATKINSON,
            EinkDither.ALGO_STUCKI,
            EinkDither.ALGO_BAYER_4X4,
        ).map { algo ->
            val px = src.copyOf()
            EinkDither.apply(px, w, h, algo, levels)
            fromPixels(px, w, h)
        }
    }

    /** 线性渐变：最考验断层消除 */
    private fun linearGradient(w: Int, h: Int): BufferedImage {
        val img = bi(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = x * 255 / (w - 1)
            img.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
        }
        return img
    }

    /** 径向渐变 */
    private fun radialGradient(w: Int, h: Int): BufferedImage {
        val img = bi(w, h)
        val cx = w / 2.0; val cy = h / 2.0
        val maxR = Math.hypot(cx, cy)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (Math.hypot(x - cx, y - cy) / maxR * 255).toInt().coerceIn(0, 255)
            img.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
        }
        return img
    }

    /** 漫画网点：正弦网格三种密度叠加渐变底 */
    private fun screentone(w: Int, h: Int): BufferedImage {
        val img = bi(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val base = 200 - x * 160 / w
            val freq = 0.35
            val dot = Math.sin(x * freq) * Math.cos(y * freq) * 55
            val v = (base + dot).toInt().coerceIn(0, 255)
            img.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
        }
        return img
    }

    /** 文字渲染：评估抖动对内嵌文字毛边的影响 */
    private fun textPage(w: Int, h: Int): BufferedImage {
        val img = bi(w, h)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.font = Font("SansSerif", Font.PLAIN, 22)
        g.drawString("漫读灰阶抖动样张 E-ink 256 level simulation", 20, 60)
        g.font = Font("SansSerif", Font.BOLD, 34)
        g.drawString("粗体标题测试 笔画均匀性", 20, 120)
        g.font = Font("Serif", Font.ITALIC, 26)
        g.drawString("斜体 Serif 混排 0123456789", 20, 175)
        g.stroke = BasicStroke(2f)
        g.drawLine(20, 210, w - 20, 210)
        g.font = Font("SansSerif", Font.PLAIN, 16)
        g.drawString("小号正文：误差扩散抖动对文字抗锯齿边缘的影响评估。", 20, 245)
        g.dispose()
        return img
    }

    /** 纯白页：验证端点直通后背景完全无噪点 */
    private fun pureWhite(w: Int, h: Int): BufferedImage {
        val img = bi(w, h)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.dispose()
        return img
    }

    private fun countNoiseLevels(img: BufferedImage): Int {
        val px = toPixels(img)
        val set = HashSet<Int>()
        for (p in px) set.add(p and 0xFF)
        return set.size
    }

    @Test
    fun generateSamples() {
        val w = 420; val h = 300
        val cases = listOf(
            "linear" to linearGradient(w, h),
            "radial" to radialGradient(w, h),
            "screentone" to screentone(w, h),
            "text" to textPage(w, h),
            "purewhite" to pureWhite(w, h),
        )
        for ((name, img) in cases) {
            save(composeGrid("[$name] levels=16", img, processVariants(img)), "sample_$name.png")
        }
        // 纯白页量化档数断言：直通后应只有 1 个灰度值（255），证明背景零噪声
        val white = pureWhite(200, 200)
        val px = toPixels(white)
        EinkDither.apply(px, 200, 200, EinkDither.ALGO_FLOYD_STEINBERG, 16)
        org.junit.Assert.assertEquals("纯白页端点直通应零噪声", 1, countNoiseLevels(fromPixels(px, 200, 200)))
        // 渐变档数断言：16 档输入的输出值域 ≤16 种灰度
        val grad = linearGradient(256, 64)
        val gpx = toPixels(grad)
        EinkDither.apply(gpx, 256, 64, EinkDither.ALGO_FLOYD_STEINBERG, 16)
        org.junit.Assert.assertTrue("输出档数不应超过量化档位", countNoiseLevels(fromPixels(gpx, 256, 64)) <= 16)
    }

    @Test
    fun benchmark() {
        val w = 1920; val h = 1600
        val src = IntArray(w * h) { i ->
            val v = ((i * 7919) ushr 3) and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val names = listOf("Floyd-Steinberg", "Atkinson", "Stucki", "Bayer 4×4")
        val algos = listOf(
            EinkDither.ALGO_FLOYD_STEINBERG, EinkDither.ALGO_ATKINSON,
            EinkDither.ALGO_STUCKI, EinkDither.ALGO_BAYER_4X4,
        )
        val report = StringBuilder()
        report.appendLine("JVM 基准 ${w}×${h}（${w * h / 10000} 万像素），5 次均值（手机按 ×2~3 折算）：")
        for ((idx, algo) in algos.withIndex()) {
            val px = src.copyOf()
            EinkDither.apply(px, w, h, algo, 16) // 预热
            var best = Long.MAX_VALUE
            var sum = 0L
            repeat(5) {
                val p = src.copyOf()
                val t0 = System.nanoTime()
                EinkDither.apply(p, w, h, algo, 16)
                val ms = (System.nanoTime() - t0) / 1_000_000
                best = minOf(best, ms); sum += ms
            }
            report.appendLine("  ${names[idx].padEnd(16)} avg=${sum / 5}ms best=${best}ms")
        }
        println(report)
        outDir.mkdirs()
        File(outDir, "benchmark.txt").writeText(report.toString())
    }
}
