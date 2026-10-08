package com.mangareader.native

import android.graphics.RectF

enum class NavigationAction { PREV, NEXT, MENU }

data class NavigationRegion(val rect: RectF, val action: NavigationAction)

sealed class ViewerNavigation {
    abstract val regions: List<NavigationRegion>

    fun getAction(x: Float, y: Float): NavigationAction {
        if (y < 0.05f) return NavigationAction.MENU
        for (region in regions) {
            if (region.rect.contains(x, y)) return region.action
        }
        return NavigationAction.MENU
    }
}

/** 默认：左33%=上一页，中33%=菜单，右33%=下一页 */
object DefaultNavigation : ViewerNavigation() {
    override val regions = listOf(
        NavigationRegion(RectF(0f, 0f, 0.33f, 1f), NavigationAction.PREV),
        NavigationRegion(RectF(0.33f, 0f, 0.67f, 1f), NavigationAction.MENU),
        NavigationRegion(RectF(0.67f, 0f, 1f, 1f), NavigationAction.NEXT)
    )
}

/** 关闭点击翻页：全屏均切换 UI */
object AllMenuNavigation : ViewerNavigation() {
    override val regions = emptyList<NavigationRegion>()
}

/** L 型（对齐 mihon LNavigation）：上 1/3 横条 + 左中 1/3 竖条 = 上一页，
 *  下 1/3 横条 + 右中 1/3 竖条 = 下一页，正中 = 菜单。拇指自然区在下方=翻页。 */
object LNavigation : ViewerNavigation() {
    override val regions = listOf(
        NavigationRegion(RectF(0f, 0f, 1f, 0.33f), NavigationAction.PREV),
        NavigationRegion(RectF(0f, 0.33f, 0.33f, 0.67f), NavigationAction.PREV),
        NavigationRegion(RectF(0.67f, 0.33f, 1f, 0.67f), NavigationAction.NEXT),
        NavigationRegion(RectF(0f, 0.67f, 1f, 1f), NavigationAction.NEXT),
    )
}

/** 水平对调包装（左手模式）：区域矩形按 fx→1-fx 镜像，动作不变——
 *  原左侧=PREV 的区域镜像到右侧，点右侧即 PREV（对齐 mihon TappingInvertMode.HORIZONTAL）。 */
class InvertedNavigation(val base: ViewerNavigation) : ViewerNavigation() {
    override val regions = base.regions.map {
        NavigationRegion(RectF(1f - it.rect.right, it.rect.top, 1f - it.rect.left, it.rect.bottom), it.action)
    }
}

/** ★ §34.3：VERT_PAGER 翻页导航——竖向翻页用全宽三分纵切：
 *  上 40%=上一页，中 20%=菜单，下 40%=下一页。
 *  与横向 DefaultNavigation 区别：fx 不分区，只用 fy；上方不保留跳 MENU 的边。 */
object VerticalPagerNavigation : ViewerNavigation() {
    override val regions = listOf(
        NavigationRegion(RectF(0f, 0f, 1f, 0.4f), NavigationAction.PREV),
        NavigationRegion(RectF(0f, 0.4f, 1f, 0.6f), NavigationAction.MENU),
        NavigationRegion(RectF(0f, 0.6f, 1f, 1f), NavigationAction.NEXT)
    )
}

/** 边沿（对齐 mihon EdgeNavigation"两侧前进"型）：左 1/3 与右 1/3 = 前进，
 *  中下 1/3 = 后退，其余（含顶条）= 菜单。mihon 用绝对 NEXT/PREV，此处沿用本工程
 *  阅读序语义（NEXT/PREV 随 RTL 翻转）——LTR 下与 mihon 完全一致，RTL 下方向正确。 */
object EdgeNavigation : ViewerNavigation() {
    override val regions = listOf(
        NavigationRegion(RectF(0f, 0f, 0.33f, 1f), NavigationAction.NEXT),
        NavigationRegion(RectF(0.67f, 0f, 1f, 1f), NavigationAction.NEXT),
        NavigationRegion(RectF(0.33f, 0.66f, 0.67f, 1f), NavigationAction.PREV),
    )
}

/** Kindle 型（对齐 mihon KindlishNavigation）：左 1/3 = 上一页，右 2/3 = 下一页，
 *  顶条与未覆盖区 = 菜单（mihon 顶部为死区，此处改良为菜单，菜单永远可达）。 */
object KindlishNavigation : ViewerNavigation() {
    override val regions = listOf(
        NavigationRegion(RectF(0f, 0.33f, 0.33f, 1f), NavigationAction.PREV),
        NavigationRegion(RectF(0.33f, 0.33f, 1f, 1f), NavigationAction.NEXT),
    )
}

/** ★ 导航模式 Int→实现 映射唯一入口（2026-10-05 抽出）：PagerReaderView/CurlReaderView/
 *  CurlDoublePageView 三处重复 when 收敛于此，新增模式只改这一处。
 *  Int 沿用 ClickFlipType.ordinal：0=关闭(全屏菜单) 1=左右分区 4=L型 5=边沿 6=Kindlish；
 *  竖向翻页恒用纵切预设（fx 无意义），对调只对横向有意义。 */
fun buildNavigation(type: Int, invertH: Boolean, verticalPager: Boolean): ViewerNavigation {
    val base = when (type) {
        0 -> AllMenuNavigation
        4 -> LNavigation
        5 -> EdgeNavigation
        6 -> KindlishNavigation
        else -> DefaultNavigation
    }
    return when {
        verticalPager -> VerticalPagerNavigation
        invertH -> InvertedNavigation(base)
        else -> base
    }
}
