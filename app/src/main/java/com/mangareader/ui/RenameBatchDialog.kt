package com.mangareader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangareader.data.Book

@Composable
fun RenameBatchDialog(
    books: List<Book>,
    onConfirm: (List<Pair<Long, String>>) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(0) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("{序号}") }
    var startNum by remember { mutableStateOf("1") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val canConfirm = when (mode) {
        0 -> findText.isNotEmpty()
        1 -> prefix.isNotEmpty() || suffix.isNotEmpty()
        2 -> template.contains("{序号}")
        else -> false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重命名（已选 ${books.size} 本）") },
        text = {
            Column(Modifier.heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState())) {
                TabRow(selectedTabIndex = mode, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    listOf("查找替换", "前缀后缀", "序号").forEachIndexed { i, label ->
                        Tab(selected = mode == i, onClick = { mode = i; errorMsg = null }, text = { Text(label) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (mode) {
                    0 -> {
                        OutlinedTextField(findText, { findText = it; errorMsg = null }, label = { Text("查找") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(replaceText, { replaceText = it }, label = { Text("替换为") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    1 -> {
                        OutlinedTextField(prefix, { prefix = it }, label = { Text("前缀") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(suffix, { suffix = it }, label = { Text("后缀") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    2 -> {
                        OutlinedTextField(template, { template = it }, label = { Text("模板") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(startNum, { startNum = it.filter { c -> c.isDigit() } },
                            label = { Text("起始序号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("输入 01 自动 2 位填充，也可用 {序号:3} 指定",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                errorMsg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    val updates = when (mode) {
                        0 -> books.mapNotNull { b ->
                            if (b.displayTitle.contains(findText)) b.id to b.displayTitle.replace(findText, replaceText) else null
                        }
                        1 -> books.map { b -> b.id to (prefix + b.displayTitle + suffix) }
                        2 -> {
                            val start = startNum.toIntOrNull() ?: 1
                            val leadingZeros = if (startNum.length > 1 && startNum.startsWith("0")) startNum.length else 0
                            val padMatch = Regex("\\{序号:(\\d+)\\}").find(template)
                            val padWidth = padMatch?.groupValues?.get(1)?.toIntOrNull() ?: leadingZeros
                            val tpl = if (padMatch != null) template.replace(padMatch.value, "{序号}") else template
                            books.mapIndexed { i, b ->
                                val num = start + i
                                val numStr = if (padWidth > 0) num.toString().padStart(padWidth, '0') else num.toString()
                                b.id to tpl.replace("{序号}", numStr)
                            }
                        }
                        else -> emptyList()
                    }
                    if (updates.isEmpty()) {
                        errorMsg = "没有匹配的书"
                    } else {
                        onConfirm(updates)
                    }
                }
            ) { Text("重命名") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}