package com.mangareader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * ★ 横屏阅读器侧边胶囊 chrome（1001 UI 审查 Phase 3）。
 *
 * 横屏高度稀缺、宽度富余：竖屏的"顶栏+底栏+进度条"纵向 chrome 会吃掉 200~260dp
 * （屏高的 2/3），改为左右两侧悬浮竖排胶囊 + 底部限宽进度条，画面恢复全高。
 * （原顶部标题胶囊经真机验证观感不佳，已移除——1001 反馈）仅由 Reader 在 [viewing && 横屏 && UI可见] 时挂载；
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
    /** ★ 逐格阅读（2026-10-11）：可用性由 Reader 判定（与底栏同源），激活时换关闭样式 */
    showPanelGuide: Boolean = false,
    panelGuideActive: Boolean = false,
    onPanelGuide: () -> Unit = {},
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
    val accentColor = readerAccentColor(bgColor)  // ★ #2：强调色跟随主题+可读性兜底
    val pillColor = bgColor.copy(alpha = 0.85f)
    val railShape = RoundedCornerShape(20.dp)

    @Composable
    fun railButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, label, tint = if (active) accentColor else textColor.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 左侧胶囊：信息头（书名+页码，真机反馈"不能看不到信息"——嵌入胶囊头部而非独立浮层）
        //          + 返回 / 重命名 / 加入书架（隐藏书）/ 收藏 —— 顶栏动作镜像
        // ★ 2026-10-06 工单"不能一直让 UI 太宽"：书名默认折叠——平时只显示页码（窄），
        //   点页码展开书名（180dp 限宽），再点收起。信息可看 + 胶囊恒窄两头兼顾
        var titleExpanded by remember { mutableStateOf(false) }
        Surface(color = pillColor, shape = railShape, modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp)) {
            Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (title.isNotEmpty() || subtitle.isNotEmpty()) {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp).widthIn(max = 180.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (titleExpanded && title.isNotEmpty()) Text(
                            title, color = textColor, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium
                        )
                        if (subtitle.isNotEmpty()) Text(
                            subtitle, color = textColor.copy(alpha = 0.6f), fontSize = 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { titleExpanded = !titleExpanded },
                        )
                    }
                }
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
                if (showPanelGuide) railButton(
                    Icons.Default.GridView,
                    "逐格阅读",
                    active = panelGuideActive,
                ) { onPanelGuide() }
                if (showCompare) railButton(Icons.Default.CompareArrows, if (compareActive) "退出对比" else "对比", active = compareActive) { onCompare() }
                railButton(Icons.Default.Settings, "设置") { onSettings() }
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
