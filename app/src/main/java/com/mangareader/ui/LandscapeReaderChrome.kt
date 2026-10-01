package com.mangareader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ★ 横屏阅读器侧边胶囊 chrome（1001 UI 审查 Phase 3）。
 *
 * 横屏高度稀缺、宽度富余：竖屏的"顶栏+底栏+进度条"纵向 chrome 会吃掉 200~260dp
 * （屏高的 2/3），改为左右两侧悬浮竖排胶囊 + 顶部标题胶囊 + 底部限宽进度条，
 * 画面恢复全高。仅由 Reader 在 [viewing && 横屏 && UI可见] 时挂载；
 * 竖屏永远走 Scaffold 顶底栏原路径，两条路径零交集。
 */
@Composable
fun LandscapeReaderChrome(
    title: String,
    subtitle: String,
    bgColor: Color,
    isFavorite: Boolean,
    isHidden: Boolean,
    showCompare: Boolean,
    compareActive: Boolean,
    pageBookmarked: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onAddToShelf: () -> Unit,
    onFavorite: () -> Unit,
    onToc: () -> Unit,
    onBookmark: () -> Unit,
    onSrPanel: () -> Unit,
    onCompare: () -> Unit,
    onSettings: () -> Unit,
    chapterNavigator: @Composable () -> Unit,
) {
    // 与顶/底栏同款：按背景亮度定字色
    val luminance = bgColor.red * 0.299f + bgColor.green * 0.587f + bgColor.blue * 0.114f
    val textColor = if (luminance > 0.5f) Color.Black else Color.White
    val pillColor = bgColor.copy(alpha = 0.85f)
    val railShape = RoundedCornerShape(20.dp)

    @Composable
    fun railButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, label, tint = if (active) Color(0xFF7C8CF8) else textColor.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 左侧胶囊：返回 / 重命名 / 加入书架（隐藏书）/ 收藏 —— 顶栏动作镜像
        Surface(color = pillColor, shape = railShape, modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp)) {
            Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                railButton(Icons.AutoMirrored.Filled.ArrowBack, "返回") { onBack() }
                railButton(Icons.Default.Edit, "重命名") { onRename() }
                if (isHidden) railButton(Icons.Default.LibraryAdd, "加入书架") { onAddToShelf() }
                railButton(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏", active = isFavorite) { onFavorite() }
            }
        }
        // 右侧胶囊：目录 / 书签 / 画质增强 / 对比 / 设置 —— 底栏动作镜像
        Surface(color = pillColor, shape = railShape, modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 10.dp)) {
            Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                railButton(Icons.Default.Menu, "目录") { onToc() }
                railButton(if (pageBookmarked) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd, if (pageBookmarked) "已书签" else "书签", active = pageBookmarked) { onBookmark() }
                railButton(Icons.Default.AutoFixHigh, "画质增强") { onSrPanel() }
                if (showCompare) railButton(Icons.Default.CompareArrows, if (compareActive) "退出对比" else "对比", active = compareActive) { onCompare() }
                railButton(Icons.Default.Settings, "设置") { onSettings() }
            }
        }
        // 顶部居中标题胶囊
        Surface(color = pillColor, shape = railShape, modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = textColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (subtitle.isNotEmpty()) Text(subtitle, color = textColor.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // 底部进度条：限宽 480dp 居中贴底（拖动精确度回升；竖屏仍全宽不受影响）
        Box(Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.widthIn(max = 480.dp)) {
                chapterNavigator()
            }
        }
    }
}
