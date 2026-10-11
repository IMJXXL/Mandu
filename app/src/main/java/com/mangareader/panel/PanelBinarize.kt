package com.mangareader.panel

/**
 * 墨水掩码构建（管线阶段 1-2，评估报告 §五）。
 * 输入检测分辨率灰度 [0,255]，输出 ink=true 布尔掩码。纯 JVM 无 Android 依赖，可单测。
 *
 * 算法（思路出处见评估报告 §3.2，实现全新）：
 * ① Otsu 全局阈值（暗墨白纸的漫画页一步到位）；
 * ② 白占比自检 ∉[0.15,0.85]（欠/过分割——发黄纸、受潮、大面积涂黑）→ 高斯自适应均值
 *    （积分图 O(1) 局部均值，blk=31、C=5，CPE 思路）；
 * ③ 仍不健康 → 反转 Otsu（黑底白字/夜间反色页）；
 * ④ 全不健康 → 取白占比最接近区间中点者（稀疏页 Otsu 仍是最稳选择）。
 */
internal object PanelBinarize {

    /** Otsu 阈值（256 bin 直方图，类间方差最大化） */
    fun otsuThreshold(gray: IntArray): Int {
        val hist = IntArray(256)
        for (g in gray) hist[g]++
        val total = gray.size
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = 0
        var bestVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val d = mB - mF
            val between = wB.toDouble() * wF * d * d
            if (between > bestVar) {
                bestVar = between
                best = t
            }
        }
        return best
    }

    /**
     * 构建墨水掩码（true=墨水）。
     * 注：白占比健康判定针对"二值化质量"而非"页面疏密"——稀疏页（墨 <15%）Otsu 依旧正确，
     * 此处宁可放过交给后续连通域过滤，也不让自适应在白纸上造噪。
     */
    /**
     * 构建墨水掩码（true=墨水=内容载体）。
     *
     * 极性先行：先用 Otsu 粗分出暗类/亮类，内容=少数类；黑底页（涂黑满版/反色）内容是亮类，
     * 先把灰度域整体反转再走标准"暗墨白纸"管线——自适应分支的"墨=比局部均值暗"语义只在
     * 正常极性下成立，极性混着选分支会在黑底页上把背景当内容。
     * 注：白占比健康判定针对"二值化质量"而非"页面疏密"——稀疏页（墨 <15%）Otsu 依旧正确，
     * 此处宁可放过交给后续连通域过滤，也不让自适应在白纸上造噪。
     */
    fun buildInkMask(gray: IntArray, w: Int, h: Int): BooleanArray {
        val n = gray.size
        val coarse = otsuThreshold(gray)
        var below = 0
        for (g in gray) if (g <= coarse) below++
        val invert = (n - below) < below   // 亮类更小 → 内容是亮的 → 反转域
        val src = if (invert) IntArray(n) { 255 - gray[it] } else gray

        val thr = otsuThreshold(src)
        val otsu = BooleanArray(n)
        for (i in 0 until n) otsu[i] = src[i] < thr
        if (isHealthy(otsu)) return otsu

        val adaptive = adaptiveMask(src, w, h)
        if (isHealthy(adaptive)) return adaptive

        val inverted = BooleanArray(n)
        for (i in 0 until n) inverted[i] = src[i] > thr
        if (isHealthy(inverted)) return inverted

        val mid = (PanelTunables.WHITE_RATIO_LO + PanelTunables.WHITE_RATIO_HI) / 2f
        val ro = whiteness(otsu)
        val ra = whiteness(adaptive)
        val ri = whiteness(inverted)
        return when {
            kotlin.math.abs(ro - mid) <= kotlin.math.abs(ra - mid) &&
                kotlin.math.abs(ro - mid) <= kotlin.math.abs(ri - mid) -> otsu
            kotlin.math.abs(ra - mid) <= kotlin.math.abs(ri - mid) -> adaptive
            else -> inverted
        }
    }

    /** 形态学闭运算（可分离两轴，先膨胀后腐蚀），弥合断裂/淡墨边框而不加粗笔画主体 */
    fun close(mask: BooleanArray, w: Int, h: Int, k: Int): BooleanArray {
        if (k <= 1) return mask
        val r = k / 2
        val dilH = BooleanArray(mask.size)
        morphAxis(mask, dilH, w, h, r, horizontal = true, dilate = true)
        val dilHV = BooleanArray(mask.size)
        morphAxis(dilH, dilHV, w, h, r, horizontal = false, dilate = true)
        val eroH = BooleanArray(mask.size)
        morphAxis(dilHV, eroH, w, h, r, horizontal = true, dilate = false)
        val out = BooleanArray(mask.size)
        morphAxis(eroH, out, w, h, r, horizontal = false, dilate = false)
        return out
    }

    /** 闭运算核边长（DPI 无关：0.3%·短边，CPE 思路） */
    fun closeKernelSize(w: Int, h: Int): Int =
        maxOf(3, kotlin.math.round(PanelTunables.CLOSE_KERNEL_RATIO * minOf(w, h)).toInt())

    // ———— 内部实现 ————

    private fun isHealthy(mask: BooleanArray): Boolean {
        val ratio = whiteness(mask)
        return ratio in PanelTunables.WHITE_RATIO_LO..PanelTunables.WHITE_RATIO_HI
    }

    private fun whiteness(mask: BooleanArray): Float {
        var ink = 0
        for (v in mask) if (v) ink++
        return 1f - ink.toFloat() / mask.size
    }

    /** 高斯窗口退化为方窗局部均值（积分图，与 cv2.adaptiveThreshold(GAUSSIAN, blk, C) 同语义量级）；仅在正常极性域调用 */
    private fun adaptiveMask(gray: IntArray, w: Int, h: Int): BooleanArray {
        val n = gray.size
        val out = BooleanArray(n)
        val iw = w + 1
        val ii = LongArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            val yDest = (y + 1) * iw
            val ySrc = y * iw
            for (x in 0 until w) {
                rowSum += gray[y * w + x].toLong()
                ii[yDest + x + 1] = ii[ySrc + x + 1] + rowSum
            }
        }
        val r = PanelTunables.ADAPTIVE_BLOCK / 2
        val c = PanelTunables.ADAPTIVE_C
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r)
            val y1 = minOf(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r)
                val x1 = minOf(w - 1, x + r)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val s = ii[(y1 + 1) * iw + (x1 + 1)] - ii[y0 * iw + (x1 + 1)] -
                    ii[(y1 + 1) * iw + x0] + ii[y0 * iw + x0]
                val mean = (s / area).toInt()
                val g = gray[y * w + x]
                out[y * w + x] = g < mean - c
            }
        }
        return out
    }

    /**
     * 单轴滑动窗形态学。边界窗收缩到实际像素（外推:false 语义）——
     * 膨胀不会把墨推到图外，腐蚀不因边界假空白削掉贴边框。
     */
    private fun morphAxis(
        src: BooleanArray, dst: BooleanArray,
        w: Int, h: Int, r: Int, horizontal: Boolean, dilate: Boolean,
    ) {
        if (horizontal) {
            val pre = IntArray(w + 1)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) pre[x + 1] = pre[x] + (if (src[row + x]) 1 else 0)
                for (x in 0 until w) {
                    val lo = maxOf(0, x - r)
                    val hi = minOf(w - 1, x + r)
                    val cnt = pre[hi + 1] - pre[lo]
                    dst[row + x] = if (dilate) cnt > 0 else cnt == hi - lo + 1
                }
            }
        } else {
            val pre = IntArray(h + 1)
            for (x in 0 until w) {
                for (y in 0 until h) pre[y + 1] = pre[y] + (if (src[y * w + x]) 1 else 0)
                for (y in 0 until h) {
                    val lo = maxOf(0, y - r)
                    val hi = minOf(h - 1, y + r)
                    val cnt = pre[hi + 1] - pre[lo]
                    dst[y * w + x] = if (dilate) cnt > 0 else cnt == hi - lo + 1
                }
            }
        }
    }
}
