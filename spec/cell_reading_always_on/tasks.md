# 实施任务：逐格阅读始终常开（REQ-002）

- **需求ID**: REQ-002
- **优先级**: P1（高）
- **基线 commit**: eb2ac412

## 1. 入口图标常量化

### 1.1 竖屏底栏入口修正
- [ ] 修改 `com/mangareader/ui/Reader.kt:2672-2676`，将图标 `if (panelGuideActive) Icons.Default.Close else Icons.Default.GridView` 改为恒 `Icons.Default.GridView`
- [ ] 将文案 `if (panelGuideActive) "退出逐格" else "逐格阅读"` 改为恒 `"逐格阅读"`
- [ ] 保留 `isActive = panelGuideActive` 高亮指示当前开关态，点击行为 `{ showPanelGuideSheet = true }` 不变

### 1.2 横屏胶囊入口修正
- [ ] 修改 `com/mangareader/ui/LandscapeReaderChrome.kt:121-125`，图标恒 `GridView`、文案恒"逐格阅读"，`active = panelGuideActive` 保留高亮

## 2. 持久化与自动生效确认

### 2.1 确认既有持久化链路无需改动
- [ ] 确认 `Reader.kt:291-295` 的 `panelGuideActive` 已用全局 `manga_reader` SharedPreferences 持久化（键 `panelGuideActive`），跨书跨进程生效
- [ ] 确认 `NativeReaderScreen.kt:729-734` 的 DisposableEffect 在进书时读取 panelGuideActive 初值，为 true 且满足可用性条件即自动挂载
- [ ] 确认 `onToggleActive`（`Reader.kt:3451-3454`）面板内切换开关并落盘逻辑不变
- [ ] 确认不满足可用性条件时（条漫/竖翻/瓦片/仿真）开关保持但不挂载，不报错

## 3. 验证与测试

### 3.1 功能验证
- [ ] 验证开启逐格阅读后退出阅读、换一本书再进、杀进程重开，逐格仍处于开启状态
- [ ] 验证关闭后同样持久保持关闭，后续进书不再自动进入逐格模式
- [ ] 验证开启状态下底栏图标始终是"逐格阅读" + GridView 图标（不变成"退出逐格" + Close 图标）
- [ ] 验证开启状态下底栏入口有高亮指示（active = panelGuideActive）
- [ ] 验证点击入口打开设置面板，面板内开关控制开/关（非点击入口直接切换）

### 3.2 可用性条件验证
- [ ] 验证条漫/竖翻/瓦片/仿真翻页等不满足条件时，开关保持开启但不挂载逐格覆盖层，以普通页面显示
- [ ] 验证关闭开关立即卸载逐格覆盖层并恢复普通阅读显示，开关状态即时落盘
- [ ] 验证会话内切换开关即时生效，无需重新进书

## 4. 审查与确认

### 4.1 代码审查
- [ ] 确认仅改两处入口呈现参数，未触碰 panelGuideActive 持久化、DisposableEffect 挂载判定、onToggleActive 逻辑
- [ ] 确认 active 高亮仍能正确指示开关态，用户可一眼看出是否已开启