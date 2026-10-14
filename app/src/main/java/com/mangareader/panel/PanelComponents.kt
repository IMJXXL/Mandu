package com.mangareader.panel

/**
 * 连通域与框级处理（管线阶段 3-9，评估报告 §五）。
 * 输入墨水掩码，输出像素域候选矩形（含边界，x1/y1 为 inclusive）。
 * 纯 JVM 无 Android 依赖。思路出处：panelzoom(MIT) 尺寸过滤、kumiko(AGPL 思路) 碎片归组/
 * 包含合并/去重叠/中缝外扩、CPE(GPL 思路) NMS——实现均为全新 Kotlin。
 */
internal object PanelComponents {

    /** 像素域矩形（inclusive 边界） */
    internal data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w: Int get() = x1 - x0 + 1
        val h: Int get() = y1 - y0 + 1
        val area: Int get() = w * h
        fun intersects(o: Box): Boolean = x0 <= o.x1 && o.x0 <= x1 && y0 <= o.y1 && o.y0 <= y1
        fun contains(o: Box): Boolean = x0 <= o.x0 && o.x1 <= x1 && y0 <= o.y0 && o.y1 <= y1
        fun union(o: Box): Box =
            Box(minOf(x0, o.x0), minOf(y0, o.y0), maxOf(x1, o.x1), maxOf(y1, o.y1))
        fun interArea(o: Box): Int {
            val iw = minOf(x1, o.x1) - maxOf(x0, o.x0) + 1
            val ih = minOf(y1, o.y1) - maxOf(y0, o.y0) + 1
            return if (iw <= 0 || ih <= 0) 0 else iw * ih
        }
    }

    internal data class Component(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int, val pixels: IntArray = IntArray(0)) {
        fun toBox() = Box(x0, y0, x1, y1)
    }

    /** 8 连通标记（单遍行扫描 + union-find 等价类），返回各连通域 bbox+面积（不含背景） */
    fun connectedComponents(mask: BooleanArray, w: Int, h: Int): List<Component> {
        val n = w * h
        val labels = IntArray(n)
        var next = 1
        var parent = IntArray(256)
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        fun newLabel(): Int {
            if (next >= parent.size) parent = parent.copyOf(parent.size * 2)
            parent[next] = next
            return next++
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) continue
                var m = 0
                if (x > 0 && mask[i - 1]) m = labels[i - 1]
                if (y > 0) {
                    if (mask[i - w]) {
                        val u = labels[i - w]
                        m = if (m == 0) u else { union(m, u); m }
                    }
                    if (x > 0 && mask[i - w - 1]) {
                        val u = labels[i - w - 1]
                        m = if (m == 0) u else { union(m, u); m }
                    }
                    if (x < w - 1 && mask[i - w + 1]) {
                        val u = labels[i - w + 1]
                        m = if (m == 0) u else { union(m, u); m }
                    }
                }
                labels[i] = if (m == 0) newLabel() else find(m)
            }
        }

        // 二遍：按根归并 bbox/area
        class Acc { var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1; var area = 0 }
        val accs = HashMap<Int, Acc>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val l = labels[y * w + x]
                if (l == 0) continue
                val root = find(l)
                val a = accs.getOrPut(root) { Acc() }
                if (x < a.x0) a.x0 = x
                if (x > a.x1) a.x1 = x
                if (y < a.y0) a.y0 = y
                if (y > a.y1) a.y1 = y
                a.area++
            }
        }
        val pixelsByRoot = HashMap<Int, IntArray>()
        for ((root, a) in accs) {
            val arr = IntArray(a.area)
            pixelsByRoot[root] = arr
        }
        val offsets = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val l = labels[y * w + x]
                if (l == 0) continue
                val root = find(l)
                val arr = pixelsByRoot[root]!!
                val off = offsets.getOrPut(root) { 0 }
                arr[off] = y * w + x
                offsets[root] = off + 1
            }
        }
        return accs.map { (root, a) -> Component(a.x0, a.y0, a.x1, a.y1, a.area, pixelsByRoot[root]!!) }
    }

    /**
     * 阶段 4-9 主入口：掩码 → 最终矩形候选。
     * 候选(≥1/8 双维且≥2%面积)直接收；小连通域进碎片池按外扩相交归组（无边框格聚合）；
     * 然后包含合并→去重叠→中缝外扩→NMS。
     */
    fun toPanelRects(mask: BooleanArray, w: Int, h: Int, frameTolerance: Int = 3, frameMinSides: Int = 4, gray: IntArray? = null, maxDepth: Int = PanelTunables.SPLIT_MAX_DEPTH, engineMode: Int = 1): List<Box> {
        val comps = connectedComponents(mask, w, h)
        if (comps.isEmpty()) return emptyList()
        val pageArea = w.toFloat() * h
        val maxDim = maxOf(w, h).toFloat()

        val candidates = ArrayList<Box>()
        val fragments = ArrayList<Box>()
        for (c in comps) {
            val box = c.toBox()
            val areaRatio = box.area / pageArea
            val dimOk = box.w >= w * PanelTunables.MIN_DIM_RATIO && box.h >= h * PanelTunables.MIN_DIM_RATIO
            if (dimOk && areaRatio >= PanelTunables.MIN_AREA_RATIO) {
                if (engineMode == 0) {
                    candidates.add(box)
                } else {
                    val sides = PanelFrameEvidence.frameSides(c.pixels, c.area, w, box, frameTolerance)
                    val isLarge = areaRatio >= 0.1f
                    val sidesOk = if (isLarge) sides >= 1 else sides >= frameMinSides
                    if (sidesOk) candidates.add(box)
                }
            } else if (areaRatio >= PanelTunables.FRAG_KEEP_AREA) {
                fragments.add(box)
            }
        }
        candidates += groupFragments(fragments, maxDim).filter {
            it.w >= w * PanelTunables.MIN_DIM_RATIO && it.h >= h * PanelTunables.MIN_DIM_RATIO &&
                it.area / pageArea >= PanelTunables.MIN_AREA_RATIO
        }
        if (candidates.isEmpty()) return emptyList()

        val afterSplit = if (engineMode == 0) candidates else splitLargeCandidates(candidates, mask, w, h, pageArea, gray, maxDepth)

        val merged = dropContained(afterSplit)
        val deoverlapped = deoverlap(merged)
        val snapped = snapGutters(deoverlapped, w, h, maxDim)
        return nms(snapped)
    }

    /** 候选生成后对大候选框执行相连框拆分（白色分隔带/黑色共享边框/边框线），小框不拆分。 */
    private fun splitLargeCandidates(candidates: List<Box>, mask: BooleanArray, w: Int, h: Int, pageArea: Float, gray: IntArray? = null, maxDepth: Int = PanelTunables.SPLIT_MAX_DEPTH): List<Box> {
        val out = ArrayList<Box>()
        for (b in candidates) {
            if (b.area / pageArea >= PanelTunables.SPLIT_LARGE_AREA_RATIO) {
                val split = try {
                    PanelSplitJoined.splitJoined(mask, w, h, b, gray = gray, maxDepth = maxDepth)
                } catch (_: Exception) {
                    listOf(b)
                }
                if (split.size == 1 && b.area / pageArea >= 0.4f) {
                    val sheared = try {
                        PanelShearSplit.tryShear(mask, w, h, b)
                    } catch (_: Exception) {
                        listOf(b)
                    }
                    out.addAll(sheared)
                } else {
                    out.addAll(split)
                }
            } else {
                out.add(b)
            }
        }
        return out
    }

    /** 阶段5：碎片各自外扩 FRAG_GROUP_EXPAND·长边后相交即并（union-find），组框=原始框并集 */
    internal fun groupFragments(frags: List<Box>, maxDim: Float): List<Box> {

        if (frags.isEmpty()) return emptyList()
        val ex = frags.map {
            val d = (PanelTunables.FRAG_GROUP_EXPAND * maxDim).toInt()
            Box(maxOf(0, it.x0 - d), maxOf(0, it.y0 - d), it.x1 + d, it.y1 + d)
        }
        val parent = IntArray(frags.size) { it }
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        for (i in ex.indices) for (j in i + 1 until ex.size) {
            if (ex[i].intersects(ex[j])) parent[find(j)] = find(i)
        }
        val groups = HashMap<Int, Box>()
        for (i in frags.indices) {
            val root = find(i)
            groups[root] = groups[root]?.union(frags[i]) ?: frags[i]
        }
        return groups.values.toList()
    }

    /** 阶段6：交集/小框面积 > CONTAIN_MERGE 弃小框（气泡/装饰误分）。大框优先，O(k²) 框数小可接受 */
    internal fun dropContained(boxes: List<Box>): List<Box> {
        val sorted = boxes.sortedByDescending { it.area }
        val dropped = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (dropped[i]) continue
            for (j in i + 1 until sorted.size) {
                if (dropped[j]) continue
                val big = sorted[i]
                val small = sorted[j]
                val ratio = big.interArea(small).toFloat() / small.area
                if (ratio > PanelTunables.CONTAIN_MERGE) dropped[j] = true
            }
        }
        return sorted.filterIndexed { i, _ -> !dropped[i] }
    }

    /** 阶段7：近全重叠(>90%)弃小；细条重叠按重叠较小的轴把两边收到共享边界中点 */
    internal fun deoverlap(boxes: List<Box>): List<Box> {
        val out = boxes.toMutableList()
        var changed = true
        var guard = 0
        while (changed && guard++ < 3) {
            changed = false
            outer@ for (i in out.indices) for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                val inter = a.interArea(b)
                if (inter <= 0) continue
                val smaller = minOf(a.area, b.area)
                if (inter.toFloat() / smaller > PanelTunables.OVERLAP_DROP) {
                    if (a.area <= b.area) out.removeAt(i) else out.removeAt(j)
                    changed = true
                    break@outer
                }
                val xOv = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0) + 1
                val yOv = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0) + 1
                if (xOv <= yOv) {
                    // 水平细条重叠：左右框各收到中点
                    val left = if (a.x0 <= b.x0) i else j
                    val right = if (left == i) j else i
                    val mid = (out[left].x1 + out[right].x0) / 2
                    out[left] = Box(out[left].x0, out[left].y0, mid, out[left].y1)
                    out[right] = Box(mid + 1, out[right].y0, out[right].x1, out[right].y1)
                } else {
                    val top = if (a.y0 <= b.y0) i else j
                    val bot = if (top == i) j else i
                    val mid = (out[top].y1 + out[bot].y0) / 2
                    out[top] = Box(out[top].x0, out[top].y0, out[top].x1, mid)
                    out[bot] = Box(out[bot].x0, mid + 1, out[bot].x1, out[bot].y1)
                }
                changed = true
                break@outer
            }
        }
        return out.filter { it.w > 0 && it.h > 0 }
    }

    /**
     * 阶段8：中缝外扩（kumiko expand_panels 保守矩形版）。仅当邻缝 < SNAP_GAP_MAX·长边：
     * 每边扩 gap×SNAP_GAP_TAKE（与对侧合计不超缝宽减呼吸缝）；贴页缘且在阈值内 → 扩到页缘
     * （出血/裁切页的隐式边框）。
     */
    internal fun snapGutters(boxes: List<Box>, w: Int, h: Int, maxDim: Float): List<Box> {
        val gapMax = (PanelTunables.SNAP_GAP_MAX * maxDim).toInt()
        val breath = (PanelTunables.SNAP_BREATH * maxDim).toInt().coerceAtLeast(1)
        return boxes.map { b ->
            var x0 = b.x0; var y0 = b.y0; var x1 = b.x1; var y1 = b.y1
            // 左
            var best = Int.MAX_VALUE
            for (o in boxes) if (o !== b && verticalOverlap(b, o) && o.x1 < b.x0) best = minOf(best, b.x0 - o.x1 - 1)
            if (best != Int.MAX_VALUE && best < gapMax) x0 = expandEdge(b.x0, best, -1)
            else if (b.x0 < gapMax) x0 = 0
            // 右
            best = Int.MAX_VALUE
            for (o in boxes) if (o !== b && verticalOverlap(b, o) && o.x0 > b.x1) best = minOf(best, o.x0 - b.x1 - 1)
            if (best != Int.MAX_VALUE && best < gapMax) x1 = expandEdge(b.x1, best, +1)
            else if ((w - 1 - b.x1) < gapMax) x1 = w - 1
            // 上
            best = Int.MAX_VALUE
            for (o in boxes) if (o !== b && horizontalOverlap(b, o) && o.y1 < b.y0) best = minOf(best, b.y0 - o.y1 - 1)
            if (best != Int.MAX_VALUE && best < gapMax) y0 = expandEdge(b.y0, best, -1)
            else if (b.y0 < gapMax) y0 = 0
            // 下
            best = Int.MAX_VALUE
            for (o in boxes) if (o !== b && horizontalOverlap(b, o) && o.y0 > b.y1) best = minOf(best, o.y0 - b.y1 - 1)
            if (best != Int.MAX_VALUE && best < gapMax) y1 = expandEdge(b.y1, best, +1)
            else if ((h - 1 - b.y1) < gapMax) y1 = h - 1
            Box(x0, y0, x1, y1)
        }
    }

    /** gap>0；toward=-1 向左/上扩，+1 向右/下扩。单侧取 60%，且不越过缝中点（留呼吸缝） */
    private fun expandEdge(cur: Int, gap: Int, toward: Int): Int {
        val take = (gap * PanelTunables.SNAP_GAP_TAKE).toInt()
        val half = (gap - 2) / 2
        val d = minOf(take, maxOf(0, half))
        return cur + toward * d
    }

    private fun verticalOverlap(a: Box, b: Box): Boolean = a.y0 <= b.y1 && b.y0 <= a.y1
    private fun horizontalOverlap(a: Box, b: Box): Boolean = a.x0 <= b.x1 && b.x0 <= a.x1

    /** 阶段9：IoU 贪心 NMS（按面积降序，CPE 阈值 0.4） */
    internal fun nms(boxes: List<Box>): List<Box> {
        val sorted = boxes.sortedByDescending { it.area }
        val keep = ArrayList<Box>()
        for (b in sorted) {
            var suppressed = false
            for (k in keep) {
                val inter = b.interArea(k)
                val union = b.area + k.area - inter
                if (union > 0 && inter.toFloat() / union > PanelTunables.NMS_IOU) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) keep.add(b)
        }
        return keep
    }
}
