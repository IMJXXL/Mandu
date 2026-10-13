package com.mangareader.panel

internal object PanelSegmenter {

    private const val MAX_CANDIDATES = 40
    private const val SINGLE_COVER_RATIO = 0.6f
    private const val MULTI_SPAN_RATIO = 0.4f
    private const val MULTI_AREA_RATIO = 0.5f
    private const val FURNITURE_MIN_COUNT = 6
    private const val FURNITURE_STRIP_H = 0.9f
    private const val FURNITURE_STRIP_W_LO = 0.2f
    private const val FURNITURE_STRIP_W_HI = 0.5f
    private const val FURNITURE_MINOR_AREA_RATIO = 0.3f

    internal fun accept(boxes: List<PanelComponents.Box>, w: Int, h: Int, inkMask: BooleanArray? = null): Boolean {
        if (boxes.isEmpty()) return false
        if (boxes.size > MAX_CANDIDATES) return false
        val pageArea = w.toFloat() * h
        if (boxes.size == 1) {
            val areaRatio = boxes[0].area / pageArea
            if (areaRatio >= SINGLE_COVER_RATIO) return true
            if (inkMask != null && inkMask.size == w * h && areaRatio < PanelTunables.INK_AREA_THRESHOLD) {
                return inkOccupation(boxes[0], w, inkMask) >= PanelTunables.INK_OCCUPY_THRESHOLD
            }
            return false
        }
        if (looksLikeFurniture(boxes, w, h, inkMask)) return false
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var totalArea = 0
        for (b in boxes) {
            totalArea += b.area
            if (b.x0 < minX) minX = b.x0
            if (b.y0 < minY) minY = b.y0
            if (b.x1 > maxX) maxX = b.x1
            if (b.y1 > maxY) maxY = b.y1
        }
        val coverW = (maxX - minX + 1).toFloat()
        val coverH = (maxY - minY + 1).toFloat()
        val coveredArea = coverW * coverH
        if (coveredArea / pageArea < MULTI_SPAN_RATIO) return false
        if (coveredArea > 0 && totalArea.toFloat() / coveredArea < MULTI_AREA_RATIO) return false
        return true
    }

    private fun looksLikeFurniture(boxes: List<PanelComponents.Box>, w: Int, h: Int, inkMask: BooleanArray? = null): Boolean {
        if (boxes.size < FURNITURE_MIN_COUNT) return false
        val pw = w.toFloat()
        val ph = h.toFloat()
        var dominantIndex = -1
        var dominant: PanelComponents.Box? = null
        for (i in boxes.indices) {
            val b = boxes[i]
            if (b.h >= ph * FURNITURE_STRIP_H &&
                b.w >= pw * FURNITURE_STRIP_W_LO &&
                b.w <= pw * FURNITURE_STRIP_W_HI
            ) {
                if (dominant == null || b.area > dominant.area) {
                    dominant = b
                    dominantIndex = i
                }
            }
        }
        if (dominant == null) return false
        val d = dominant
        val stripMid = d.x0 + d.w / 2f
        val stripOnLeft = stripMid < pw / 2
        for (i in boxes.indices) {
            if (i == dominantIndex) continue
            val b = boxes[i]
            if (stripOnLeft) {
                if (b.x0 < d.x0 + d.w * 0.9f) return false
            } else {
                if (b.x1 > d.x0 + d.w * 0.1f) return false
            }
        }
        var minorArea = 0
        for (i in boxes.indices) {
            if (i != dominantIndex) minorArea += boxes[i].area
        }
        if (minorArea.toFloat() / d.area >= FURNITURE_MINOR_AREA_RATIO) return false
        if (inkMask != null && inkMask.size == w * h) {
            val dDensity = inkDensity(d, w, inkMask)
            if (dDensity <= 0f) return false
            for (i in boxes.indices) {
                if (i == dominantIndex) continue
                val mDensity = inkDensity(boxes[i], w, inkMask)
                if (mDensity > 0f && dDensity < mDensity * PanelTunables.FURNITURE_DENSITY_RATIO) return false
            }
        }
        return true
    }

    private fun inkOccupation(box: PanelComponents.Box, w: Int, inkMask: BooleanArray): Float {
        var ink = 0
        for (y in box.y0..box.y1) {
            val row = y * w
            for (x in box.x0..box.x1) if (inkMask[row + x]) ink++
        }
        return ink.toFloat() / box.area
    }

    private fun inkDensity(box: PanelComponents.Box, w: Int, inkMask: BooleanArray): Float {
        return inkOccupation(box, w, inkMask)
    }
}