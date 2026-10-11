package com.mangareader.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
