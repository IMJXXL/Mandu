package com.mangareader.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

private const val SLIDE_DURATION_MS = 200
private const val FADE_DURATION_MS = 150

@Composable
fun AnimatedSlideVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enterFromBottom: Boolean = true,
    /** 墨水屏等场景传 false：不动画直接显隐（墨水屏模式装饰动画一律关规约） */
    animate: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!animate) {
        if (visible) androidx.compose.foundation.layout.Box(modifier) { content() }
        return
    }
    val enter = slideInVertically(
        initialOffsetY = { if (enterFromBottom) it else -it },
        animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(FADE_DURATION_MS))

    val exit = slideOutVertically(
        targetOffsetY = { if (enterFromBottom) it else -it },
        animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(FADE_DURATION_MS))

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = modifier
    ) {
        content()
    }
}

@Composable
fun AnimatedSlideVisibilityHorizontal(
    visible: Boolean,
    fromLeft: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val enter = slideInHorizontally(
        initialOffsetX = { if (fromLeft) -it else it },
        animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(FADE_DURATION_MS))

    val exit = slideOutHorizontally(
        targetOffsetX = { if (fromLeft) -it else it },
        animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(FADE_DURATION_MS))

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = modifier
    ) {
        content()
    }
}

/**
 * ★ Scaffold 槽位（topBar/bottomBar）专用的条栏显隐（F8 修复，2026-10-11）：
 *   AnimatedVisibility 放在槽位里，退场动画全程照常占位、结束瞬间释放——阅读区高度
 *   在动画尾帧突跳一整条栏高，入场反向同理。本实现把"滑出"和"占位高度塌缩"绑在
 *   同一条曲线上逐帧同步：内容先按无界高度测量出真实栏高，容器高度 = 栏高 × 动画进度，
 *   内容贴容器顶放置（y=0）+ clipToBounds 裁掉超出部分。锚定方向自动正确：
 *   顶栏容器钉在屏顶，塌缩时底边上移 = 向上滑出；底栏容器钉在屏底（Scaffold 把槽位
 *   底边贴屏幕底），塌缩时顶边下移 = 向下滑出。
 *   ★ 测量必须无界（maxHeight=Infinity）：曾用「外层 height(实测×进度) + 内层
 *   onSizeChanged 回写」——外层 0dp 强制内容按 0 高测量，实测值恒 0 死锁，条栏永久
 *   不可见（1.395(62) 实锤：点按只切状态不出 UI）。改为 Layout 内一次无界测量。
 *   eink（animate=false）保持瞬时切换；隐藏时容器 0 高，不参与触摸命中。
 */
@Composable
fun AnimatedBarVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!animate) {
        if (visible) Box(modifier) { content() }
        return
    }
    val collapseFraction by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        label = "barCollapse"
    )
    Layout(
        modifier = modifier.fillMaxWidth().clipToBounds(),
        content = { Box(Modifier.fillMaxWidth()) { content() } },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        )
        val collapsed = (placeable.height * collapseFraction).roundToInt()
        layout(constraints.maxWidth, collapsed) {
            placeable.placeRelative(0, 0)
        }
    }
}
