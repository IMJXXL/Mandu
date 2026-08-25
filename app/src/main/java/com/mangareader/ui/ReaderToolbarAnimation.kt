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
    content: @Composable () -> Unit
) {
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
