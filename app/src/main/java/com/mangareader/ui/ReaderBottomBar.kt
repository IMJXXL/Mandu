package com.mangareader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BottomBarAction(
    val icon: ImageVector,
    val label: String,
    val isActive: Boolean = false,
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
    val surfaceColor = bgColor.copy(alpha = 0.85f)

    Surface(color = surfaceColor, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val rows = actions.chunked(5)
            rows.forEach { rowActions ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowActions.forEach { action ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(onClick = action.onClick, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    action.icon,
                                    contentDescription = action.label,
                                    tint = if (action.isActive) Color(0xFF7C8CF8) else textColor.copy(alpha = 0.8f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                action.label,
                                color = if (action.isActive) Color(0xFF7C8CF8) else textColor.copy(alpha = 0.6f),
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }
                    repeat(5 - rowActions.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
