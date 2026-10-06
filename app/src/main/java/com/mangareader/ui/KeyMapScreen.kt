package com.mangareader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.keys.HardwareKeyRouter
import com.mangareader.keys.KeyAction
import com.mangareader.keys.KeyMapStore
import com.mangareader.keys.KeyMapping

/**
 * 按键自定义（2026-10-03）：蓝牙遥控器/翻页器/蓝牙键盘/电纸书侧键的「按键码→功能」映射管理。
 * 交互对齐参考 app：编辑对话框内「侦测」后按任意键自动抓键码；功能下拉；一键一映射（同键覆盖）。
 * 映射只在漫读前台生效，无需蓝牙权限（HID 按键由系统输入层直接派发）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyMapScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE) }

    var masterOn by remember { mutableStateOf(prefs.getBoolean("reader_hw_keys_enabled", false)) }
    var mappings by remember { mutableStateOf(KeyMapStore.mappings(ctx).sortedBy { it.keyCode }) }
    var editing by remember { mutableStateOf<KeyMapping?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showTip by remember { mutableStateOf(!prefs.getBoolean("hw_keys_tip_dismissed", false)) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("按键自定义") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    TextButton(onClick = { showResetConfirm = true }) { Text("恢复默认") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("添加按键") },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showTip) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "遥控器怎么用？",
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "蓝牙遥控器、翻页器、蓝牙键盘、电纸书侧键、触摸笔的侧键都是标准键盘设备：系统里配对后，" +
                                        "先打开下方「硬件按键翻页」总开关，再点右下角「添加按键」——" +
                                        "对话框默认就是侦测模式，按一下遥控器上的键（触摸笔按一下笔上的侧键），键码自动抓进来绑到功能。" +
                                        "默认表已覆盖常见按键，改绑/删除随意。\n" +
                                        "个别翻页器一个按键会发送多个键码（时灵时不灵的常见原因）：把它的几个键码" +
                                        "分别侦测出来、都绑同一个功能即可。触摸笔的笔尖点击不用这里配——" +
                                        "它走「点击翻页区域」，笔尖与手指同路。",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            IconButton(onClick = {
                                prefs.edit().putBoolean("hw_keys_tip_dismissed", true).apply()
                                showTip = false
                            }) { Icon(Icons.Default.Close, "收起提示") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("硬件按键翻页", fontSize = 16.sp)
                            Text(
                                "阅读时遥控器/键盘按键生效；关闭后按键交还系统（音量键仍可在阅读设置单独开）",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = masterOn,
                            onCheckedChange = {
                                masterOn = it
                                prefs.edit().putBoolean("reader_hw_keys_enabled", it).apply()
                            },
                        )
                    }
                }
            }
            item {
                Text(
                    "音量键翻页的开关在「阅读设置」里；若在下方映射了音量键，以映射为准。" +
                        "映射仅在漫读在前台时生效，不需要任何蓝牙权限。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    "按键映射（${mappings.size}）",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (mappings.isEmpty()) {
                item {
                    Text(
                        "还没有任何映射。点右下角「添加按键」新建，或点右上角「恢复默认」套用出厂表。",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(mappings, key = { it.keyCode }) { m ->
                Card(
                    Modifier.fillMaxWidth().clickable { editing = m },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.label.ifBlank { KeyMapStore.keyCodeName(m.keyCode) },
                                fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${m.actionEnum().label} · ${KeyMapStore.keyCodeName(m.keyCode)}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("编辑 ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认映射？") },
            text = { Text("将清空当前全部按键映射，套用出厂默认表（十字键/OK/翻页键/媒体键/空格/J K）。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    KeyMapStore.resetDefaults(ctx)
                    mappings = KeyMapStore.mappings(ctx)
                    toast("已恢复默认映射")
                }) { Text("恢复默认", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            },
        )
    }

    val dialogInitial = editing
    if (adding || dialogInitial != null) {
        KeyEditDialog(
            initial = dialogInitial,
            onDismiss = { adding = false; editing = null },
            onSave = { m ->
                // 一键一映射：同键码覆盖，列表按键码升序
                val next = (mappings.filter { it.keyCode != m.keyCode } + m).sortedBy { it.keyCode }
                KeyMapStore.save(ctx, next)
                mappings = next
                adding = false; editing = null
                toast("已保存：${KeyMapStore.keyCodeName(m.keyCode)} → ${m.actionEnum().label}")
            },
            onDelete = dialogInitial?.let { init ->
                {
                    KeyMapStore.save(ctx, mappings.filter { it.keyCode != init.keyCode })
                    mappings = KeyMapStore.mappings(ctx)
                    adding = false; editing = null
                    toast("已删除该映射")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyEditDialog(
    initial: KeyMapping?,
    onDismiss: () -> Unit,
    onSave: (KeyMapping) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var codeText by remember { mutableStateOf(initial?.keyCode?.toString() ?: "") }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var action by remember { mutableStateOf(initial?.actionEnum() ?: KeyAction.NEXT_PAGE) }
    // 打开对话框默认即进入侦测（用户拍板 2026-10-03）：编辑时直接按遥控器任意键即改绑
    var detecting by remember { mutableStateOf(true) }
    var ddExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 侦测模式：全局抓下一个按下的键（MainActivity.dispatchKeyEvent 会把按键先送到这里），
    // 期间吞掉一切按键（含音量键）防外漏；BACK 键用于退出侦测而不是填入。
    DisposableEffect(detecting) {
        if (detecting) {
            HardwareKeyRouter.handler = { code, e ->
                if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (code == android.view.KeyEvent.KEYCODE_BACK) {
                        detecting = false
                    } else {
                        codeText = code.toString()
                        if (label.isBlank()) label = KeyMapStore.keyCodeName(code)
                        detecting = false
                    }
                }
                true
            }
        }
        onDispose { HardwareKeyRouter.handler = null }
    }

    val code = codeText.toIntOrNull()
    val codeValid = code != null && code in 0..65535 && KeyMapStore.keyCodeName(code) != "Unknown key code"

    AlertDialog(
        onDismissRequest = { if (detecting) detecting = false else onDismiss() },
        title = { Text(if (initial == null) "添加按键" else "编辑按键") },
        text = {
            // 内容可滚动：小屏 + 输入法弹起时不再被裁切
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { s -> codeText = s.filter { it.isDigit() }.take(4) },
                    label = { Text("按键码") },
                    isError = codeText.isNotEmpty() && !codeValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { detecting = !detecting },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (detecting) "侦测中…请按遥控器/键盘任意键（返回键停止）"
                        else "点此侦测：按遥控器任意键自动填入按键码",
                        fontSize = 12.sp,
                    )
                }
                Text(
                    when {
                        codeText.isEmpty() -> "也可手动输入按键码数字（点「侦测」更省事）"
                        !codeValid -> "无法识别的按键码"
                        else -> "按键码：${KeyMapStore.keyCodeName(code)}"
                    },
                    fontSize = 11.sp,
                    color = if (!codeValid && codeText.isNotEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("说明（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(expanded = ddExpanded, onExpandedChange = { ddExpanded = it }) {
                    OutlinedTextField(
                        value = action.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("功能") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ddExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = ddExpanded, onDismissRequest = { ddExpanded = false }) {
                        KeyAction.entries.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label) },
                                onClick = { action = a; ddExpanded = false },
                            )
                        }
                    }
                }
                Text(
                    "同一按键只保留一条映射，保存时覆盖旧映射；长按条漫滚动键可连续滚动。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = { onSave(KeyMapping.of(code!!, action, label.trim())) },
                    enabled = codeValid,
                ) { Text("保存") }
            }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条映射？") },
            text = {
                Text(
                    "「${(initial?.label ?: "").ifBlank { KeyMapStore.keyCodeName(initial?.keyCode ?: 0) }}」" +
                        "（${KeyMapStore.keyCodeName(initial?.keyCode ?: 0)} → ${initial?.actionEnum()?.label}）将被移除。" +
                        "删除后该按键不再被漫读拦截（音量键等交还系统）。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete?.invoke()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
