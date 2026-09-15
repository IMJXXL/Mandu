package com.mangareader.backup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.backup.format.BackupContentSelection
import com.mangareader.backup.format.ConflictStrategy

@Composable
fun BackupContentSelector(
    selection: BackupContentSelection,
    onChange: (BackupContentSelection) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = selection.progress, onCheckedChange = { onChange(selection.copy(progress = it)) })
            Text("阅读进度", fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = selection.settings, onCheckedChange = { onChange(selection.copy(settings = it)) })
            Text("阅读设置", fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = selection.shelf, onCheckedChange = { onChange(selection.copy(shelf = it)) })
            Text("书架分类", fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = selection.connections, onCheckedChange = { onChange(selection.copy(connections = it)) })
            Text("连接配置", fontSize = 14.sp)
        }
        if (selection.connections) {
            Text("此分类包含加密凭据，请妥善保管备份文件", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
    }
}

@Composable
fun ConflictStrategyPicker(
    strategy: ConflictStrategy,
    onChange: (ConflictStrategy) -> Unit,
) {
    Column {
        ConflictStrategy.entries.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(s) }) {
                RadioButton(selected = strategy == s, onClick = { onChange(s) })
                Text(when (s) {
                    ConflictStrategy.OVERWRITE -> "覆盖（替换本地数据）"
                    ConflictStrategy.SKIP -> "跳过（保留本地数据）"
                    ConflictStrategy.MERGE -> "合并（替换冲突项）"
                }, fontSize = 14.sp)
            }
        }
    }
}