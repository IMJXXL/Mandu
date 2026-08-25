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
        NavigationRegion(RectF(0f, 0.05f, 0.33f, 1f), NavigationAction.PREV),
        NavigationRegion(RectF(0.33f, 0.05f, 0.67f, 1f), NavigationAction.MENU),
        NavigationRegion(RectF(0.67f, 0.05f, 1f, 1f), NavigationAction.NEXT)
    )
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
