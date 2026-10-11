package com.mangareader.panel

/**
 * 置信度评分与整页降级（管线阶段 10/12，评估报告 §五）。
 * 五因子加权（coverage/中缝变异/格数/矩形度 + 唯一大格整页规则）——panelizer(MIT) 思路的
 * 简化实现，全新 Kotlin。页置信度 <CONF_FAIL 或 0 框 → 整页单格 fallback。
 */
internal object PanelConfidence {

    /** 像素域候选框（已排阅读序）→ 归一化结果（含 fallback 整页单格） */
    fun evaluate(
        boxes: List<PanelComponents.Box>,
        w: Int,
        h: Int,
        rtl: Boolean,
        confFail: Float = PanelTunables.CONF_FAIL,
    ): PanelPageResult {
        val pageArea = w.toFloat() * h
        if (boxes.isEmpty()) return fallbackResult()

        val coverage = minOf(1f, boxes.sumOf { it.area.toDouble() }.toFloat() / pageArea)

        val covScore = when {
            coverage >= 0.98f -> 1f - (coverage - 0.98f) * 20f * 0.4f   // 满版贴边轻微降权
            coverage >= 0.65f -> 1f
            else -> (coverage - 0.35f) / 0.30f                            // <35% 直接落入降级区
        }.coerceIn(0f, 1f)

        val gutterScore = gutterScore(boxes)
        val countScore = when (boxes.size) {
            in 1..12 -> 1f
            else -> 0.5f
        }
        val aspectScore = boxes.map { b ->
            val aspect = b.w.toFloat() / b.h
            if (aspect in PanelTunables.ASPECT_LO..PanelTunables.ASPECT_HI) 1f else 0.4f
        }.average().toFloat()

        val conf = 0.45f * covScore + 0.20f * gutterScore + 0.20f * countScore + 0.15f * aspectScore

        // 整页规则：唯一框覆盖 ≥85% → 满版/跨页大图，属正常单格，不降级
        val isFullPageSingle = boxes.size == 1 && boxes[0].area / pageArea >= PanelTunables.FULL_PAGE_AREA
        val fail = conf < confFail && !isFullPageSingle
        if (fail) return fallbackResult(conf)

        val normalized = boxes.mapIndexed { i, b ->
            PanelBox(
                x = b.x0 / w.toFloat(),
                y = b.y0 / h.toFloat(),
                w = b.w / w.toFloat(),
                h = b.h / h.toFloat(),
                order = i,
                conf = conf,
            )
        }
        return PanelPageResult(normalized, conf, fallback = false)
    }

    private fun fallbackResult(conf: Float = 0f): PanelPageResult = PanelPageResult(
        boxes = listOf(PanelBox(0f, 0f, 1f, 1f, 0, conf)),
        pageConf = conf,
        fallback = true,
    )

    /** 中缝质量：收集相邻框间距（同列垂直邻 / 同行水平邻，重叠过半才视为邻居），CV=σ/μ；<2 缝 → 中性 0.7 */
    internal fun gutterScore(boxes: List<PanelComponents.Box>): Float {
        val gaps = ArrayList<Int>()
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            val hOv = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0) + 1
            val vOv = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0) + 1
            val vGap = maxOf(b.y0 - a.y1, a.y0 - b.y1)
            val hGap = maxOf(b.x0 - a.x1, a.x0 - b.x1)
            if (hOv > 0 && hOv * 2 >= minOf(a.w, b.w) && vGap > 0) gaps.add(vGap)
            if (vOv > 0 && vOv * 2 >= minOf(a.h, b.h) && hGap > 0) gaps.add(hGap)
        }
        if (gaps.size < 2) return 0.7f
        val mean = gaps.average()
        if (mean <= 0.5) return 0.4f
        val variance = gaps.sumOf { (it - mean) * (it - mean) } / gaps.size
        val cv = (kotlin.math.sqrt(variance) / mean).toFloat()
        return when {
            cv <= 0.35f -> 1f
            cv >= 1.0f -> 0.4f
            else -> 1f - (cv - 0.35f) / 0.65f * 0.6f
        }
    }
}
