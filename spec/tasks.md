# MangaReader 实施任务总览

- **项目**: Android 漫画阅读器（MangaReader）
- **技术栈**: Kotlin + Jetpack Compose
- **基线 commit**: eb2ac412
- **生成日期**: 2026-10-12

本文件为8个需求的实施任务汇总索引，每个需求的详细任务清单见各自子目录下的 `tasks.md`。

---

## 需求清单与优先级

| 序号 | 需求ID | 标题 | 优先级 | 子目录 | 独立性 |
|------|--------|------|--------|--------|--------|
| 1 | REQ-001 | 归档密码功能 UI 重构 | P1（高） | `spec/archive_password_ui/` | 独立 |
| 2 | REQ-002 | 逐格阅读始终常开 | P1（高） | `spec/cell_reading_always_on/` | 独立 |
| 3 | REQ-003 | 设置 UI 优化 | P2（中） | `spec/settings_ui_optimize/` | 独立 |
| 4 | REQ-004 | 锁定竖屏失效修复 | P1（高） | `spec/portrait_lock_fix/` | 独立 |
| 5 | REQ-005 | 合并/增强按钮重组 | P1（高） | `spec/merge_enhance_reorg/` | 独立 |
| 6 | REQ-006 | 点击漫画画面不漂移 | P1（高） | `spec/tap_no_drift/` | 独立 |
| 7 | REQ-007 | 子文件夹滑动记忆点 | P2（中） | `spec/subfolder_scroll_memory/` | 独立 |
| 8 | REQ-008 | Komga PDF 打不开修复 | P1（高） | `spec/komga_pdf_open_fix/` | 独立 |

**依赖关系**：8个需求相互独立，可并行实施。其中 REQ-001 与 REQ-003 均涉及设置页 UI，建议串行合入避免合并冲突；REQ-005 涉及数据库 migration，需独立 commit 便于回退。

---

## REQ-001：归档密码功能 UI 重构（P1）

**目标**：归档密码从系统原生弹窗改为独立全屏管理界面；加密压缩包打开失败时区分密码错误与内容缺失；自动尝试全部失败后弹兜底输入窗。

**主要改动文件**：
- 新增 `com/mangareader/ui/ArchivePasswordScreen.kt`（全屏管理界面）
- 新增 `com/mangareader/ui/ArchivePasswordInputDialog.kt`（兜底输入弹窗）
- 新增 `com/mangareader/util/ArchivePasswordRepo.kt`（密码仓储）
- 修改 `FolderManageScreen.kt`、`SmbSettingsScreen.kt`、`WebDavSettingsScreen.kt`（三处入口替换）
- 修改 `ArchivePageLoader.kt`（失败提示区分）

**任务组**：密码仓储封装 → 独立全屏管理界面 → 兜底密码输入弹窗 → 失败提示区分 → 验证测试 → 审查确认

详见：`spec/archive_password_ui/tasks.md`

---

## REQ-002：逐格阅读始终常开（P1）

**目标**：逐格阅读开关持久化（已实现，确认无需改动）；底栏图标始终为功能入口"逐格阅读"，不切换为"退出逐格"文案。

**主要改动文件**：
- 修改 `com/mangareader/ui/Reader.kt:2672-2676`（竖屏底栏入口图标/文案常量化）
- 修改 `com/mangareader/ui/LandscapeReaderChrome.kt:121-125`（横屏胶囊入口同上）

**任务组**：入口图标常量化 → 持久化确认 → 验证测试 → 审查确认

详见：`spec/cell_reading_always_on/tasks.md`

---

## REQ-003：设置 UI 优化（P2）

**目标**：纯正向优化设置页布局与文字排列，不破坏任何现有功能。标签精简、间距统一、主副层级分明、宽度约束防溢出。

**主要改动文件**：
- 修改 `ReadingPrefsScreen.kt`、`ReaderSettingsSheet.kt`、`FolderManageScreen.kt`、`SettingsScreen.kt`、`AppearanceSettingsScreen.kt`、`GroupSortSettingsScreen.kt`、`ShelfManageSettingsScreen.kt`

**任务组**：逐页审查记录问题 → 标签精简 → 布局参数统一 → 验证测试 → 审查确认

详见：`spec/settings_ui_optimize/tasks.md`

---

## REQ-004：锁定竖屏失效修复（P1）

**目标**：全局默认"锁定竖屏"偏好在进书/切书/条漫分支时失效，根因为 `getInt("orientation_$bookId", UNSPECIFIED)` 默认值未回退全局。新增 resolveOrientation 集中解析。

**主要改动文件**：
- 修改 `com/mangareader/ui/Reader.kt:2344`（进书方向应用）
- 修改 `com/mangareader/ui/Reader.kt:2056`（切书方向应用）
- 新增 `Reader.kt` 私有函数 `resolveOrientation(bookId)`

**任务组**：先核实失效路径 → 新增 resolveOrientation → 替换方向应用点 → 验证测试 → 审查确认

详见：`spec/portrait_lock_fix/tasks.md`

---

## REQ-005：合并/增强按钮重组（P1）

**目标**：首页多选模式上方加"增强"菜单，底部原增强位置改"合并/解散"；新增虚拟书合并功能（多本合并为单本 A，点 A 看成员，解散恢复）。

**主要改动文件**：
- 修改 `com/mangareader/data/Database.kt`（Book 加 mergedParentId 字段 + Room migration + 合并/解散 DAO）
- 修改 `com/mangareader/ui/Library.kt`（按钮重组 + MergeBooksDialog + 成员视图）

**任务组**：数据模型扩展 → 上方按钮重组 → 底部合并/解散按钮 → 合并/解散执行 → 成员视图 → 验证测试 → 审查确认

详见：`spec/merge_enhance_reorg/tasks.md`

---

## REQ-006：点击漫画画面不漂移（P1）

**目标**：阅读时点击画面弹出/收起 chrome（上下栏）的瞬间画面漂移。根因为 chrome 占 Scaffold 槽位，显隐改变 content padding。改为 chrome 悬浮 overlay 叠加。

**主要改动文件**：
- 修改 `com/mangareader/ui/Reader.kt:2581-2698`（chrome 移出 Scaffold 槽位）
- 修改 `com/mangareader/ui/Reader.kt:2808` 附近（内容区改 Box 叠加）
- 排查 `LandscapeReaderChrome.kt`（横屏是否需同改）

**任务组**：根因确认 → chrome 移出 Scaffold 槽位 → 内容区改 Box 叠加 → 验证测试 → 审查确认

详见：`spec/tap_no_drift/tasks.md`

---

## REQ-007：子文件夹滑动记忆点（P2）

**目标**：先核实首页书架子文件夹界面是否已有滑动记忆点，若已由 TocScrollStore 覆盖则仅验证，若缺失则参照 TocScrollStore 补全。

**主要改动文件**：
- 排查 `Library.kt`、`FolderManageScreen.kt`（是否有独立子文件夹界面）
- 视核实结果新增 `LibrarySubFolderScrollStore.kt`（若缺失）

**任务组**：先核实是否存在缺失 → 视结果补全或仅验证 → 验证测试 → 审查确认

详见：`spec/subfolder_scroll_memory/tasks.md`

---

## REQ-008：Komga PDF 打不开修复（P1）

**目标**：Komga 来源 PDF 书籍打不开。先调研 X 盘 komiho（已确认不存在），再基于代码与日志定位根因（convert 参数/页表空/mediaType 判定/异常吞），修正后不破坏 Komga 非 PDF 与其他来源 PDF。

**主要改动文件**：
- 修改 `KomgaBookRouter.kt`（isPdf 容错 + humanize 扩展 + 页表空提示）
- 修改 `KomgaApiClient.kt:151-154`（pageImageUrl convert 参数修正）
- 修改 `KomgaPageAdapter.kt`（loadPageRaw 失败传播 + loadBitmap 解码失败提示）

**任务组**：先调研后修复 → mediaType 容错 → convert 参数修正 → 异常明确传播 → 验证测试 → 审查确认

详见：`spec/komga_pdf_open_fix/tasks.md`

---

## 实施建议

1. **优先级顺序**：6个 P1 需求优先于2个 P2 需求。P1 中 REQ-004（竖屏失效）和 REQ-006（画面漂移）改动最小、风险最低，建议先做。
2. **commit 规范**：每个独立能力一个 commit，说明改动范围与动机，不 squash 中间历史（用户偏好可回退可溯源）。
3. **REQ-005 风险最高**：涉及 Room migration，需严格测试 migration 路径，独立 commit 便于回退。
4. **REQ-001 与 REQ-003 协调**：均涉及设置页 UI，建议串行合入避免合并冲突。
5. **先核实后修复**：REQ-004、REQ-007、REQ-008 均要求先核实/调研再动手，禁止盲改。