package com.mangareader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    onBack: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    // 根据背景亮度决定文字颜色：亮底黑字，暗底白字
    val luminance = bgColor.red * 0.299f + bgColor.green * 0.587f + bgColor.blue * 0.114f
    val textColor = if (luminance > 0.5f) Color.Black else Color.White
    val textColorAlpha = textColor.copy(alpha = 0.6f)

    TopAppBar(
        title = {
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = textColor)
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
                IconButton(onClick = onFavoriteClick) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null, tint = if (isFavorite) Color.Red else textColor
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
    )
}
