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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mangareader.ui.HistoryScreen
import androidx.navigation.navOptions
import com.mangareader.ui.Library
import com.mangareader.ui.Reader
import com.mangareader.ui.SettingsScreen
import com.mangareader.ui.Theme
import com.mangareader.ui.AppTheme

class MainActivity : ComponentActivity() {
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

            Theme(dark = dark, appTheme = currentTheme) {
                val nav = rememberNavController()
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
                        Reader(readerId, it.arguments?.getString("jump")) { nav.popBackStack() }
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
                    AlertDialog(
                        onDismissRequest = { },
                        modifier = Modifier.fillMaxWidth(0.95f),
                        title = { Text("大家好，我是瑾轩小狼") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text("    最开始做漫读，不过是想打造一款贴合自身习惯的本地漫画阅读工具。iOS 平台的可达阅读器堪称标杆，我一直十分推崇；安卓端其实也有不少成熟优质的阅读软件，只是都不太契合我的使用习惯，于是决定从零独立开发。")
                                Spacer(Modifier.height(8.dp))
                                Text("    每个人的使用偏好各不相同，漫读未必适合所有人，大家可以多体验几款软件，总能找到心仪的那一款。")
                                Spacer(Modifier.height(8.dp))
                                Text("    项目全程利用业余碎片时间开发，前后耗时三周左右。原本计划加入 SMB 文件读取以及图片无损放大功能，空余精力有限，SMB 最后只能忍痛舍弃；而图片无损放大（超分）已在后续版本补回——入口在漫画阅读器底部工具栏的【超分】。")
                                Text("    受自身技术水平局限，成品距离标杆软件还有不小差距，程序内仍存在不少待修复 bug，好在主体功能均可正常使用，便先行定稿发布。")
                                Text("    整个项目前后推倒重写三次，反复迭代优化；还有一处兼容问题始终没能解决，高版本安卓系统下，滑动动画流畅度反而不及低版本，暂时没能找到问题根源。")
                                Spacer(Modifier.height(8.dp))
                                Text("    本软件为纯个人业余开发，全程免费，没有任何商业收益。如果这款阅读器用着合你的心意，欢迎添加QQ：1173350134（点击可复制），请我喝杯咖啡，随心就好。", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.clickable {
                                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("QQ", "1173350134"))
                                    android.widget.Toast.makeText(this@MainActivity, "QQ号已复制", android.widget.Toast.LENGTH_SHORT).show()
                                })
                                Spacer(Modifier.height(8.dp))
                                Text("    特别感谢身边亲友的鼎力支持：", color = MaterialTheme.colorScheme.primary)
                                Text("    感谢晓萌抽空为本项目绘制专属 Logo，感谢染林同学耐心测试，及时反馈各类问题；更要谢谢我的妻子，包容我占用大量空余时间投入开发，给予我充足的理解与支持。")
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                                Text("仅安装后首次提示", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                personalNoteShown.value = true
                                prefs.edit().putBoolean("personal_note_shown", true).apply()
                            }) { Text("阅") }
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
