package eu.wewox.pagecurl.page

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.PageCurlConfig.DragInteraction.PointerBehavior
import eu.wewox.pagecurl.utils.multiply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@ExperimentalPageCurlApi
internal fun Modifier.dragStartEnd(
    dragInteraction: PageCurlConfig.StartEndDragInteraction,
    state: PageCurlState.InternalState,
    enabledForward: Boolean,
    enabledBackward: Boolean,
    scope: CoroutineScope,
    onChange: (Int) -> Unit,
): Modifier = this.composed {
    val isEnabledForward = rememberUpdatedState(enabledForward)
    val isEnabledBackward = rememberUpdatedState(enabledBackward)

    pointerInput(state) {
        val forwardEndRect by lazy { dragInteraction.forward.end.multiply(size) }
        val backwardEndRect by lazy { dragInteraction.backward.end.multiply(size) }

        val maxWidthPx = state.constraints.maxWidth.toFloat()
        val forwardConfig = DragConfig(
            edge = state.forward,
            start = state.rightEdge,
            // Kamigura fork: settle at the configured turn end (the spine for leaf turns).
            end = state.forwardEndEdge,
            isEnabled = { isEnabledForward.value },
            isDragSucceed = { _, end -> forwardEndRect.contains(end) },
            onChange = { onChange(+1) },
            creaseRangeX = if (state.leafTurn) state.forwardEndEdge.top.x..maxWidthPx else null
        )
        val backwardConfig = DragConfig(
            edge = state.backward,
            start = state.leftEdge,
            end = state.backwardEndEdge,
            isEnabled = { isEnabledBackward.value },
            isDragSucceed = { _, end -> backwardEndRect.contains(end) },
            onChange = { onChange(-1) },
            mirrorInputX = state.leafTurn,
            creaseRangeX = if (state.leafTurn) 0f..state.backwardEndEdge.top.x else null
        )

        detectCurlGestures(
            scope = scope,
            newEdgeCreator = when (dragInteraction.pointerBehavior) {
                PointerBehavior.Default -> NewEdgeCreator.Default()
                PointerBehavior.PageEdge -> NewEdgeCreator.PageEdge()
            },
            getConfig = { start, end ->
                // ★ 方向优先（2026-10-05，修"半区死区"）：拖动方向决定前进/后退——向左=前进、
                //   向右=后退。旧行为按起手半区选向（右半起手才能前进、左半才能后退），
                //   起手半区与方向不符时整个手势判 null 无效——用户从中间/对侧起手的滑动
                //   近半数"没反应"（zip/图片文件夹单页不能滑动翻页的仿真侧根因）。
                //   方向与半区一致时结果与旧行为完全相同，仅补齐不一致的方向。
                val config = if (end.x < start.x) {
                    forwardConfig
                } else {
                    backwardConfig
                }

                if (config != null) {
                    scope.launch {
                        state.animateJob?.cancel()
                        state.reset()
                    }
                }

                config
            },
        )
    }
}
