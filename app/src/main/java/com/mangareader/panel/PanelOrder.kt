package com.mangareader.panel

/**
 * 阅读顺序（管线阶段 11，评估报告 §五）。
 * 行聚类（容差=中位格高比例）→ 行内按 x（RTL 降序）→ 修正回路（上方格+同向侧格必须在前）。
 * 思路出处：CPE(GPL 思路) 行容差、kumiko(AGPL 思路) 修正回路——实现为全新 Kotlin。
 */
internal object PanelOrder {

    /** 像素域框列表 → 阅读序（LTR：上→下、左→右；RTL：上→下、右→左） */
    fun sortReadingOrder(
        boxes: List<PanelComponents.Box>,
        rtl: Boolean,
    ): List<PanelComponents.Box> {
        if (boxes.size <= 1) return boxes
        val rows = clusterRows(boxes).sortedBy { it.minOf { b -> b.y0 } }
        val ordered = ArrayList<PanelComponents.Box>(boxes.size)
        for (row in rows) {
            row.sortBy { if (rtl) -it.x0 else it.x0 }
            ordered += row
        }
        return fixup(ordered, rtl)
    }

    /**
     * 行聚类：按 y0 排序后逐框归属"垂直重叠≥50%行带"的最佳行。
     * ★ 不设纯间隙容差分支——行距很近的网格会被吸进上一行、行内 x 排序随即交错；
     * 贴行错位（重叠不足）的场景由 fixup 修正回路兜住正确性。
     */
    internal fun clusterRows(boxes: List<PanelComponents.Box>): List<MutableList<PanelComponents.Box>> {
        val rows = ArrayList<MutableList<PanelComponents.Box>>()
        for (b in boxes.sortedBy { it.y0 }) {
            var best: MutableList<PanelComponents.Box>? = null
            var bestScore = 0f
            for (row in rows) {
                val bandY0 = row.minOf { it.y0 }
                val bandY1 = row.maxOf { it.y1 }
                val ov = minOf(b.y1, bandY1) - maxOf(b.y0, bandY0) + 1
                if (ov <= 0) continue
                val minH = minOf(b.h, bandY1 - bandY0 + 1)
                val score = ov.toFloat() / minH
                if (score >= PanelTunables.ROW_OVERLAP && score > bestScore) {
                    bestScore = score
                    best = row
                }
            }
            if (best != null) best.add(b) else rows.add(mutableListOf(b))
        }
        return rows
    }

    /**
     * 修正回路：相邻对若逆序（j 应在 i 前却排在后）则交换，迭代至稳定（kumiko fix_panels_numbering 思路）。
     * "a 应在 b 前"= ① a 在 b 正上方（无垂直重叠、水平重叠≥1/3 短边、且高差超出模糊容差）；
     * ② 同行带（垂直重叠≥1/3 短高）且 a 在阅读方向一侧。
     */
    internal fun fixup(
        ordered: List<PanelComponents.Box>,
        rtl: Boolean,
    ): List<PanelComponents.Box> {
        val out = ordered.toMutableList()
        if (out.size < 2) return out
        val fuzz = (PanelTunables.FUZZ_Y_TOL * out.maxOf { it.h }).coerceAtLeast(2f)
        var moved = true
        var guard = 0
        val maxGuard = out.size * out.size
        while (moved && guard++ < maxGuard) {
            moved = false
            for (i in 0 until out.size - 1) {
                if (mustComeBefore(out[i + 1], out[i], fuzz, rtl)) {
                    val t = out[i]
                    out[i] = out[i + 1]
                    out[i + 1] = t
                    moved = true
                }
            }
        }
        return out
    }

    private fun mustComeBefore(
        a: PanelComponents.Box,
        b: PanelComponents.Box,
        fuzz: Float,
        rtl: Boolean,
    ): Boolean {
        // ① a 在 b 上方
        val hOv = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0) + 1
        if (hOv > 0) {
            val noVertOverlap = a.y1 < b.y0 || b.y1 < a.y0
            if (noVertOverlap && a.y1 < b.y0 && hOv * 3 >= minOf(a.w, b.w) && (b.y0 - a.y1) > fuzz) {
                return true
            }
        }
        // ② 同行带且 a 在阅读方向一侧
        val vOv = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0) + 1
        if (vOv > 0 && vOv * 3 >= minOf(a.h, b.h)) {
            return if (rtl) a.x0 >= b.x1 else a.x1 <= b.x0
        }
        return false
    }
}
