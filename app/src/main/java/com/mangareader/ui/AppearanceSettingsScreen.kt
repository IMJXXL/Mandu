package com.mangareader.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.smbbridge.SectionCard

/**
 * 外观设置子页（自设置页「外观」卡片进入）：
 * - 暗色模式：亮色/暗色/系统（改动后 recreate 整个 Activity 生效，历史行为保持一致）
 * - 主题颜色：动态色彩 + 预设主题横滑（全部为原版手工配色）+ 自定义色卡
 * - 自定义主色：HSV 拾色器（SV 方块 + 色相条）+ hex 手输，独立于预设按该颜色整体染色生成全套色板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    val activity = ctx as? Activity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP).padding(horizontal = 16.dp)
        ) {
            item {
                SectionCard("暗色模式") {
                    var darkMode by remember { mutableStateOf(prefs.getString("dark_mode", "SYSTEM") ?: "SYSTEM") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("亮色" to "LIGHT", "暗色" to "DARK", "系统" to "SYSTEM").forEach { (label, value) ->
                            FilterChip(
                                selected = darkMode == value,
                                onClick = {
                                    darkMode = value
                                    prefs.edit().putString("dark_mode", value).apply()
                                    activity?.recreate()
                                },
                                label = { Text(label, fontSize = 13.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    // ★ 快赢 #6：AMOLED 纯黑开关接线（主题链路已建成未接）——纯增量，不开=零变化
                    var amoled by remember { mutableStateOf(prefs.getBoolean("is_amoled_dark", false)) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("AMOLED 纯黑", fontSize = 14.sp)
                            Text("暗色下背景改为纯黑（OLED 省电）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = amoled,
                            onCheckedChange = {
                                amoled = it
                                prefs.edit().putBoolean("is_amoled_dark", it).apply()
                                activity?.recreate()
                            },
                        )
                    }
                }
            }

            item {
                SectionCard("主题颜色") {
                    val currentThemeOrdinal = prefs.getInt("app_theme", 0)
                    val dynamicColorEnabled = prefs.getBoolean("dynamic_color", false)
                    val useCustomColor = prefs.getBoolean("use_custom_color", false)
                    val storedCustom = prefs.getInt("custom_color", 0xFFE91E63.toInt())
                    val previewIsDark = when (prefs.getString("dark_mode", "SYSTEM") ?: "SYSTEM") {
                        "LIGHT" -> false
                        "DARK" -> true
                        else -> {
                            val nightMode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                            nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        }
                    }
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        item {
                            val isSelected = useCustomColor
                            Card(
                                modifier = Modifier.width(72.dp).clickable {
                                    prefs.edit().putBoolean("use_custom_color", true).putBoolean("dynamic_color", false).apply()
                                    activity?.recreate()
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                                            .background(Color(storedCustom))
                                    ) {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp).align(Alignment.Center), tint = Color.White)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("自定义", fontSize = 10.sp, maxLines = 1, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            val isSelected = dynamicColorEnabled
                            val dynAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                            Card(
                                modifier = Modifier.width(72.dp).clickable {
                                    if (dynAvailable) {
                                        prefs.edit().putBoolean("dynamic_color", true).putBoolean("use_custom_color", false).apply()
                                        activity?.recreate()
                                    } else {
                                        android.widget.Toast.makeText(ctx, "动态色彩需 Android 12+", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                                            .background(Brush.linearGradient(listOf(Color(0xFF6750A4), Color(0xFF6200EE), Color(0xFF03DAC6))))
                                    ) {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp).align(Alignment.Center), tint = Color.White)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("动态色彩", fontSize = 10.sp, maxLines = 1, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(AppTheme.entries.size) { index ->
                            val theme = AppTheme.entries[index]
                            val isSelected = !dynamicColorEnabled && !useCustomColor && currentThemeOrdinal == index
                            val themePrimary = getThemePrimaryColor(theme, previewIsDark)
                            Card(
                                modifier = Modifier.width(72.dp).clickable {
                                    prefs.edit().putInt("app_theme", index).putBoolean("dynamic_color", false).putBoolean("use_custom_color", false).apply()
                                    activity?.recreate()
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) themePrimary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                border = if (isSelected) BorderStroke(2.dp, themePrimary) else null
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(themePrimary))
                                    Spacer(Modifier.height(4.dp))
                                    Text(theme.label, fontSize = 10.sp, maxLines = 1, color = if (isSelected) themePrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("预设主题为原版手工配色；自定义主色按整体染色单独生成", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                CustomAccentSection(prefs, activity)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 自定义主色：开关 + HSV 拾色器 + hex + 应用（与标签改色同一套交互手感） */
@Composable
private fun CustomAccentSection(prefs: android.content.SharedPreferences, activity: Activity?) {
    SectionCard("自定义主色") {
        var enabled by remember { mutableStateOf(prefs.getBoolean("use_custom_color", false)) }
        // 默认取与默认蓝色主题拉开差距的粉色：开关键入即有可见变化，避免"开了像没开"误判不生效
        val initColor = prefs.getInt("custom_color", 0xFFE91E63.toInt())
        val initHsv = floatArrayOf(0f, 0f, 0f)
        android.graphics.Color.colorToHSV(initColor, initHsv)
        var hue by remember { mutableFloatStateOf(initHsv[0]) }
        var sat by remember { mutableFloatStateOf(initHsv[1]) }
        var value by remember { mutableFloatStateOf(initHsv[2]) }
        // hex 输入只存 # 后的 6 位；默认空，# 由 prefix 固定不可编辑
        var hexText by remember { mutableStateOf("") }
        val pickedColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("使用自定义主色", fontSize = 15.sp)
                Text("开启后优先于主题预设与动态色彩", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 当前调色实时预览
            Box(
                Modifier.size(28.dp).clip(CircleShape)
                    .background(Color(pickedColor))
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Switch(checked = enabled, onCheckedChange = { on ->
                enabled = on
                prefs.edit().putBoolean("use_custom_color", on).putBoolean("dynamic_color", false).apply()
                activity?.recreate()
            })
        }
        Spacer(Modifier.height(12.dp))

        // SV 方块：底色=当前色相，横向 白→色，纵向 透→黑
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
                .pointerInput(hue) {
                    fun update(pos: Offset) {
                        sat = (pos.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                    }
                    detectTapGestures { update(it) }
                }
                .pointerInput(hue) {
                    detectDragGestures { change, _ ->
                        sat = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    }
                }
        ) {
            val w = maxWidth
            val h = 150.dp
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            }
            Box(
                Modifier.offset(x = w * sat - 9.dp, y = h * (1 - value) - 9.dp)
                    .size(18.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape)
            )
        }
        Spacer(Modifier.height(10.dp))

        // 色相条
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    fun update(pos: Offset) { hue = (pos.x / size.width).coerceIn(0f, 1f) * 360f }
                    detectTapGestures { update(it) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                    }
                }
        ) {
            val w = maxWidth
            Canvas(Modifier.fillMaxSize()) {
                val steps = 72
                for (i in 0 until steps) {
                    drawRect(
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(i.toFloat() / steps * 360f, 1f, 1f))),
                        topLeft = Offset(size.width * i / steps, 0f),
                        size = Size(size.width / steps + 1f, size.height)
                    )
                }
            }
            Box(
                Modifier.offset(x = w * (hue / 360f) - 8.dp)
                    .size(16.dp, 38.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape)
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hexText,
                onValueChange = { input ->
                    // 只留 hex 字符、# 后最多 6 位；恰好 6 位时实时换算到拾色器
                    val filtered = input.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(6)
                    hexText = filtered
                    if (filtered.length == 6) {
                        filtered.toLongOrNull(16)?.let { rgb ->
                            val hsv = floatArrayOf(0f, 0f, 0f)
                            android.graphics.Color.colorToHSV(rgb.toInt(), hsv)
                            hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                        }
                    }
                },
                prefix = { Text("#", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                placeholder = { Text("如 FF5722", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.width(150.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp)
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                prefs.edit().putInt("custom_color", pickedColor).putBoolean("use_custom_color", true).putBoolean("dynamic_color", false).apply()
                enabled = true
                activity?.recreate()
            }) { Text("应用") }
        }
        Spacer(Modifier.height(6.dp))
        Text("拖动选色，或输满 6 位 hex 实时换算；点「应用」立即生效，整套界面按该颜色整体染色", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
