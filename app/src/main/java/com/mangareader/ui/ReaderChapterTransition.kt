package com.mangareader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 章节过渡页面
 * 在跨章节翻页时显示，提示用户已到章节边界
 */
@Composable
fun ChapterTransitionOverlay(
    visible: Boolean,
    isLast: Boolean,
    chapterTitle: String = "",
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable {
                    if (isLast) onPreviousChapter() else onNextChapter()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isLast) "已是最后一页" else "已是第一页",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (chapterTitle.isNotEmpty()) {
                    Text(
                        text = chapterTitle,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
                Text(
                    text = if (isLast) "点击返回上一章" else "点击进入下一章",
                    color = Color(0xFF7C8CF8),
                    fontSize = 14.sp
                )
                CircularProgressIndicator(
                    color = Color(0xFF7C8CF8),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
