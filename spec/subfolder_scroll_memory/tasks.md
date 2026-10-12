# 实施任务：子文件夹滑动记忆点核实与补全（REQ-007）

- **需求ID**: REQ-007
- **优先级**: P2（中）
- **基线 commit**: eb2ac412

## 1. 先核实是否存在缺失（REQ-007-A）

### 1.1 核实所有"首页→子文件夹"路径
- [ ] 核实步骤1：确认首页书架点击文件夹书（d:/z:/smb:/webdav:）后进入 Reader 目录页（`Library.kt:1721` openBookThrottled → Reader），该路径子文件夹记忆已由 TocScrollStore 覆盖
- [ ] 核实步骤2：搜索 `Library.kt` 内是否有独立子文件夹浏览界面（非 Reader 目录页），grep openFolder/navStack/FolderBrowse 等关键词
- [ ] 核实步骤3：检查 `FolderManageScreen.kt` 是否有子文件夹列表界面需滚动记忆
- [ ] 记录核实证据，判定是否所有路径均已被 TocScrollStore 覆盖

## 2. 视核实结果补全或仅验证

### 2.1 若已覆盖（路径A 即全部）—仅验证
- [ ] 验证 TocScrollStore 正确性：滚动→返回→重进落原位附近
- [ ] 验证进程重启后仍记得
- [ ] 验证不同子文件夹独立（按 bookId|层路径 键独立）
- [ ] 验证 LRU 128 上限生效，超限淘汰最久未访问

### 2.2 若存在未覆盖的子文件夹界面—新增 LibrarySubFolderScrollStore
- [ ] 在 `com/mangareader/ui/LibrarySubFolderScrollStore.kt`（新增）参照 TocScrollStore 模式实现 `fun get(ctx, folderKey: String): Int?` / `fun put(ctx, folderKey: String, index: Int)`
- [ ] 键为文件夹身份（d: 路径或 SAF docId 或远程路径），独立于 TocScrollStore 的 `bookId|层路径` 键空间
- [ ] LRU 128 + org.json JSONArray 落盘 SharedPreferences（新键 `lib_subfolder_scroll`，与 `toc_scroll_positions` 独立）
- [ ] 接入该界面的滚动存取：滚动停止时 put 首可见 index，列表就绪时 get 恢复（参照 `ReaderTocSheet.kt:348,353` 接入方式）

### 2.3 内容变化容错
- [ ] 恢复时 `index.coerceAtMost(items.size - 1)`，避免文件增删后越界（REQ-007-F）
- [ ] 不同子文件夹按 folderKey 独立存储，互不干扰（REQ-007-G）

## 3. 验证与测试

### 3.1 功能验证
- [ ] 验证已先核实首页子文件夹界面是否有滑动记忆点（有据可查）
- [ ] 验证滚动后返回再进，落回原位附近（以首个可见项 index 为准，列数/屏幕变化时误差一屏内可接受）
- [ ] 验证进程重启后再次进入同一子文件夹仍能恢复到上次滚动位置
- [ ] 验证不同子文件夹记忆独立，互不干扰
- [ ] 验证记忆数量有上限（LRU 128），不无界增长
- [ ] 验证内容变化后恢复不越界/不错乱

### 3.2 回归验证
- [ ] 验证阅读器目录页签的子文件夹记忆（TocScrollStore）不受影响，不重复实现
- [ ] 验证首页书架主列表滚动记忆（library_scroll_index）不受影响
- [ ] 若新增 Store，确认与 TocScrollStore 键空间不冲突（lib_subfolder_scroll vs toc_scroll_positions）

## 4. 审查与确认

### 4.1 代码审查
- [ ] 确认核实覆盖 Library/FolderManage/Reader 全部子文件夹入口，无隐蔽路径遗漏
- [ ] 确认若无需补全则仅出验证报告，无代码改动（符合需求"若已存在则仅验证"）
- [ ] 确认新增 Store 存 index 不存像素 offset（列数/屏幕变化容差一屏内）