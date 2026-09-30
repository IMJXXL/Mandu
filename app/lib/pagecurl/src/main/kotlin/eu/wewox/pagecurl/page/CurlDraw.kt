// Kamigura fork of oleksandrbalan/pagecurl v1.5.1 (Apache-2.0).
// Modifications: added drawCurlFront/drawCurlBack so a custom composable (the real
// incoming page, not a mirrored copy of the front) can be rendered on the back face.
package eu.wewox.pagecurl.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.utils.Polygon
import eu.wewox.pagecurl.utils.lineLineIntersection
import eu.wewox.pagecurl.utils.rotate
import java.lang.Float.max
import kotlin.math.atan2

@ExperimentalPageCurlApi
internal fun Modifier.drawCurl(
    config: PageCurlConfig,
    edge: () -> Edge,
): Modifier = drawWithCache {
    val posA = edge().top
    val posB = edge().bottom
    // Fast-check if curl is in left most position (gesture is fully completed)
    // In such case do not bother and draw nothing
    if (posA == size.toRect().topLeft && posB == size.toRect().bottomLeft) {
        return@drawWithCache drawNothing()
    }

    // Fast-check if curl is in right most position (gesture is not yet started)
    // In such case do not bother and draw the full content
    if (posA == size.toRect().topRight && posB == size.toRect().bottomRight) {
        return@drawWithCache drawOnlyContent()
    }

    // Find the intersection of the curl line ([posA, posB]) and top and bottom sides, so that we may clip and mirror
    // content correctly
    val topIntersection = lineLineIntersection(
        Offset(0f, 0f), Offset(size.width, 0f),
        posA, posB
    )
    val bottomIntersection = lineLineIntersection(
        Offset(0f, size.height), Offset(size.width, size.height),
        posA, posB
    )

    // Should not really happen, but in case there is not intersection (curl line is horizontal), just draw the full
    // content instead
    if (topIntersection == null || bottomIntersection == null) {
        return@drawWithCache drawOnlyContent()
    }

    // ★ 2026-09-30：不再钳位交点——钳位会让"折线"偏离真实折叠线（倾斜相交画布左/右边时），
    //   多边形随之切错位置露出下层原画（右上角纹块实锤）；全部几何改由 rectClipByLine 用真实折线计算
    val topCurlOffset = topIntersection
    val bottomCurlOffset = bottomIntersection

    // That is the easy part, prepare a lambda to draw the content clipped by the curl line
    val drawClippedContent = prepareClippedContent(topCurlOffset, bottomCurlOffset)
    // That is the tricky part, prepare a lambda to draw the back-page with the shadow
    val drawCurl = prepareCurl(config, topCurlOffset, bottomCurlOffset)

    onDrawWithContent {
        drawClippedContent()
        drawCurl()
    }
}

/**
 * Kamigura fork: draws only the front side of the curled page (clipped content + the
 * cast shadow of the flap). Pair with [drawCurlBack] on a sibling node that holds the
 * back-face content. Keeping the shadow here places it under the back flap but over
 * the page beneath, matching the original z-order.
 */
@ExperimentalPageCurlApi
internal fun Modifier.drawCurlFront(
    config: PageCurlConfig,
    edge: () -> Edge,
): Modifier = drawWithCache {
    val posA = edge().top
    val posB = edge().bottom
    if (posA == size.toRect().topLeft && posB == size.toRect().bottomLeft) {
        return@drawWithCache drawNothing()
    }
    if (posA == size.toRect().topRight && posB == size.toRect().bottomRight) {
        return@drawWithCache drawOnlyContent()
    }

    val topIntersection = lineLineIntersection(
        Offset(0f, 0f), Offset(size.width, 0f),
        posA, posB
    )
    val bottomIntersection = lineLineIntersection(
        Offset(0f, size.height), Offset(size.width, size.height),
        posA, posB
    )
    if (topIntersection == null || bottomIntersection == null) {
        return@drawWithCache drawOnlyContent()
    }

    // ★ 不钳位（同 drawCurl）：真实折线喂给 rectClipByLine，正/背面严格互补
    val topCurlOffset = topIntersection
    val bottomCurlOffset = bottomIntersection

    val drawClippedContent = prepareClippedContent(topCurlOffset, bottomCurlOffset)
    val drawShadow = prepareCurlShadowOnly(config, topCurlOffset, bottomCurlOffset)

    onDrawWithContent {
        drawClippedContent()
        drawShadow()
    }
}

/**
 * Kamigura fork: draws only the back face of the curl flap using this node's own
 * content instead of a mirror of the front page.
 *
 * Contract: the content of this node must be laid out at the position it will occupy
 * once the page has fully landed (upright, as the reader will see it). The modifier
 * applies the extra centre mirror internally so that mid-fold the content unrolls from
 * the flap exactly like the printed back of a real sheet.
 */
@ExperimentalPageCurlApi
internal fun Modifier.drawCurlBack(
    config: PageCurlConfig,
    edge: () -> Edge,
): Modifier = drawWithCache {
    val posA = edge().top
    val posB = edge().bottom
    if (posA == size.toRect().topLeft && posB == size.toRect().bottomLeft) {
        return@drawWithCache drawNothing()
    }
    // Unlike the front, a flat (not yet started) page has no visible back face.
    if (posA == size.toRect().topRight && posB == size.toRect().bottomRight) {
        return@drawWithCache drawNothing()
    }

    val topIntersection = lineLineIntersection(
        Offset(0f, 0f), Offset(size.width, 0f),
        posA, posB
    )
    val bottomIntersection = lineLineIntersection(
        Offset(0f, size.height), Offset(size.width, size.height),
        posA, posB
    )
    if (topIntersection == null || bottomIntersection == null) {
        return@drawWithCache drawNothing()
    }

    // ★ 不钳位（同 drawCurl）：真实折线喂给 rectClipByLine，正/背面严格互补
    val topCurlOffset = topIntersection
    val bottomCurlOffset = bottomIntersection

    val polygon = backPagePolygon(topCurlOffset, bottomCurlOffset)
    val lineVector = topCurlOffset - bottomCurlOffset
    val angle = Math.PI.toFloat() - atan2(lineVector.y, lineVector.x) * 2
    val center = Offset(size.width / 2f, size.height / 2f)

    onDrawWithContent {
        withTransform({
            scale(-1f, 1f, pivot = bottomCurlOffset)
            rotateRad(angle, pivot = bottomCurlOffset)
        }) {
            clipPath(polygon.toPath()) {
                // ★ 纸背（0930 终版）：镜像内容全量画 + 半透明纸盖上——
                //   合成 = (1-a)纸 + a内容（a=背面透出程度），无图层边界（根治裸纹理块）
                //   且省每帧离屏图层分配；透出滑条=内容可见度，a=1 纸全透明，a=0 纯纸。
                val bmp = config.backPageBitmap
                if (bmp != null) {
                    // ★ 透出实现（0930 终版）：先镜像内容全量画，再以半透明纸盖上去——
                    //   合成结果 0.9纸+0.1内容 与 saveLayer 方案完全等价，但无图层边界，
                    //   根治"边界×fold变换交互裁出全强度裸纹理块"（右上角实锤两次）。
                    //   透出程度滑条 = 内容可见度：contentAlpha=1 → 纸全透明；=0 → 纯纸。
                    // ★ contentAlpha<=0（纸背纯色开关强制 0 / 滑条拉到 0）跳过内容绘制——
                    //   背面=纯纸/纯色，省掉镜像内容整页重绘（翻页背面最大开销）
                    if (config.backPageContentAlpha > 0f) this@onDrawWithContent.drawContent()
                    drawImage(
                        bmp,
                        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                        alpha = (1f - config.backPageContentAlpha).coerceIn(0f, 1f),
                    )
                } else {
                    // ★ 纯色分支：contentAlpha<=0（纸背纯色开关）→ 只画纯色，内容也不画
                    if (config.backPageContentAlpha > 0f) {
                        this@onDrawWithContent.drawContent()
                    }

                    val overlayAlpha = 1f - config.backPageContentAlpha
                    if (overlayAlpha > 0f) {
                        drawRect(config.backPageColor.copy(alpha = overlayAlpha))
                    }
                }
            }
        }
    }
}

/**
 * The simple method to draw the whole unmodified content.
 */
private fun CacheDrawScope.drawOnlyContent(): DrawResult =
    onDrawWithContent {
        drawContent()
    }

/**
 * The simple method to draw nothing.
 */
private fun CacheDrawScope.drawNothing(): DrawResult =
    onDrawWithContent {
        /* Empty */
    }

@ExperimentalPageCurlApi
private fun CacheDrawScope.prepareClippedContent(
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): ContentDrawScope.() -> Unit {
    // ★ 正面区域 = 折线另一侧（与背面多边形同一几何源，严格互补无缝隙）
    val path = Path()
    rectClipByLine(topCurlOffset, bottomCurlOffset, keepTopRight = false).forEachIndexed { i, p ->
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    return result@{
        // Draw a content clipped by the constructed path
        clipPath(path) {
            this@result.drawContent()
        }
    }
}

/**
 * Kamigura fork: extracted from [prepareCurl] so [drawCurlBack] can reuse it.
 * Build a quadrilateral of the part of the page which should be mirrored as the back-page
 * In all cases polygon should have 4 points, even when back-page is only a small "corner" (with 3 points) due to
 * the shadow rendering, otherwise it will create a visual artifact when switching between 3 and 4 points polygon
 */
/**
 * ★ 2026-09-30 修翻页角落露底（用户截图实锤）：原多边形用钳位后的交点构造，
 *   折叠线倾斜到与画布左/右边相交时（翻转中后段常态）"折线"偏离真实折叠线，
 *   背面多边形切进翻转视觉区域——没盖住的角落露出下层页面原画（RTL 右上角全强度纹块）。
 *   改为：用未钳位的真实折叠线对画布矩形做半平面裁切（Sutherland–Hodgman），
 *   keepSide 参照点决定保哪一侧——正面/背面区域严格互补，任何翻转位姿无缝隙。
 */
private fun CacheDrawScope.rectClipByLine(
    lineTop: Offset,
    lineBottom: Offset,
    keepTopRight: Boolean,
    expandForMirror: Boolean = false,
): List<Offset> {
    val w = size.width
    val h = size.height
    // ★ 背面多边形的采样域扩一圈（expandForMirror）：折叠线斜穿画布边缘时，镜像的源像素
    //   只有画布内的部分，越界那块的镜像=空——缺口露出下层原画（右上角纹块实锤）。
    //   扩域后镜像有源可采，缺口被纸背盖住。正面裁剪不需要（平面内互补即可）。
    val ex = if (expandForMirror) w else 0f
    val ey = if (expandForMirror) h else 0f
    // 折叠线方向：lineTop→lineBottom（画布坐标 y 向下）
    val ax = lineBottom.x - lineTop.x
    val ay = lineBottom.y - lineTop.y
    fun cross(p: Offset): Float = ax * (p.y - lineTop.y) - ay * (p.x - lineTop.x)
    // 参照点定侧：折叠线中点右侧 (w, (top.y+bottom.y)/2) 恒属背面区域
    val ref = Offset(w, (lineTop.y + lineBottom.y) / 2f)
    val refCross = cross(ref)
    if (kotlin.math.abs(refCross) < 0.001f) {
        // 折线恰过参照点（= 右缘静止态）：背面零面积，正面满幅
        return if (keepTopRight) emptyList()
        else listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
    }
    val wantPositive = if (keepTopRight) refCross > 0f else refCross < 0f
    fun inside(p: Offset): Boolean {
        val c = cross(p)
        return if (wantPositive) c >= 0f else c <= 0f
    }
    fun intersect(p: Offset, q: Offset): Offset {
        val cp = cross(p); val cq = cross(q)
        val t = cp / (cp - cq)
        return Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
    }
    val subject = listOf(Offset(-ex, -ey), Offset(w + ex, -ey), Offset(w + ex, h + ey), Offset(-ex, h + ey))
    val out = buildList {
        for (i in subject.indices) {
            val cur = subject[i]
            val nxt = subject[(i + 1) % subject.size]
            val cIn = inside(cur); val nIn = inside(nxt)
            if (cIn) add(cur)
            if (cIn != nIn) add(intersect(cur, nxt))
        }
    }
    return out.ifEmpty {
        if (keepTopRight) emptyList()
        else listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
    }
}

private fun CacheDrawScope.backPagePolygon(
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): Polygon = Polygon(rectClipByLine(topCurlOffset, bottomCurlOffset, keepTopRight = true, expandForMirror = true))

/**
 * Kamigura fork: shadow of the flap without the back-page content, used by [drawCurlFront].
 */
@ExperimentalPageCurlApi
private fun CacheDrawScope.prepareCurlShadowOnly(
    config: PageCurlConfig,
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): ContentDrawScope.() -> Unit {
    val polygon = backPagePolygon(topCurlOffset, bottomCurlOffset)
    val lineVector = topCurlOffset - bottomCurlOffset
    val angle = Math.PI.toFloat() - atan2(lineVector.y, lineVector.x) * 2
    val drawShadow = prepareShadow(config, polygon, angle)

    return result@{
        withTransform({
            scale(-1f, 1f, pivot = bottomCurlOffset)
            rotateRad(angle, pivot = bottomCurlOffset)
        }) {
            this@result.drawShadow()
        }
    }
}

@ExperimentalPageCurlApi
private fun CacheDrawScope.prepareCurl(
    config: PageCurlConfig,
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): ContentDrawScope.() -> Unit {
    val polygon = backPagePolygon(topCurlOffset, bottomCurlOffset)

    // Calculate the angle in radians between X axis and the curl line, this is used to rotate mirrored content to the
    // right position of the curled back-page
    val lineVector = topCurlOffset - bottomCurlOffset
    val angle = Math.PI.toFloat() - atan2(lineVector.y, lineVector.x) * 2
    val center = Offset(size.width / 2f, size.height / 2f)

    // Prepare a lambda to draw the shadow of the back-page
    val drawShadow = prepareShadow(config, polygon, angle)

    return result@{
        CurlGeomProbe.mark(
            "curlgeom node=${size.width.toInt()}x${size.height.toInt()}" +
                " bmp=${config.backPageBitmap?.let { "${it.width}x${it.height}" } ?: "-"}" +
                " a=${config.backPageContentAlpha}" +
                " pivot=(${bottomCurlOffset.x.toInt()},${bottomCurlOffset.y.toInt()})" +
                " poly=${polygon.vertices.joinToString(prefix = "[", postfix = "]") { "(${it.x.toInt()},${it.y.toInt()})" }}"
        )
        withTransform({
            // Mirror in X axis the drawing as back-page should be mirrored
            scale(-1f, 1f, pivot = bottomCurlOffset)
            // Rotate the drawing according to the curl line
            rotateRad(angle, pivot = bottomCurlOffset)
        }) {
            // Draw shadow first
            this@result.drawShadow()

            // And finally draw the back-page with an overlay with alpha
            clipPath(polygon.toPath()) {
                // ★ 同块1：镜像内容全量 + 半透明纸盖上（无 saveLayer，见块1注释）
                val bmp = config.backPageBitmap
                if (bmp != null) {
                    // ★ 同块1：镜像内容全量 + 半透明纸盖上（无 saveLayer，见块1注释）
                    this@result.drawContent()
                    drawImage(
                        bmp,
                        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                        alpha = (1f - config.backPageContentAlpha).coerceIn(0f, 1f),
                    )
                } else {
                    if (config.backPageContentAlpha > 0f) {
                        this@result.drawContent()
                    }

                    val overlayAlpha = 1f - config.backPageContentAlpha
                    drawRect(config.backPageColor.copy(alpha = overlayAlpha))
                }
            }
        }
    }
}

@ExperimentalPageCurlApi
private fun CacheDrawScope.prepareShadow(
    config: PageCurlConfig,
    polygon: Polygon,
    angle: Float
): ContentDrawScope.() -> Unit {
    // Quick exit if no shadow is requested
    if (config.shadowAlpha == 0f || config.shadowRadius == 0.dp) {
        return { /* No shadow is requested */ }
    }

    // Prepare shadow parameters
    val radius = config.shadowRadius.toPx()
    val shadowColor = config.shadowColor.copy(alpha = config.shadowAlpha).toArgb()
    val transparent = config.shadowColor.copy(alpha = 0f).toArgb()
    val shadowOffset = Offset(-config.shadowOffset.x.toPx(), config.shadowOffset.y.toPx())
        .rotate(2 * Math.PI.toFloat() - angle)

    // Prepare shadow paint with a shadow layer
    val paint = Paint().apply {
        val frameworkPaint = asFrameworkPaint()
        frameworkPaint.color = transparent
        frameworkPaint.setShadowLayer(
            config.shadowRadius.toPx(),
            shadowOffset.x,
            shadowOffset.y,
            shadowColor
        )
    }

    // Hardware acceleration supports setShadowLayer() only on API 28 and above, thus to support previous API versions
    // draw a shadow to the bitmap instead
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        prepareShadowApi28(radius, paint, polygon)
    } else {
        prepareShadowImage(radius, paint, polygon)
    }
}

private fun prepareShadowApi28(
    radius: Float,
    paint: Paint,
    polygon: Polygon,
): ContentDrawScope.() -> Unit = {
    drawIntoCanvas {
        it.nativeCanvas.drawPath(
            polygon
                .offset(radius).toPath()
                .asAndroidPath(),
            paint.asFrameworkPaint()
        )
    }
}

private fun CacheDrawScope.prepareShadowImage(
    radius: Float,
    paint: Paint,
    polygon: Polygon,
): ContentDrawScope.() -> Unit {
    // Increase the size a little bit so that shadow is not clipped
    val bitmap = Bitmap.createBitmap(
        (size.width + radius * 4).toInt(),
        (size.height + radius * 4).toInt(),
        Bitmap.Config.ARGB_8888
    )
    Canvas(bitmap).apply {
        drawPath(
            polygon
                // As bitmap size is increased we should translate the polygon so that shadow remains in center
                .translate(Offset(2 * radius, 2 * radius))
                .offset(radius).toPath()
                .asAndroidPath(),
            paint.asFrameworkPaint()
        )
    }

    return {
        drawIntoCanvas {
            // As bitmap size is increased we should shift the drawing so that shadow remains in center
            it.nativeCanvas.drawBitmap(bitmap, -2 * radius, -2 * radius, null)
        }
    }
}
