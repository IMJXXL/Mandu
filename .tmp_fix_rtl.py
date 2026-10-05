# -*- coding: utf-8 -*-
import io

# ---- CurlReaderContent.kt ----
p = r"app/src/main/java/com/mangareader/native/curl/CurlReaderContent.kt"
s = io.open(p, encoding="utf-8").read()

old = '''    // 对齐：alignX 输出即屏空间语义（RTL 自动=对齐右缘）；本层在"外层镜像+内层反镜像"里，
    // 内层定位要按镜像再翻一次（双层镜像抵消后 artwork 正立、起始缘落在屏侧读边）
    val biasInnerX = ScalePlan.alignX(zoomStart, isRtl).let { if (isRtl) -it else it }
    val align = biasToAlignment(biasInnerX, ScalePlan.alignY(zoomStart))'''
new = '''    // 对齐：alignX 输出即屏空间语义（RTL 自动=对齐右缘）。★ 内层偏置直接用屏语义——
    // 外层 PageCurl 镜像与本层反镜像作用在同一全宽区域上，对"布局位置"完全抵消
    // （净效果=恒等，只有 artwork 朝向被修正），所以定位不需要再翻一次；
    // 定位→镜像→还原 的往返推导见 2026-10-05 自查修正（此前误加 XOR 翻转，
    // RTL 下对齐边/缩放中心落到镜像错的一侧；LTR 无镜像不受影响）
    val biasX = ScalePlan.alignX(zoomStart, isRtl)
    val align = biasToAlignment(biasX, ScalePlan.alignY(zoomStart))'''
assert old in s, "single bias"
s = s.replace(old, new)

old = '''    // ★ 双击缩放层：套在镜像之后（clip→mirror→zoom→内容，溢出仍被外层 clip 裁住）；
    //   transformOrigin 的 x 在镜像空间要翻一次（1-fx）才能对准屏空间点按位置
    val zoomMod = if (zoom != null) Modifier.graphicsLayer {
        val zs = zoom.scale.floatValue
        scaleX = zs; scaleY = zs
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
            if (isRtl) 1f - zoom.originX.floatValue else zoom.originX.floatValue,
            zoom.originY.floatValue,
        )
    } else Modifier'''
new = '''    // ★ 双击缩放层：套在镜像之后（clip→mirror→zoom→内容，溢出仍被外层 clip 裁住）。
    //   镜像+外层镜像=恒等（位置抵消），transformOrigin 直接用屏空间点按位置即可
    //   （已知近似：「仅画面区域」有 inset 时按整视口归一化，原点略有偏移，仅视觉影响）
    val zoomMod = if (zoom != null) Modifier.graphicsLayer {
        val zs = zoom.scale.floatValue
        scaleX = zs; scaleY = zs
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
            zoom.originX.floatValue,
            zoom.originY.floatValue,
        )
    } else Modifier'''
assert old in s, "single zoom origin"
s = s.replace(old, new)

io.open(p, "w", encoding="utf-8", newline="").write(s)
print("single ok")

# ---- CurlDoublePageContent.kt ----
p2 = r"app/src/main/java/com/mangareader/native/curl/CurlDoublePageContent.kt"
s2 = io.open(p2, encoding="utf-8").read()

old = '''    // 内层偏置（同 SpreadImage）：alignX 输出为屏空间语义，镜像层内取反
    val biasX = ScalePlan.alignX(zoomStart, isRtl).let { if (isRtl) -it else it }'''
new = '''    // 屏空间偏置（同 SpreadImage）：双层镜像对位置抵消，直接用 alignX 屏语义（自查修正）
    val biasX = ScalePlan.alignX(zoomStart, isRtl)'''
assert old in s2, "spine bias"
s2 = s2.replace(old, new)

old = '''    // 对齐：alignX 输出为屏空间语义（RTL 自动=右缘）；本层内层反镜像，定位偏置按镜像取反
    val biasInnerX = ScalePlan.alignX(zoomStart, isRtl).let { if (isRtl) -it else it }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().clipToBounds().then(bgMod).then(mirror).then(zoomMod),
        contentAlignment = biasToAlignment(biasInnerX, ScalePlan.alignY(zoomStart)),
    ) {'''
new = '''    // 对齐：alignX 输出为屏空间语义（RTL 自动=右缘）；双层镜像对位置抵消，直接用（自查修正）
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().clipToBounds().then(bgMod).then(mirror).then(zoomMod),
        contentAlignment = biasToAlignment(ScalePlan.alignX(zoomStart, isRtl), ScalePlan.alignY(zoomStart)),
    ) {'''
assert old in s2, "spread bias"
s2 = s2.replace(old, new)

old = '''    // ★ 双击缩放层（同单页）：clip→…→mirror→zoom→内容；origin 在镜像空间 x 取反
    val zoomMod = if (zoom != null) Modifier.graphicsLayer {
        val zs = zoom.scale.floatValue
        scaleX = zs; scaleY = zs
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
            if (isRtl) 1f - zoom.originX.floatValue else zoom.originX.floatValue,
            zoom.originY.floatValue,
        )
    } else Modifier'''
new = '''    // ★ 双击缩放层（同单页）：clip→…→mirror→zoom→内容；双层镜像位置抵消，origin 用屏空间
    val zoomMod = if (zoom != null) Modifier.graphicsLayer {
        val zs = zoom.scale.floatValue
        scaleX = zs; scaleY = zs
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
            zoom.originX.floatValue,
            zoom.originY.floatValue,
        )
    } else Modifier'''
assert old in s2, "spread zoom origin"
s2 = s2.replace(old, new)

io.open(p2, "w", encoding="utf-8", newline="").write(s2)
print("double ok")
