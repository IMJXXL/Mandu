# 实施任务：归档密码功能 UI 重构（REQ-001）

- **需求ID**: REQ-001
- **优先级**: P1（高）
- **基线 commit**: eb2ac412

## 1. 密码仓储封装

### 1.1 新建 ArchivePasswordRepo 仓储对象
- [ ] 在 `com/mangareader/util/ArchivePasswordRepo.kt`（新增）实现 `load(): Set<String>`、`add(pwd: String)`、`remove(pwd: String)`、`replaceAll(set: Set<String>)` 四个方法，底层读写 `Settings.archivePasswds`，写操作即时 `apply()` 落盘
- [ ] 确认 Repo 单例读写同一 `archive_passwds` 键，不按来源分键，保证本地/SMB/WebDAV 三路共用

## 2. 独立全屏管理界面

### 2.1 新建 ArchivePasswordScreen 全屏 Composable
- [ ] 在 `com/mangareader/ui/ArchivePasswordScreen.kt`（新增）实现 `@Composable fun ArchivePasswordScreen(onBack: () -> Unit)`，LazyColumn 展示每条密码（明文，与现有弹窗一致不加掩码）
- [ ] 顶部输入栏 + 添加按钮实现单条新增（非空且不重复才 add），每条尾部删除图标实现单条删除，所有增删经 `ArchivePasswordRepo` 即时落盘
- [ ] 返回手势由 `onBack` 回退，因已即时落盘，返回不丢失修改

### 2.2 三处入口替换为导航至 ArchivePasswordScreen
- [ ] 修改 `com/mangareader/ui/FolderManageScreen.kt:527-532`，行内条目点击改为导航至 `ArchivePasswordScreen`（沿用同目录既有 Compose 导航方式）
- [ ] 修改 `com/mangareader/smbbridge/SmbSettingsScreen.kt:211-216`，替换 `showArchivePasswdsDialog` 调用为导航至 `ArchivePasswordScreen`
- [ ] 修改 `com/mangareader/webdavbridge/WebDavSettingsScreen.kt:230-235`，同上替换
- [ ] grep 全量确认 `showArchivePasswdsDialog` 调用点清零后，将 `SmbSettingsScreen.kt:298` 的旧弹窗函数标记废弃（保留至三处替换完成后移除）

## 3. 兜底密码输入弹窗

### 3.1 新建 ArchivePasswordInputDialog 弹窗
- [ ] 在 `com/mangareader/ui/ArchivePasswordInputDialog.kt`（新增）实现 `@Composable fun ArchivePasswordInputDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit)`，单输入框 + 确认/取消
- [ ] 在 `useArchivePageLoader`（`ArchivePageLoader.kt:102`）的 `passwdProvider` 回调处接入此弹窗，确保自动尝试全部失败后弹出（替代当前隐式输入路径）

## 4. 失败提示区分

### 4.1 区分密码错误与内容缺失
- [ ] 修改 `com/hippo/ehviewer/gallery/ArchivePageLoader.kt:101-106`，在 `check(opened>0)` 之前先判 `needPassword()`：若需要密码且当前未命中，提示"密码错误，请重新输入"并回弹输入窗；若不需要密码但 opened==0，才提示"文件无内容/损坏"
- [ ] 在打开失败时增加日志标记 needPassword 结果，便于核实 native 层判定准确性

## 5. 验证与测试

### 5.1 功能验证
- [ ] 验证归档密码管理界面：独立全屏、一条一记录、单条新增、单条删除、返回手势正常回退不丢失
- [ ] 验证加密压缩包自动尝试：打开时依次尝试已存密码，命中的优先使用
- [ ] 验证全部失败后必弹 ArchivePasswordInputDialog，手动输入成功后密码自动加入密码库
- [ ] 验证密码相关失败不再被"没有内容""文件缺失"等无关提示掩盖
- [ ] 验证密码集合为空时直接弹输入窗，不进行无意义空尝试

### 5.2 回归验证
- [ ] 验证本地/SMB/WebDAV 三路共用的密码记忆库行为不破坏
- [ ] 验证三处入口（文件夹管理/SMB/WebDAV 设置）均能正确进入独立管理界面
- [ ] 验证 Repo 即时落盘：新增/删除后杀进程重进，修改仍在

## 6. 审查与确认

### 6.1 代码审查
- [ ] 确认 `showArchivePasswdsDialog` 调用点已全部替换，无残留
- [ ] 确认 ArchivePasswordRepo 未按来源分键，三路共用同一 `archive_passwds` 键
- [ ] 确认失败提示区分逻辑未误伤"文件损坏"等真正内容缺失场景