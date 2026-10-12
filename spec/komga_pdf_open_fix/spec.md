# 需求规格：Komga 配置的 PDF 打不开

- **需求ID**: REQ-008
- **标题**: Komga 来源 PDF 书籍打开失败修复
- **优先级**: P1（高）
- **基线 commit**: eb2ac412
- **状态**: 待评审

## 1. 背景与问题

用户反馈通过 Komga 自建漫画服务器配置的 PDF 书籍无法打开。当前 Komga 书籍打开走 `KomgaBookRouter.openKomgaBook`，PDF 判定依据 `book.media.mediaType == "application/pdf"`，PDF 页面图片 URL 带 `?convert=jpeg` 参数由服务端转图。打不开的可能方向：PDF 的页面拉取/转换 URL 构造、服务端 convert 支持、客户端解码链路。需先看 X 盘 komiho 是否有解决方案，或自行定位问题根因后修复。

## 2. 核心输入

1. Komga 服务器上 mediaType 为 `application/pdf` 的书籍
2. Komga API 的页表响应（`getBookPages`）
3. PDF 页面图片 URL（带 `?convert=jpeg`）

## 3. 核心输出

1. PDF 书籍成功打开并显示页面
2. 打开失败时的明确错误信息

## 4. EARS 需求

### 4.1 先调研后修复

**REQ-008-A**
WHEN 实施本需求前 THE SYSTEM SHALL 先查阅 X 盘 komiho 是否已有解决方案，若有则按其方案修复；若无则基于现有代码与日志定位 Komga PDF 打不开的根因后修复。

### 4.2 PDF 打开成功

**REQ-008-B**
WHEN 用户打开一本 Komga 来源且 mediaType 为 `application/pdf` 的书籍 THE SYSTEM SHALL 成功加载并显示其全部页面，不因 PDF 格式而失败。

**REQ-008-C**
WHEN Komga PDF 书籍的页面图片 URL 被构造 THE SYSTEM SHALL 正确携带服务端转图参数（如 `?convert=jpeg`），使返回的可解码图片能被客户端正常解码显示。

**REQ-008-D**
WHEN Komga 服务端对 PDF 的 convert 转图返回异常或空 THE SYSTEM SHALL 给出明确的失败提示（如"该 PDF 无法转换显示"），不静默白屏或崩溃。

### 4.3 约束

**REQ-008-E**
WHILE 修复 Komga PDF 打开问题 THE SYSTEM SHALL NOT 破坏 Komga 非 PDF（CBZ/ZIP/图片）书籍的正常打开与阅读。

**REQ-008-F**
WHILE 修复 Komga PDF 打开问题 THE SYSTEM SHALL NOT 破坏本地/SMB/WebDAV/OPDS 来源 PDF 的正常打开（若这些来源 PDF 已可用）。

**REQ-008-G**
IF Komga PDF 打不开的根因在服务端（服务端不支持 convert 或 PDF 损坏） THEN THE SYSTEM SHALL 在客户端给出可理解的提示，不把服务端问题伪装成客户端 bug。

## 5. 验收条件

1. 已先查阅 X 盘 komiho 方案或定位根因（有据可查）。
2. Komga 配置的 PDF 书籍可正常打开并逐页显示。
3. PDF 页面图片 URL 转图参数正确，客户端可解码。
4. 服务端转图异常时有明确提示，不白屏/不崩溃。
5. Komga 非 PDF 书籍不受影响。
6. 其他来源 PDF 不受影响。

## 6. 职责边界

- 不负责 Komga 服务端本身的 PDF 转图能力（仅客户端正确请求与处理）
- 不负责 PDF 本地解析引擎（Komga PDF 走服务端 convert 转图，不走本地 PDF 引擎）
- 不负责 Komga 连接配置/认证（既有链路）