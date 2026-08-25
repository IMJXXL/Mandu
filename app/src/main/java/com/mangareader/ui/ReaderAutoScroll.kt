package com.mangareader.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ReaderAutoScrollPanel(
    enabled: Boolean,
    intervalMs: Long,
    onIntervalChange: (Long) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = enabled,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF7C8CF8))
                Text("自动翻页", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("${intervalMs / 1000}s", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Slider(
                    value = intervalMs.toFloat(),
                    onValueChange = { onIntervalChange(it.toLong()) },
                    valueRange = 1000f..10000f,
                    steps = 8,
                    modifier = Modifier.weight(1.5f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF7C8CF8),
                        activeTrackColor = Color(0xFF7C8CF8)
                    )
                )
                IconButton(onClick = onToggle) {
                    Icon(Icons.Default.Close, "停止自动翻页", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun AutoScrollController(
    enabled: Boolean,
    intervalMs: Long,
    onPageNext: () -> Unit,
    isPaused: Boolean
) {
    LaunchedEffect(enabled, intervalMs, isPaused) {
        if (enabled && !isPaused && intervalMs > 0) {
            while (true) {
                delay(intervalMs)
                onPageNext()
            }
        }
    }
}
