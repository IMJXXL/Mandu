package com.mangareader.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    subtitle: String = "",
    viewing: Boolean,
    bgColor: Color,
    isFavorite: Boolean,
    isHidden: Boolean,
    onBack: () -> Unit,
    onFavoriteClick: () -> Unit,
    onRenameClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onContinue: (() -> Unit)? = null,
    continueTargetName: String = ""
) {
    // 根据背景亮度决定文字颜色：亮底黑字，暗底白字
    val luminance = bgColor.red * 0.299f + bgColor.green * 0.587f + bgColor.blue * 0.114f
    val textColor = if (luminance > 0.5f) Color.Black else Color.White
    val textColorAlpha = textColor.copy(alpha = 0.6f)

    TopAppBar(
        title = {
            Column {
                // ★ 2026-10-05 工单：长书名横屏挤占宽度——最多两行，仍溢出走跑马灯滚动
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = textColor,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, fontSize = 11.sp, color = textColorAlpha, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = textColor)
            }
        },
        actions = {
            if (viewing) {
                IconButton(onClick = onRenameClick) {
                    Icon(Icons.Default.Edit, "重命名", tint = textColor)
                }
                if (isHidden) {
                    IconButton(onClick = onAddToShelfClick) {
                        Icon(Icons.Default.LibraryAdd, "加入书架", tint = textColor)
                    }
                }
                IconButton(onClick = onFavoriteClick) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null, tint = if (isFavorite) Color.Red else textColor
                    )
                }
            } else if (onContinue != null) {
                TextButton(onClick = onContinue, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.PlayArrow, "继续阅读", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("继续", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    if (continueTargetName.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            continueTargetName,
                            fontSize = 13.sp,
                            color = textColorAlpha,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp)
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
    )
}
