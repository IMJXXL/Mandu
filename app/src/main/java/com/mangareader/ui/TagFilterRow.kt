package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.TagWithCount

/** 标签色点（design.md §六：8~9dp 圆点只做身份识别；对比度不足自动描边） */
@Composable
fun TagDot(tagName: String, storedColor: Long, size: Dp = 9.dp) {
    val dark = isSystemInDarkTheme()
    val color = TagColors.resolve(tagName, storedColor, dark)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(color))
            .then(if (TagColors.needsOutline(storedColor, dark)) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
    )
}

/**
 * 首页行2 标签筛选行（design.md §三）：
 * - 渐进披露：无已选且无最近标签 → 整行不渲染
 * - 已选态：入口chip(计数) + 已选chips(色点+名+×单删) + 清除
 * - 空选态：入口chip + 最近5个快捷chips（1 tap 直选）
 */
@Composable
fun TagFilterRow(
    allTags: List<TagWithCount>,
    selectedIds: Set<Long>,
    recentIds: List<Long>,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    val byId = remember(allTags) { allTags.associateBy { it.tag.id } }
    val selected = selectedIds.mapNotNull { byId[it] }
    val recent = recentIds.mapNotNull { byId[it] }.filter { it.tag.id !in selectedIds }.take(5)
    if (selected.isEmpty() && recent.isEmpty()) return

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = false,
                onClick = onOpenPicker,
                label = { Text(if (selected.isEmpty()) "标签" else "标签 · ${selected.size}", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Sell, null, Modifier.size(15.dp)) }
            )
        }
        if (selected.isNotEmpty()) {
            items(selected.size) { i ->
                val twc = selected[i]
                FilterChip(
                    selected = true,
                    onClick = { onRemove(twc.tag.id) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TagDot(twc.tag.name, twc.tag.color)
                            Spacer(Modifier.width(6.dp))
                            Text(twc.tag.name, fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            Text("×", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            }
            item {
                FilterChip(selected = false, onClick = onClear, label = { Text("清除", fontSize = 13.sp) })
            }
        } else {
            items(recent.size) { i ->
                val twc = recent[i]
                FilterChip(
                    selected = false,
                    onClick = { onToggle(twc.tag.id) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TagDot(twc.tag.name, twc.tag.color)
                            Spacer(Modifier.width(6.dp))
                            Text(twc.tag.name, fontSize = 13.sp)
                        }
                    }
                )
            }
        }
    }
}
