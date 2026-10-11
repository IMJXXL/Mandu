package com.mangareader.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset

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
 *   同一条曲线上逐帧同步：容器高度 = 内容实测高度 × 动画进度，内容用 graphicsLayer
 *   贴槽位锚定边平移（draw 阶段生效，不触发重组），被裁掉的部分就是滑出去的部分，
 *   阅读区随之平滑让位/回收。顶栏（锚定屏幕顶）与底栏（锚定屏幕底）用同一公式：
 *   translationY = 容器高 − 内容高，可见部分恰好是还没滑出的那截。
 *   eink（animate=false）保持瞬时切换。内容常驻组合以保测量值新鲜；隐藏时容器
 *   0 高 + clipToBounds，不参与触摸命中。
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
    var fullHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val collapseFraction by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        label = "barCollapse"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(with(density) { (fullHeightPx * collapseFraction).toDp() })
            .clipToBounds()
    ) {
        Box(
            Modifier
                .onSizeChanged { fullHeightPx = it.height }
                .graphicsLayer { translationY = size.height * (collapseFraction - 1f) }
        ) {
            content()
        }
    }
}
