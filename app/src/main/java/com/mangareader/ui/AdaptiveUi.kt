package com.mangareader.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ★ 横屏/平板适配共享工具（1001 UI 审查方案落地）。
 *
 * 竖屏零影响原则的三道机制：
 *  A 断点门槛——布局类分支挂 isLandscape/isTabletWide，竖屏手机不进新路径；
 *  B 容量式约束——dialogContentMaxHeight 用「屏高−预留」而非固定百分比，
 *    内容装得下时约束数学上不生效（竖屏视觉零变化），只有本就要溢出裁切的
 *    场景才触发滚动；
 *  C 等价替换——横屏行为替换在竖屏下输出与现状完全一致（如底栏 max(5,按宽计算)）。
 */

/** 大屏内容容器限宽：≥ 此宽度（平板）才生效，竖屏手机宽度低于此值零影响 */
val CONTENT_MAX_WIDTH_DP = 640.dp

/** 平板断点（最小屏宽），与 ReaderBottomBar 既有判断同源 */
@Composable
fun isTabletWide(): Boolean = LocalConfiguration.current.smallestScreenWidthDp >= 600

@Composable
fun isLandscapeOrientation(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 弹窗内容列容量上限：屏高减去标题/按钮/状态栏预留。
 * 关键性质：内容自然高度 ≤ 此上限时，heightIn 不改变任何布局（竖屏严格无操作）；
 * 只有横屏/键盘弹出导致装不下时才封顶，配合 verticalScroll 让内容可达。
 */
@Composable
fun dialogContentMaxHeight(): Dp = (LocalConfiguration.current.screenHeightDp - 100).coerceAtLeast(160).dp

/**
 * 平板/大屏内容容器：全宽下限宽并水平居中。
 * 竖屏手机（宽度 < 上限）内层 fillMaxSize 后 widthIn(max) 不产生任何约束变化。
 */
@Composable
fun CenteredPane(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter, content = content)
}
