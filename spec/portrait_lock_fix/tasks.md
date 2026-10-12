# 实施任务：锁定竖屏容易失效修复（REQ-004）

- **需求ID**: REQ-004
- **优先级**: P1（高）
- **基线 commit**: eb2ac412

## 1. 先核实失效路径（禁止盲改）

### 1.1 按核实流程定位根因
- [ ] 核实步骤1：阅读偏好页设"锁定竖屏"（全局 `orientation` = SENSOR_PORTRAIT）
- [ ] 核实步骤2：打开一本书（不单独设方向），观察是否竖屏（预期：失效，得 UNSPECIFIED）
- [ ] 核实步骤3：换另一本书，观察是否竖屏（预期：失效）
- [ ] 核实步骤4：书内面板重选"锁定竖屏"，观察是否恢复（预期：恢复，写了 `orientation_$bookId`）
- [ ] 核实步骤5：条漫模式重复，观察留空时是否失效
- [ ] 确认根因 = `getInt("orientation_$bookId", UNSPECIFIED)` 默认值未回退全局 `orientation`，记录核实证据（日志/截图）

### 1.2 排查所有方向赋值点
- [ ] grep 全部 `requestedOrientation` 赋值点，确认除 `Reader.kt:2344/2056` 外是否有其他赋值点（条漫视图 re-setup、配置重载等）

## 2. 新增方向解析函数

### 2.1 实现 resolveOrientation
- [ ] 在 `com/mangareader/ui/Reader.kt` 新增私有函数 `fun resolveOrientation(bookId: Long): Int`：先读 `orientation_$bookId`（用哨兵默认值如 `Int.MIN_VALUE` 判存在性），不存在则读全局 `orientation`（默认 UNSPECIFIED），返回最终方向值
- [ ] 确认函数纯读取不修改任何存储，键结构 `orientation`（全局）与 `orientation_$bookId`（按书）不变

## 3. 替换方向应用点

### 3.1 进书与切书点替换
- [ ] 修改 `com/mangareader/ui/Reader.kt:2344`，将 `prefs.getInt("orientation_$bookId", UNSPECIFIED)` 改为 `resolveOrientation(bookId)`
- [ ] 修改 `com/mangareader/ui/Reader.kt:2056`，将 `prefs.getInt("orientation_$newBookId", UNSPECIFIED)` 改为 `resolveOrientation(newBookId)`

### 3.2 条漫分支与退出处理
- [ ] 若 grep 发现条漫视图重建/配置重载有独立 `requestedOrientation` 赋值点，一并改用 `resolveOrientation`
- [ ] 确认 `goBack()`（`Reader.kt:2198`）退出阅读器时设 UNSPECIFIED 恢复正常态，保持不变
- [ ] 确认 `saveOrientation`（`Reader.kt:2250-2253`）按书写入逻辑不变

## 4. 验证与测试

### 4.1 功能验证
- [ ] 验证设为锁定竖屏后，条漫留空/内容为空时仍保持竖屏
- [ ] 验证换一本书（新书偏好同为锁定竖屏）后立即竖屏，无需重开设置
- [ ] 验证换一本书（新书偏好为默认/跟随系统）后按新书偏好生效，不被前书残留影响
- [ ] 验证翻页、切章、视图重建过程中竖屏不丢失

### 4.2 回归验证
- [ ] 验证用户单独为某书设方向时仍优先按书键生效（`orientation_$bookId` 优先级最高）
- [ ] 验证未设单本方向时回退全局默认偏好，进书时即时生效
- [ ] 验证退出阅读器恢复正常态（UNSPECIFIED）不被残留
- [ ] 验证全局 orientation=-2（跟随系统）回退行为正确

## 5. 审查与确认

### 5.1 代码审查
- [ ] 确认所有方向应用点均走 `resolveOrientation`，无散落的 getInt 默认值硬编码
- [ ] 确认退出重置（goBack UNSPECIFIED）与进书 resolveOrientation 无冲突竞态
- [ ] 确认横屏锁定不在本次特例处理范围（resolveOrientation 对横屏同样正向生效，属附带）