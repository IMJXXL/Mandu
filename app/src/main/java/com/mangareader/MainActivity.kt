package com.mangareader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mangareader.ui.HistoryScreen
import com.mangareader.ui.VolumeKeyHandler
import com.mangareader.ui.showSystemBars
import androidx.navigation.navOptions
import com.mangareader.ui.Library
import com.mangareader.ui.Reader
import com.mangareader.ui.SettingsScreen
import com.mangareader.ui.Theme
import com.mangareader.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (VolumeKeyHandler.enabled && !VolumeKeyHandler.menuVisible) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == android.view.KeyEvent.ACTION_UP) {
                        if (VolumeKeyHandler.inverted) VolumeKeyHandler.onPrev?.invoke()
                        else VolumeKeyHandler.onNext?.invoke()
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == android.view.KeyEvent.ACTION_UP) {
                        if (VolumeKeyHandler.inverted) VolumeKeyHandler.onNext?.invoke()
                        else VolumeKeyHandler.onPrev?.invoke()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignatureVerifier.verify(this, "F6D95465BE7D17C1B3BE2B0C59969C22")
        enableEdgeToEdge()
        requestAllFilesAccess()
        val prefs = getSharedPreferences("manga_reader", MODE_PRIVATE)
        setContent {
            val darkModeValue = prefs.getString("dark_mode", "SYSTEM") ?: "SYSTEM"
            val isSystemDark = isSystemInDarkTheme()
            val dark = when (darkModeValue) {
                "LIGHT" -> false
                "DARK" -> true
                else -> isSystemDark
            }
            val themeOrdinal = prefs.getInt("app_theme", 0)
            val currentTheme = AppTheme.entries.getOrNull(themeOrdinal) ?: AppTheme.BLUE
            val dynamicColor = prefs.getBoolean("dynamic_color", false)

            Theme(dark = dark, appTheme = currentTheme, dynamicColor = dynamicColor) {
                val nav = rememberNavController()

                // ★ 免疫沉浸式：只要当前屏幕不是阅读器，状态栏强制可见。
                //   key=路由，路由变化时重跑；书架/历史/设置/超分队列及以后所有非阅读页一处覆盖。
                //   维护点：若重命名 reader 路由，同步下方 startsWith("reader/")。
                val backStackEntry by nav.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                DisposableEffect(currentRoute) {
                    if (currentRoute != null && !currentRoute.startsWith("reader/")) {
                        showSystemBars(this@MainActivity, false)
                    }
                    onDispose { }
                }
                NavHost(nav, "library") {
                    composable("library") {
                        Library(
                            { id, jump ->
                                // ★ ⑨ 修复：双击 FAB 会 push 两层 Reader backStackEntry，按两次返回才回书架
                                //   加 launchSingleTop —— 对完全相同 route+args 的二次 navigate noop（属性赋值非函数调用）
                                nav.navigate(
                                    if (jump != null) "reader/$id?jump=${android.net.Uri.encode(jump)}" else "reader/$id",
                                    navOptions { launchSingleTop = true }
                                )
                            },
                            {
                                val newMode = if (dark) "LIGHT" else "DARK"
                                prefs.edit().putString("dark_mode", newMode).apply()
                                (this@MainActivity).recreate()
                            },
                            { nav.navigate("settings") },
                            { nav.navigate("history") }
                        )
                    }
                    composable("history") {
                        HistoryScreen(
                            onBack = { nav.popBackStack() },
                            onOpen = { id, jump ->
                                nav.navigate(
                                    if (jump != null) "reader/$id?jump=${android.net.Uri.encode(jump)}" else "reader/$id",
                                    navOptions { launchSingleTop = true }
                                )
                            }
                        )
                    }
                    composable("reader/{id}?jump={jump}", listOf(
                        navArgument("id") { type = NavType.LongType },
                        navArgument("jump") { type = NavType.StringType; nullable = true; defaultValue = null }
                    )) {
                        val readerId = it.arguments?.getLong("id") ?: return@composable
                        Reader(
                            bookId = readerId,
                            jumpPath = it.arguments?.getString("jump"),
                            back = { nav.popBackStack() },
                            onOpenBook = { bookId, path ->
                                nav.navigate("reader/$bookId?jump=${android.net.Uri.encode(path)}") {
                                    popUpTo("library")
                                }
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenBook = { bookId, path ->
                                nav.navigate(
                                    "reader/$bookId?jump=${android.net.Uri.encode(path)}",
                                    navOptions { launchSingleTop = true },
                                )
                            },
                        )
                    }
                }

                // 仅安装后首次打开弹出
                val personalNoteShown = remember { mutableStateOf(prefs.getBoolean("personal_note_shown", false)) }
                if (!personalNoteShown.value) {
                    var countdown by remember { mutableStateOf(3) }
                    LaunchedEffect(Unit) {
                        while (countdown > 0) {
                            kotlinx.coroutines.delay(1000)
                            countdown--
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { },
                        modifier = Modifier.fillMaxWidth(0.95f),
                        title = { Text("大家好，我是瑾轩小狼") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text("    漫读是一款安卓本地漫画阅读器，全程免费、无广告、无任何商业收益。")
                                Spacer(Modifier.height(8.dp))
                                Text("    开发不易，如果用着合心意，欢迎点击左上角的「瑾」字请我喝杯咖啡；遇到问题或建议，也可以直接加 QQ 反馈。")
                                Spacer(Modifier.height(8.dp))
                                Text("QQ：1173350134（点击可复制）", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable {
                                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("QQ", "1173350134"))
                                    android.widget.Toast.makeText(this@MainActivity, "QQ号已复制", android.widget.Toast.LENGTH_SHORT).show()
                                })
                                Spacer(Modifier.height(8.dp))
                                Text("    特别感谢晓萌绘制 Logo，染林同学耐心测试。")
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                                Text("仅安装后首次提示", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    personalNoteShown.value = true
                                    prefs.edit().putBoolean("personal_note_shown", true).apply()
                                },
                                enabled = countdown == 0
                            ) { Text(if (countdown > 0) "阅 ($countdown)" else "阅") }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
        } catch (_: Exception) {}
    }

    // ★ 附录 B.3 修复：trimMemory 过去全项目无调用方（阅读视图缓存永远没人收）。
    //   广播给 NES 注册的阅读视图（弱引用注册表，不收想释放的视图）
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.mangareader.native.ReaderTrimRegistry.trimAll(level)
    }

    private fun requestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
