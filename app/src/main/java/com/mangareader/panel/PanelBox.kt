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
    /** 跨格气泡合并组号：同组分格属于同一气泡跨越，显示时一起呈现；null=独立 */
    val group: Int? = null,
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
    const val FULL_PAGE_AREA = 0.92f
    /** 自研模式整页判定阈值（b4648d9 旧值） */
    const val FULL_PAGE_AREA_SELF = 0.85f
    /** 页置信度低于此值 → 整页单格降级 */
    const val CONF_FAIL = 0.5f

    fun fullPageArea(engineMode: Int): Float = if (engineMode == 0) FULL_PAGE_AREA_SELF else FULL_PAGE_AREA

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

    data class PrecisionParams(
        val maxSide: Int,
        val bgSampleRatio: Float,
        val frameTolerance: Int,
        val frameMinSides: Int,
    )

    fun precisionParams(precision: Int): PrecisionParams = when (precision) {
        0 -> PrecisionParams(maxSide = 720, bgSampleRatio = 0.05f, frameTolerance = 3, frameMinSides = 3)
        else -> PrecisionParams(maxSide = 960, bgSampleRatio = 0.02f, frameTolerance = 2, frameMinSides = 4)
    }

    // —— 彩色背景估计（PanelBackground RGB 三通道中位数 + 最大通道差）——
    const val RGB_DELTA = 30
    const val RGB_SAMPLE_RATIO = 0.05f
    /** 彩色背景判定：三通道极差 ≥ 此值视为彩色背景（非灰度页） */
    const val RGB_COLOUR_SPREAD = 18

    // —— 相连框拆分（PanelSplitJoined）——
    const val SPLIT_MAX_DEPTH = 6
    /** 自研模式拆分深度上限（b4648d9 旧值） */
    const val SPLIT_MAX_DEPTH_SELF = 4

    fun splitMaxDepth(engineMode: Int): Int = if (engineMode == 0) SPLIT_MAX_DEPTH_SELF else SPLIT_MAX_DEPTH
    const val SPLIT_BAND_RATIO = 0.035f
    const val SPLIT_CONTENT_RATIO = 0.75f
    const val SPLIT_LARGE_AREA_RATIO = 0.1f
    /** 共享黑色边框判定：投影值 ≥ span×此值视为黑边 */
    const val SPLIT_BLACK_LINE_RATIO = 0.88f
    /** 分隔带距投影两端的最低裕度（占 len 比例），避免把外边框误判为内部共享边框 */
    const val SPLIT_EDGE_MARGIN = 0.15f

    // —— 倾斜剪切（PanelShearSplit）——
    val SHEAR_ANGLES = listOf(-8, -6, -4, -2, 2, 4, 6, 8)
    const val SHEAR_CHILD_AREA_MIN = 0.15f
    /** 剪切后空白带投影阈值（占 span 比例） */
    const val SHEAR_BAND_RATIO = 0.06f

    // —— 小图满版接受（PanelSegmenter ink 占用）——
    const val INK_AREA_THRESHOLD = 0.6f
    const val INK_OCCUPY_THRESHOLD = 0.7f

    // —— 家具页密度对比（PanelSegmenter looksLikeFurniture）——
    const val FURNITURE_DENSITY_RATIO = 2.5f

    // —— 折痕边缘修剪（PanelFoldJoin trimEdges）——
    const val FOLD_TRIM_COLUMNS = 3

    // —— 分段器参数（PanelsPlus segment_* 对齐）——
    /** 单页面板数上限，超出降为整页单格 */
    const val SEGMENT_MAX_PANELS = 40
    /** 窄长条判定：宽高比 ≥ 此值视为 sliver（竖条/横条面板） */
    const val SEGMENT_SLIVER_ASPECT = 4
    /** sliver 墨水占比下限，低于此值视为空白缝隙而非面板 */
    const val SEGMENT_SLIVER_INK = 0.02f
    /** 面板合集覆盖页面面积下限，低于此值触发降级 */
    const val SEGMENT_PAGE_COVERAGE_MIN = 0.4f
    /** 单面板面积 ≥ 此比例 → 直接整页单格（跳过分割） */
    const val SEGMENT_SINGLE_PANEL_RATIO = 0.6f
    /** 装订沟宽度占页宽比例（跨页中缝检测） */
    const val SEGMENT_GUTTER_RATIO = 0.005f
    /** 沟内墨水占比上限，低于此值确认为空白沟 */
    const val SEGMENT_GUTTER_INK_RATIO = 0.05f
    /** 面板最小边长占短边比例（PanelsPlus segment_min_panel_side） */
    const val SEGMENT_MIN_PANEL_SIDE = 0.03f
    /** 面板最小面积占比（PanelsPlus segment_min_panel_area） */
    const val SEGMENT_MIN_PANEL_AREA = 0.01f

    // —— 边框分割（PanelsPlus border split pass，T6）——
    /** 边框像素亮度上限（0~255），低于此值视为黑边框 */
    const val SEGMENT_BORDER_LUMINANCE_MAX = 60
    /** 边框线投影占比下限，≥ 此值确认为连续边框线 */
    const val SEGMENT_BORDER_LINE_RATIO = 0.97f
    /** 边框宽度占短边比例（估算框线粗细） */
    const val SEGMENT_BORDER_WIDTH_RATIO = 0.01f
}
