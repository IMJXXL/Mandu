# 实施任务：设置 UI 优化（REQ-003）

- **需求ID**: REQ-003
- **优先级**: P2（中）
- **基线 commit**: eb2ac412

## 1. 逐页审查记录问题

### 1.1 审查7个设置页并形成问题清单
- [ ] 审查 `com/mangareader/ui/ReadingPrefsScreen.kt`（阅读偏好页），记录拥挤/换行/混排/过长标签位置
- [ ] 审查 `com/mangareader/ui/ReaderSettingsSheet.kt`（书内设置面板），记录问题
- [ ] 审查 `com/mangareader/ui/FolderManageScreen.kt`（文件夹管理页），记录问题
- [ ] 审查 `com/mangareader/ui/SettingsScreen.kt`（主设置页），记录问题
- [ ] 审查 `com/mangareader/ui/AppearanceSettingsScreen.kt`（外观设置页），记录问题
- [ ] 审查 `com/mangareader/ui/GroupSortSettingsScreen.kt`（分组排序页），记录问题
- [ ] 审查 `com/mangareader/ui/ShelfManageSettingsScreen.kt`（书架管理页），记录问题

## 2. 标签精简

### 2.1 精简过长 label 措辞（语义不变）
- [ ] 修改 `ReadingPrefsScreen.kt` 中过长标签，如"非全面屏阅读时隐藏底部导航栏"→"隐藏导航栏(非全面屏)"、"仿真翻页Beta（仅静图左右翻页生效）"→"仿真翻页(Beta)"等，含括号补充说明的移至 info 副说明或缩短
- [ ] 对过长描述性标签提炼为短词，用户偏好简短标签（如"Kindlish"类过长项缩写）
- [ ] 改动仅限字符串字面量（label/info），改前 grep 确认 key 不在改动范围，不触碰任何 `key`、`editor.options`、默认值、读写逻辑

## 3. 布局参数统一

### 3.1 条目间距与分组容器统一
- [ ] 统一各设置页 LazyColumn/Column 的 `verticalArrangement` 与条目 `padding`（条目间 8-12dp，分组间 16-20dp），消除拥挤
- [ ] 统一分组标题（section）与组间分隔样式（字号/颜色/分隔线），分组内条目缩进一致

### 3.2 主副层级与宽度约束
- [ ] 统一主标题与 info 副说明的字号（主 14sp/副 12sp）与颜色（主 onSurface/副 onSurfaceVariant），副说明置于主标题下方独立行不混排
- [ ] 过长标签加 `maxLines(1)` + `TextOverflow.Ellipsis` 或用 `weight(1f)` 分配宽度，确保竖屏/横屏/平板下不溢出/不截断/不错乱
- [ ] Chips 选项块确保不换行挤占

### 3.3 逐页应用布局优化
- [ ] 修改 `ReadingPrefsScreen.kt` 渲染部分（614-732）间距/主副层级/宽度约束
- [ ] 修改 `ReaderSettingsSheet.kt` 书内设置面板条目间距与文字排列
- [ ] 修改 `FolderManageScreen.kt` 文件夹管理页布局
- [ ] 修改 `SettingsScreen.kt` 主设置页分组与间距
- [ ] 修改 `AppearanceSettingsScreen.kt` 外观设置页布局
- [ ] 修改 `GroupSortSettingsScreen.kt` 分组排序页布局
- [ ] 修改 `ShelfManageSettingsScreen.kt` 书架管理页布局

## 4. 验证与测试

### 4.1 布局验证
- [ ] 验证各设置页面布局整齐、分组清晰、文字不溢出/不挤/不乱换行
- [ ] 验证竖屏/横屏/平板三宽度下设置页均不错乱
- [ ] 验证主标题与副说明层级分明，不混排

### 4.2 回归验证（纯正向，不破坏功能）
- [ ] 逐页逐项确认：每项仍可见、仍可改、改后仍生效、仍落盘、跨页/跨进程保持
- [ ] 验证无任何设置项被移除或语义被改变
- [ ] 验证文字过长者已简短化且语义不变

## 5. 审查与确认

### 5.1 代码审查
- [ ] 确认所有改动仅限 Composable 中的布局参数（padding/spacing/Arrangement/maxLines/overflow）与字符串字面量（label/info）
- [ ] 确认未触碰任何 `key`、`editor.options`、默认值、读写逻辑
- [ ] 确认 maxLines(1)+Ellipsis 仅兜底，label 已精简则不触发截断