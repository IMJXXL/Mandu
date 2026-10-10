package com.mangareader.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.util.GroupSortConfig
import com.mangareader.util.GroupSortConfigCache
import com.mangareader.util.SortGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSortSettingsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    var config by remember { mutableStateOf(GroupSortConfig.load(ctx)) }
    var expandedIdx by remember { mutableStateOf(-1) }
    var showResetConfirm by remember { mutableStateOf(false) }

    fun save(newConfig: GroupSortConfig) {
        config = newConfig
        GroupSortConfig.save(ctx, newConfig)
        GroupSortConfigCache.refresh(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分组排序") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Default.Restore, "恢复默认")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SwitchRow(
                title = "启用",
                subtitle = "开启后按分组优先级排序，关闭时使用原有自然排序",
                checked = config.enabled,
                onCheckedChange = { save(config.copy(enabled = it)) }
            )
            SwitchRow(
                title = "文件夹在前",
                subtitle = "排序时文件夹整体排在文件之前",
                checked = config.folderFirst,
                onCheckedChange = { save(config.copy(folderFirst = it)) }
            )

            Spacer(Modifier.height(4.dp))
            Text("分组列表", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("拖动箭头调整分组优先级，点击展开编辑关键词，可新增或删除分组", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            config.groups.forEachIndexed { index, group ->
                GroupItemCard(
                    group = group,
                    index = index,
                    total = config.groups.size,
                    expanded = expandedIdx == index,
                    onToggleExpand = { expandedIdx = if (expandedIdx == index) -1 else index },
                    onMoveUp = {
                        if (index > 0) {
                            val newGroups = config.groups.toMutableList()
                            val tmp = newGroups[index - 1]
                            newGroups[index - 1] = newGroups[index]
                            newGroups[index] = tmp
                            save(config.copy(groups = newGroups))
                        }
                    },
                    onMoveDown = {
                        if (index < config.groups.size - 1) {
                            val newGroups = config.groups.toMutableList()
                            val tmp = newGroups[index + 1]
                            newGroups[index + 1] = newGroups[index]
                            newGroups[index] = tmp
                            save(config.copy(groups = newGroups))
                        }
                    },
                    onEdit = { newName, newKeywords ->
                        val newGroups = config.groups.toMutableList()
                        newGroups[index] = SortGroup(
                            name = newName.ifEmpty { group.name },
                            keywords = newKeywords
                        )
                        save(config.copy(groups = newGroups))
                    },
                    onDelete = {
                        if (config.groups.size > 1) {
                            val newGroups = config.groups.toMutableList()
                            newGroups.removeAt(index)
                            if (expandedIdx == index) expandedIdx = -1
                            else if (expandedIdx > index) expandedIdx--
                            save(config.copy(groups = newGroups))
                        }
                    },
                    canDelete = config.groups.size > 1,
                )
            }

            OutlinedButton(
                onClick = {
                    val newGroups = config.groups.toMutableList()
                    newGroups.add(SortGroup("新分组", emptyList()))
                    save(config.copy(groups = newGroups))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("新增分组")
            }

            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("规则说明", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("• 使用关键字识别(支持繁体)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• 以数字结尾会被当作章节对待", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• 修改后立即生效", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• 未匹配任何关键词的条目归入其他组，排在最后", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认") },
            text = { Text("将分组排序配置恢复为默认值。") },
            confirmButton = {
                TextButton(onClick = {
                    save(GroupSortConfig.DEFAULT)
                    showResetConfirm = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GroupItemCard(
    group: SortGroup,
    index: Int,
    total: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: (String, List<String>) -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
) {
    var nameText by remember(group.name) { mutableStateOf(group.name) }
    var keywordsText by remember(group.keywords) { mutableStateOf(group.keywords.joinToString(", ")) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.weight(1f).clickable { onToggleExpand() }
                ) {
                    Text("${index + 1}. ${group.name}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        group.keywords.joinToString(", ").ifEmpty { "无关键词" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onMoveUp, enabled = index > 0) {
                    Icon(Icons.Default.ArrowDropUp, "上移")
                }
                IconButton(onClick = onMoveDown, enabled = index < total - 1) {
                    Icon(Icons.Default.ArrowDropDown, "下移")
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "展开")
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("组名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = keywordsText,
                        onValueChange = { keywordsText = it },
                        label = { Text("关键词") },
                        supportingText = { Text("多个关键词用逗号分隔") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            onEdit(nameText.trim(), keywordsText.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() })
                        }) { Text("保存") }
                        TextButton(onClick = {
                            nameText = group.name
                            keywordsText = group.keywords.joinToString(", ")
                        }) { Text("重置") }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onDelete,
                            enabled = canDelete,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}