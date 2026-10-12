# 实施任务：Komga 配置的 PDF 打不开修复（REQ-008）

- **需求ID**: REQ-008
- **优先级**: P1（高）
- **基线 commit**: eb2ac412

## 1. 先调研后修复（REQ-008-A）

### 1.1 komiho 调研结论
- [ ] 确认 X 盘不存在 komiho 项目（已确认 `ls X:/komiho` → NOT_FOUND），记录调研证据
- [ ] 走"基于现有代码与日志定位根因后修复"路线

### 1.2 根因定位（证据驱动）
- [ ] 方向1 convert 参数：抓取实际 HTTP 请求（logcat/抓包），确认 `?convert=jpeg` 是否被 Komga 服务端接受（HTTP 200 返回 JPEG vs 406/415 错误），对照 Komga API 文档确认 PDF 转图正确参数/端点
- [ ] 方向2 页表空：确认 `getBookPages` 对 PDF 是否返回非空 List<PageDto>，若服务端对 PDF 不生成页表则走 line 70"该章节无页面"
- [ ] 方向3 mediaType 判定：确认服务端返回的 `book.media.mediaType` 是否精确 `application/pdf`（可能含 charset 后缀或大小写差异）
- [ ] 方向4 异常吞：确认 `loadPageRaw` catch(Throwable) 返回 null 时上层是否白屏无提示（`KomgaPageAdapter.kt:34-37`）

## 2. mediaType 容错

### 2.1 修正 isPdf 判定
- [ ] 修改 `com/mangareader/komgabridge/reader/KomgaBookRouter.kt:73`，将 `== "application/pdf"` 改为 `startsWith("application/pdf", ignoreCase = true)`，兼容 charset 后缀/大小写

## 3. convert 参数与端点修正

### 3.1 修正 pageImageUrl
- [ ] 修改 `com/mangareader/komgabridge/api/KomgaApiClient.kt:151-154`，视根因定位结果修正 `pageImageUrl` 的 convert 参数/端点
- [ ] 若 Komga 要求不同参数名/值/端点则改；若服务端不支持 convert 则在客户端给出"服务端不支持 PDF 转图"提示（REQ-008-G）

## 4. 异常明确传播

### 4.1 loadPageRaw 失败传播
- [ ] 修改 `com/mangareader/komgabridge/reader/KomgaPageAdapter.kt:26-38`，失败时不再静默返回 null，而是区分错误类型（网络错误/HTTP 状态码/服务端 convert 异常），通过日志/状态标记供上层映射
- [ ] 保留返回 null 兼容上层白屏处理，同时增加可查错误标记

### 4.2 loadBitmap 解码失败提示
- [ ] 修改 `com/mangareader/komgabridge/reader/KomgaPageAdapter.kt:40-62`，解码失败时给出"PDF 转图数据无法解码"提示而非白屏（REQ-008-D）

### 4.3 humanize 扩展错误映射
- [ ] 修改 `com/mangareader/komgabridge/reader/KomgaBookRouter.kt:107-115`，humanize 增加 PDF/convert 相关错误映射（如 HTTP 406/415 → "服务端不支持该 PDF 转图"，decode null → "PDF 页面转换失败"）
- [ ] 修改 `KomgaBookRouter.kt:68-71`，页表空时区分 PDF 给更明确提示（"该 PDF 无页面或服务端未生成页表"）

## 5. 验证与测试

### 5.1 功能验证
- [ ] 验证已先查阅 X 盘 komiho 方案或定位根因（有据可查）
- [ ] 验证 Komga 配置的 PDF 书籍可正常打开并逐页显示
- [ ] 验证 PDF 页面图片 URL 转图参数正确，客户端可解码
- [ ] 验证服务端转图异常时有明确提示，不白屏/不崩溃

### 5.2 回归验证
- [ ] 验证 Komga 非 PDF（CBZ/ZIP/图片）书籍正常打开与阅读不受影响（所有改动仅在 isPdf=true 分支生效）
- [ ] 验证本地/SMB/WebDAV/OPDS 来源 PDF 正常打开不受影响（改动限于 `komgabridge` 包，其他来源走各自 `engine/pdf`）
- [ ] 验证 mediaType 容错不过度（"application/pdf" 前缀足够精确，无其他常见 mediaType 以此开头）

## 6. 审查与确认

### 6.1 代码审查
- [ ] 确认所有改动仅在 isPdf=true 分支生效，isPdf=false 的 CBZ/ZIP/图片链路不动
- [ ] 确认改动限于 `komgabridge` 包，不波及其他来源 PDF 引擎
- [ ] 确认根因在服务端时客户端给出可理解提示，不把服务端问题伪装成客户端 bug（REQ-008-G）
- [ ] 确认异常传播保留返回 null 兼容，不破坏既有重试逻辑