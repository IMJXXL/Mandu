package com.mangareader.panel

/**
 * 逐格引导阅读（Panel-by-Panel）数据模型。
 * 坐标一律为页面归一化 0~1（与显示分辨率/缩放解耦，跨设备/旋转稳定——评估报告 §六）。
 */
data class PanelBox(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** 阅读顺序 0 起（已按 LTR/RTL 排好） */
    val order: Int,
    /** 格级置信度 0~1 */
    val conf: Float,
) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
}

/** 单页检测结果；fallback=true 表示该页识别失败，按整页单格降级（评估报告 §五 降级规则） */
data class PanelPageResult(
    val boxes: List<PanelBox>,
    val pageConf: Float,
    val fallback: Boolean,
)

/**
 * 管线全部可调参数（冻结模块设计见 docs/逐格阅读-实施与进度.md；调优只动这里，禁止散落魔数）。
 * 各参数出处见评估报告 §3.2/§五：panelzoom(MIT) 尺寸过滤、CPE(GPL 思路) 二值化自检与 DPI 无关核、
 * kumiko(AGPL 思路) 碎片归组/去重叠/中缝外扩、Seeneva(GPL 思路) 模糊容差——实现均为全新 Kotlin。
 */
object PanelTunables {
    /** 检测缩样上限（长边，性能预算 30~50ms/页的依据） */
    const val MAX_SIDE = 960

    // —— 二值化（PanelBinarize）——
    /** 白占比健康区间；越界触发自适应回退（发黄/受潮/黑底扫描） */
    const val WHITE_RATIO_LO = 0.15f
    const val WHITE_RATIO_HI = 0.85f
    /** 自适应均值窗口边长（检测分辨率下固定值，blk 随缩样天然 DPI 无关） */
    const val ADAPTIVE_BLOCK = 31
    const val ADAPTIVE_C = 5
    /** 闭运算方核 = max(3, round(此比例 × 短边))，弥合断裂边框 */
    const val CLOSE_KERNEL_RATIO = 0.003f

    // —— 连通域与框级过滤（PanelComponents）——
    /** 面板候选：宽高均 ≥ 页面此比例（panelzoom 默认 1/8） */
    const val MIN_DIM_RATIO = 0.125f
    /** 候选最小面积占比（低于则降为碎片） */
    const val MIN_AREA_RATIO = 0.02f
    /** 碎片留池门槛（面积占比），防噪点进碎片池 */
    const val FRAG_KEEP_AREA = 0.0005f
    /** 碎片归组：各自外扩此比例·长边后相交即合并（无边框格聚合，kumiko is_close 矩形化） */
    const val FRAG_GROUP_EXPAND = 0.012f
    /** 包含合并：交集/小框面积 > 此值弃小框（气泡/装饰误分，kumiko） */
    const val CONTAIN_MERGE = 0.5f
    /** 重叠丢弃：交集 > 小框面积×此值直接弃小（CPE 风格强去重） */
    const val OVERLAP_DROP = 0.9f
    /** IoU-NMS 阈值（CPE） */
    const val NMS_IOU = 0.4f
    /** 中缝外扩：仅当邻缝 < 页面此比例才扩（kumiko expand_panels 保守矩形版） */
    const val SNAP_GAP_MAX = 0.06f
    /** 每次外扩量 = 缝宽 × 此值 */
    const val SNAP_GAP_TAKE = 0.6f
    /** 外扩后与邻居保留的呼吸缝（占长边比例），防框间粘连 */
    const val SNAP_BREATH = 0.004f

    // —— 整页规则与置信度（PanelConfidence）——
    /** 唯一框面积 ≥ 此比例 → 整页单格（满版/跨页大图保护，不视为失败） */
    const val FULL_PAGE_AREA = 0.85f
    /** 页置信度低于此值 → 整页单格降级 */
    const val CONF_FAIL = 0.5f

    /**
     * ★ 灵敏度档位 → 页置信度门槛（设置面板映射，唯一出处）：
     *   0=宽松（弱分格/彩漫页更易通过，可能误分） 1=标准 2=严格（更保守，宁整页勿误分）
     */
    fun confFailFor(sensitivity: Int): Float = when (sensitivity) {
        0 -> 0.35f
        2 -> 0.65f
        else -> CONF_FAIL
    }

    // —— 阅读顺序（PanelOrder）——
    /** 垂直重叠 ≥ 较矮框此比例 → 归同行 */
    const val ROW_OVERLAP = 0.5f
    /** 行容差 = 此比例 × 中位格高（CPE，防一条大格压扁行判定） */
    const val ROW_TOL_MEDIAN = 0.5f
    /** y 模糊容差（占页高）：差值内视为"近似同行"，交给修正回路（Seeneva 模糊比较器思路） */
    const val FUZZ_Y_TOL = 0.06f
    /** 矩形健康宽高比区间（CPE） */
    const val ASPECT_LO = 0.15f
    const val ASPECT_HI = 6.5f
}
