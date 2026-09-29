# -*- coding: utf-8 -*-
"""UI 文案精修批量补丁（P0 批次）：每处替换断言恰好 1 次，失配即报错"""
import sys

ROOT = "app/src/main/java/com/mangareader/"
EDITS = [
    # ===== R1 省略号 … + 搜索占位词 =====
    ("komgabridge/ui/KomgaAddServerDialog.kt", '"测试中..."', '"测试中…"', 1),
    ("opdsbridge/ui/OpdsAddServerDialog.kt", '"测试中..."', '"测试中…"', 1),
    ("opdsbridge/ui/OpdsBrowserScreen.kt", '"正在下载..."', '"正在下载…"', 1),
    ("ui/FolderManageScreen.kt", '"扫描中..."', '"扫描中…"', 1),
    ("ui/FolderManageScreen.kt", '"一键扫描所有文件夹..."', '"一键扫描所有文件夹…"', 1),
    ("ui/FolderManageScreen.kt", '正在扫描「${f.displayName}」...', '正在扫描「${f.displayName}」…', 1),
    ("ui/Library.kt", '"正在读取 ComicInfo.xml..."', '"正在读取 ComicInfo.xml…"', 1),
    ("ui/Library.kt", 'Text("搜索...")', 'Text("搜索书名、作者…")', 1),
    ("ui/TagManageList.kt", 'Text("搜索标签...")', 'Text("搜索标签…")', 1),
    # ===== R2 加号 / R5 箭头 =====
    ("ui/ReaderSettingsSheet.kt", '"＋导入"', '"+ 导入"', 1),
    ("ui/Library.kt", '"书架 更多>"', '"书架 更多 ›"', 1),
    # ===== R3 确认按钮动词化 =====
    ("opdsbridge/ui/OpdsBrowserScreen.kt", '{ Text("确定") }', '{ Text("知道了") }', 1),
    ("smbbridge/SmbDownloadQueueScreen.kt", '{ Text("确定") }', '{ Text("知道了") }', 1),
    ("smbbridge/SmbSettingsScreen.kt", '.setPositiveButton("确定")', '.setPositiveButton("保存")', 1),
    ("ui/FolderManageScreen.kt", '{ Text("确定", color = MaterialTheme.colorScheme.error) }', '{ Text("删除", color = MaterialTheme.colorScheme.error) }', 1),
    ("ui/FolderManageScreen.kt", 'Toast.makeText(ctx, "已重命名", Toast.LENGTH_SHORT).show()\n                }) { Text("确定") }',
     'Toast.makeText(ctx, "已重命名", Toast.LENGTH_SHORT).show()\n                }) { Text("保存") }', 1),
    ("ui/MergeTargetPickerDialog.kt", '{ Text("确定") }', '{ Text("合并") }', 1),
    ("ui/Reader.kt", 'passwordDeferred?.complete(pwd)\n                }) { Text("确定") }',
     'passwordDeferred?.complete(pwd)\n                }) { Text("解锁") }', 1),
    ("ui/ReadingSettingsScreen.kt", '{ Text("确定") }', '{ Text("重置", color = MaterialTheme.colorScheme.error) }', 1),
    ("ui/RenameBatchDialog.kt", 'Text("确定")', 'Text("重命名")', 1),
    ("ui/SettingsScreen.kt", 'TextButton(onClick = { showAbout = false }) { Text("确定") }', 'TextButton(onClick = { showAbout = false }) { Text("知道了") }', 1),
    ("ui/ShelfManageScreen.kt", '{ Text("确定", color = MaterialTheme.colorScheme.error) }', '{ Text("删除", color = MaterialTheme.colorScheme.error) }', 1),
    ("ui/ShelfPickerDialog.kt", '{ Text("确定") }', '{ Text("添加") }', 1),
    ("ui/TagManageList.kt", '{ Text("确定") }', '{ Text("重命名") }', 1),
    # ===== 危险弹窗三行式 =====
    ("ui/ShelfManageScreen.kt", 'title = { Text("删除书架") },\n            text = { Text("删除书架「${s.name}」？书架内的漫画不受影响。") },',
     'title = { Text("删除书架？") },\n            text = { Text("书架内的漫画不受影响，仅移除该分组。\\n此操作不可撤销。") },', 1),
    ("ui/FolderManageScreen.kt", 'title = { Text("删除文件夹") },', 'title = { Text("删除文件夹？") },', 1),
    ("ui/FolderManageScreen.kt", 'Text("删除「${f.displayName}」及该文件夹下所有漫画记录？原始文件不受影响。已设置的自定义封面将一并删除。")',
     'Text("将删除「${f.displayName}」及该文件夹下所有漫画记录；原始文件不受影响，已设置的自定义封面将一并删除。\\n此操作不可撤销。")', 1),
    ("ui/ReadingSettingsScreen.kt", 'Text("将清除所有阅读设置（含单本自定义），恢复到刚安装时的状态。阅读进度不受影响。")',
     'Text("将清除所有阅读设置（含单本自定义），恢复到刚安装时的状态。阅读进度不受影响。\\n此操作不可撤销。")', 1),
    # ===== R6 暂无 → 还没有 =====
    ("komgabridge/ui/KomgaBrowserScreen.kt", '"暂无书库"', '"还没有书库"', 1),
    ("komgabridge/ui/KomgaBrowserScreen.kt", '"暂无系列"', '"还没有系列"', 1),
    ("komgabridge/ui/KomgaServersScreen.kt", '"暂无 Komga 服务器，点击右上角 + 添加"', '"还没有 Komga 服务器，点击右上角 + 添加"', 1),
    ("opdsbridge/ui/OpdsBrowserScreen.kt", '"暂无内容"', '"还没有内容"', 1),
    ("opdsbridge/ui/OpdsServersScreen.kt", '"暂无 OPDS 服务器，点击右上角 + 添加"', '"还没有 OPDS 服务器，点击右上角 + 添加"', 1),
    ("smbbridge/SmbServersScreen.kt", '"暂无服务器，点击右上角 + 添加"', '"还没有服务器，点击右上角 + 添加"', 1),
    ("webdavbridge/WebDavServersScreen.kt", '"暂无服务器，点击右上角 + 添加"', '"还没有服务器，点击右上角 + 添加"', 1),
    ("ui/Library.kt", '"暂无标签（导入带 ComicInfo.xml 的书可自动获得，或在下方手动添加）"', '"还没有标签（导入带 ComicInfo.xml 的书可自动获得，或在下方手动添加）"', 1),
    ("ui/ReaderTocSheet.kt", '"暂无书签（阅读中点底部「书签」按钮添加当前页）"', '"还没有书签（阅读中点底部「书签」按钮添加当前页）"', 1),
    ("ui/ReaderTocSheet.kt", '"暂无可浏览页面"', '"还没有可浏览的页面"', 1),
    # ===== R4 中英空格（人工筛后 18 处） =====
    ("MainActivity.kt", '"QQ号已复制"', '"QQ 号已复制"', 1),
    ("SignatureVerifier.kt", '"签名hash不匹配"', '"签名 hash 不匹配"', 1),
    ("SignatureVerifier.kt", '"后台定时校验失败(签名hash不匹配或签名列表为空)"', '"后台定时校验失败（签名 hash 不匹配或签名列表为空）"', 1),
    ("smbbridge/SmbSettingsScreen.kt", "（1-20，默认10）", "（1-20，默认 10）", 1),
    ("ui/ReaderSettingsSheet.kt", "检测阈值（默认220）", "检测阈值（默认 220）", 1),
    ("ui/ReaderSettingsSheet.kt", "图片亮度（默认0）", "图片亮度（默认 0）", 1),
    ("ui/ReaderSettingsSheet.kt", "屏幕亮度（默认0）", "屏幕亮度（默认 0）", 1),
    ("ui/ReaderSettingsSheet.kt", "对比度（默认100）", "对比度（默认 100）", 1),
    ("ui/ReaderSettingsSheet.kt", "伽马（默认100）", "伽马（默认 100）", 1),
    ("ui/ReaderSettingsSheet.kt", "低于安卓13伽马调节会有细节丢失", "安卓 13 以下为线性近似，细节略有丢失", 1),
    ("ui/ReadingSettingsScreen.kt", '"PDF加载质量"', '"PDF 加载质量"', 1),
    ("ui/ReadingSettingsScreen.kt", "手机屏幕一般300-500DPI，3x-4x已足够清晰", "手机屏幕一般 300-500 DPI，3x-4x 已足够清晰", 1),
    ("ui/ReadingSettingsScreen.kt", "缩短UI自动隐藏时间以减少残影", "缩短 UI 自动隐藏时间以减少残影", 1),
    ("ui/ReadingSettingsScreen.kt", '"UI自动隐藏时间"', '"UI 自动隐藏时间"', 1),
    ("ui/ReadingSettingsScreen.kt", "（普通模式默认10秒）", "（普通模式默认 10 秒）", 1),
    ("ui/RenameBatchDialog.kt", "输入01自动2位填充，也可用{序号:3}指定", "输入 01 自动 2 位填充，也可用 {序号:3} 指定", 1),
    ("ui/SettingsScreen.kt", "背景颜色、墨水屏、PDF质量、自动翻页等", "背景颜色、墨水屏、PDF 质量、自动翻页等", 1),
    ("ui/SettingsScreen.kt", '"32位系统，部分功能性能可能有所下降"', '"32 位系统，部分功能性能可能有所下降"', 1),
    ("ui/SettingsScreen.kt", '"64位系统，可使用全部功能"', '"64 位系统，可使用全部功能"', 1),
    # ===== 表单 helper 规范 =====
    ("webdavbridge/WebDavAddWizardScreen.kt",
     'OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("服务器地址 *（https://dav.example.com/）") }, singleLine = true, modifier = Modifier.fillMaxWidth())',
     'OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("服务器地址 *") }, supportingText = { Text("示例：https://dav.example.com/") }, singleLine = true, modifier = Modifier.fillMaxWidth())', 1),
    ("smbbridge/SmbAddWizardScreen.kt",
     'OutlinedTextField(host, { host = it }, label = { Text("主机地址或 IP *") }, singleLine = true, modifier = Modifier.fillMaxWidth())',
     'OutlinedTextField(host, { host = it }, label = { Text("主机地址或 IP *") }, supportingText = { Text("局域网 IP 或主机名，如 192.168.1.10") }, singleLine = true, modifier = Modifier.fillMaxWidth())', 1),
]

fails = []
for rel, old, new, expect in EDITS:
    p = ROOT + rel
    src = open(p, encoding="utf-8").read()
    n = src.count(old)
    if n != expect:
        fails.append((rel, old[:40], f"count={n}, expect={expect}"))
        continue
    open(p, "w", encoding="utf-8", newline="").write(src.replace(old, new))

if fails:
    for rel, old, msg in fails:
        print(f"FAIL {rel}: \"{old}\" -> {msg}")
    sys.exit(1)
print(f"ALL {len(EDITS)} EDITS APPLIED")
