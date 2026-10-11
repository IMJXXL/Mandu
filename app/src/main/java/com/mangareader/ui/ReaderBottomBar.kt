package com.mangareader.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BottomBarAction(
    val icon: ImageVector,
    val label: String,
    val isActive: Boolean = false,
    /** 长按动作（2026-10-11：逐格设置挂长按——点击=开关，长按=设置面板）；onClick 保持末位兼容尾随 lambda 调用点 */
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit
)

/**
 * Reader bottom toolbar with action buttons only.
 * Progress slider is now handled by ChapterNavigator.
 */
@Composable
fun ReaderBottomBar(
    actions: List<BottomBarAction>,
    bgColor: Color = Color.Black,
    modifier: Modifier = Modifier
) {
    // 根据背景亮度决定文字颜色
    val luminance = bgColor.red * 0.299f + bgColor.green * 0.587f + bgColor.blue * 0.114f
    val textColor = if (luminance > 0.5f) Color.Black else Color.White
    val accentColor = readerAccentColor(bgColor)  // ★ #2：强调色跟随主题+可读性兜底
    val surfaceColor = bgColor.copy(alpha = 0.85f)

    Surface(color = surfaceColor, modifier = modifier) {
        val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
        val hPad = if (isTablet) 24.dp else 12.dp
        val vPad = if (isTablet) 10.dp else 6.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = hPad, vertical = vPad)) {
            // ★ 底栏换行修复（2026-10-11 用户报"逐格+超分对比出现后图标和设置换行成两行"）：
            //   旧竖屏恒 chunked(5)——6 个动作必两行。横竖屏统一按屏宽算每行容量
            //   （单按钮约 56dp，窄屏取下限 5 保证行数只多不少）
            val perRow = maxOf(5, (LocalConfiguration.current.screenWidthDp - 24) / 56)
            val rows = actions.chunked(perRow)
            rows.forEach { rowActions ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowActions.forEach { action ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .combinedClickable(
                                    onClick = action.onClick,
                                    onLongClick = { action.onLongClick?.invoke() },
                                )
                        ) {
                            Icon(
                                action.icon,
                                contentDescription = action.label,
                                tint = if (action.isActive) accentColor else textColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(40.dp).padding(9.dp)
                            )
                            Text(
                                action.label,
                                color = if (action.isActive) accentColor else textColor.copy(alpha = 0.6f),
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
