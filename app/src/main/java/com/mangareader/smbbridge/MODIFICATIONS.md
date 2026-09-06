# 参考项目代码修改记录

本文件记录对参考项目 `com.hippo.ehviewer` 包代码的所有修改点。

## LocalLibrary.kt
- **修改**: `localLibraryDb` 从 `roomDb<LocalLibraryDatabase>("local_library.db")` 改为 `SmbDbBridge.localLibraryDb`
- **原因**: 漫读使用单一 MangaDatabase，不创建独立的 LocalLibraryDatabase Room 实例
- **影响**: `roomDb` import 已移除，新增 `SmbDbBridge` import

---

## 任务 1.4 编译修复记录（2026-09-05）

### 一、删除的参考项目文件（UI-only，无核心功能引用）
参考项目代码中以下文件仅服务于参考项目自身 UI，且不被拷入的核心代码（smb/gallery/library/jni/util/image/coilG/webdav/core）引用，为消除其 UI 依赖编译错误而删除：
- `eu/kanade/tachiyomi/ui/reader/BottomReaderBar.kt`（依赖 FlexibleBottomAppBar、RollingNumber 等）
- `eu/kanade/tachiyomi/ui/reader/ReaderAppBars.kt`
- `eu/kanade/tachiyomi/ui/reader/ChapterNavigator.kt`
- `eu/kanade/tachiyomi/ui/reader/ReaderPageSheet.kt`
- `eu/kanade/tachiyomi/ui/reader/PageIndicatorText.kt`
- `eu/kanade/tachiyomi/ui/reader/ReaderContentOverlay.kt`
- `eu/kanade/tachiyomi/ui/reader/viewer/`（整个目录：ViewerNavigation + navigation/ 5 个文件）
- `eu/kanade/tachiyomi/ui/reader/setting/TappingInvertMode.kt`
- `eu/kanade/tachiyomi/util/lang/RectExtensions.kt`、`eu/kanade/tachiyomi/util/view/WindowExtensions.kt`
- `moe/tarsin/ContextBridges.kt`（依赖 DestinationsNavigator/MainActivity）

### 二、从参考项目补拷入的文件（零 import 修改）
- `app/.../com/hippo/ehviewer/ktbuilder/Coil.kt` → `com/hippo/ehviewer/ktbuilder/Coil.kt`（imageRequest/diskCache/imageLoader/execute 扩展）
- `core/ui/.../icons/EhIcons.kt` → `com/ehviewer/core/ui/icons/EhIcons.kt`（替换此前的占位 stub）
- `core/ui/.../icons/reader/*.kt`（11 个阅读器图标）→ `com/ehviewer/core/ui/icons/reader/`

### 三、新建的胶水文件（非参考项目原文件）
- `com/hippo/ehviewer/library/LocalTokens.kt`：从参考项目 `LocalHistory.kt`（1012 行，依赖已删除的 model/UI 代码，未整体拷入）**原样提取** 13 个 `*_TOKEN` 常量 + `parentRelativeOfFile()`。被 `ArchivePageLoader` / `LocalGalleryInfo` / `FolderGalleryIndex` / `ZipAsDirListing` 引用。
- `moe/tarsin/Snackbar.kt`：从参考项目 `ContextBridges.kt` **原样提取** `snackbar()` 扩展。被 `coroutines/Result.kt` 引用。
- `com/hippo/ehviewer/BuildConfig.kt`：stub，`const val COMMIT_TIME = 0L`（参考项目为构建期注入的 Long；漫读真实 BuildConfig 在 `com.mangareader` 包）。

### 四、Stub 文件的实质修改
- `com/ehviewer/core/i18n/R.kt`：string 常量从 String 占位改为**映射真实 Android 资源**（`com.mangareader.R.string.*` Int 值）。原因：拷入代码运行时调用 `appCtx.getString(R.string.xxx)`（如 `ExceptionUtils.displayString()`、`ZipMemberCover`），String 占位会导致运行时崩溃；对应 38 条字符串已添加到 `app/src/main/res/values/strings.xml`。
- `com/mangareader/data/Database.kt`：`smbSourceDao()` 加 `abstract override`（MangaDatabase 继承 LocalLibraryDatabase stub，成员重声明需 override）。

### 五、build.gradle.kts 变更
- `kotlin { compilerOptions }` 新增两条全局 opt-in（避免修改参考文件源码）：
  - `-opt-in=kotlin.contracts.ExperimentalContracts`（`moe/tarsin/coroutines/LockTracker.kt` 使用 contract）
  - `-opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi`（`com/hippo/ehviewer/image/image/Image.kt` 使用 AtomicList 等）
---

## 业务功能实现记录（2026-09-05 "全部完成"轮次，任务组 2-7 / 11-17）

### 新建文件（漫读侧胶水层，非参考项目文件）
**smbbridge/**：`SmbPaths.kt`（路径解析）、`SmbPageAdapter.kt`（阅读会话+页面供给）、
`SmbBookRouter.kt`（格式分发：Stream/TarChunk/SolidExtract(回退整档)/DocumentExtract/Folder）、
`SmbCoverLoader.kt`、`SmbScanner.kt`、`SmbUnsupportedFormatHandler.kt`、`SmbServersScreen.kt`
**webdavbridge/**：`WebDavPaths.kt`、`WebDavPageAdapter.kt`、`WebDavBookRouter.kt`（含 RANGE 降级）、
`WebDavCoverLoader.kt`、`WebDavScanner.kt`、`WebDavUnsupportedFormatHandler.kt`、
`WebDavRangeFallbackHandler.kt`、`WebDavServersScreen.kt`、`WebDavBridges.kt`（DbBridge+AppContextBridge）
**native/**：`RemotePageSupply.kt`（页面供给抽象接口）

### 修改的漫读侧文件（全部纯增量，本地路径行为零改动）
- `native/PagerReaderView.kt`：+`remotePageSupply` 属性；loadPageAsync 在 raw 取字节处分流
  （supply!=null 时跳过 ensureNativeArchive 与 engine.loadPageRaw）；Coil 解码/动图/裁边/超分管线不变
- `native/WebtoonReaderView.kt`：同构（两处 `engine.loadPage` 调用点分流，loadBitmap 供给）
- `ui/Reader.kt`：enterReader 顶部 smb:/webdav: 早退分流 → enterRemoteReader/attachRemoteSession；
  远程供给挂接 LaunchedEffect + DisposableEffect(会话关闭)；RemoteUnsupportedOverlay 占位 +
  downloadRemoteUnsupported 下载后 rehome 本地打开；本地三分支逻辑零改动
- `viewer/ArchiveEngine.kt`：+requireNotRemote 守卫（listPages/loadPageRaw/loadPageSize/
  loadPage/children/loadCover/loadCoverRaw 七个公开方法入口）
- `viewer/CoverMapper.kt`：CoverFetcher.fetch 新增 cv:smb:/cv:webdav: 分流（cv: 本地前缀零改动）
- `ui/SettingsScreen.kt`：+SMB/WebDAV 服务器管理入口卡片与子页渲染（现有设置项零改动）
- `data/Database.kt`：+`rehomeBookToLocal`/`deleteSmbBooks`/`deleteWebDavBooks` 三个 DAO 方法（仅新增）
- `App.kt`：+WebDavDbBridge.mangaDatabase 注入
- `proguard-rules.pro`：+参考包/smbj/ktor 整包冻结规则
- `build.gradle.kts`：+两条实验 API opt-in（contracts/atomics）

### 关键技术决策
1. **页面供给走原始字节**：PagerReaderView 的 supply 返回原始字节 → 复用其 Coil3 解码 +
   动图识别 + 裁边 + 超分 + SSIV 全管线（与本地阅读体验一致）；参考项目 PageLoader 的
   demand 机制经 `navigate(ReaderNavigation)` 驱动，等待页面落盘后读原始字节。
2. **路径语义 = 前缀空间**：`*_Gateway.listDirectory`/`SmbArchiveByteSource`/`withOpenFile`
   内部经 resolveLocation 自动拼 `source.pathPrefix`；故扫描器从空串起步，filePath 的
   `{share}/{relativePath}` 段在 Router 按 fixed-share/server-root 规则转换为 gateway remote
   （SmbBookRef.rel / fullRel，单测覆盖）。
3. **PageLoader 生命周期桥接**：useXxxPageLoader 的 block 挂起于 `adapter.awaitClose()`，
   close() 触发返回 → autoCloseScope 关闭字节源；"调用方持有到 close"语义与参考一致。
4. **RANGE 降级**：WebDavBookRouter 捕获 RemoteRangeNotSupportedException → 整档下载
   （RemoteArchiveOpen.ensureWebDavArchive）→ 本地 useArchivePageLoader 重开（v1 静默）。
5. **WebDAV TLS**：v1 trust-all（WebDavClient 原行为；实体无 acceptAnyCert 列），对话框留扩展点。
6. **测试**：SmbBridgeLogicTest / WebDavBridgeLogicTest（路径解析/格式判定/守卫常量，
   含成员后缀、server-root 组合、畸形输入拒绝）。

---

## 真机反馈修复（2026-09-05 晚，日志实锤轮）

### 日志结论（log_export_0905_214705，Redmi 22081212C / Android 12 / 堆上限 512MB）
1. **翻页闪退 = OOM**：`loadDirPageRaw → loadPageRaw → PagerReaderView.loadPageAsync` 分配 12~17MB 失败。
   根因：上个会话把本地翻页从 engine.loadPage（原生下采样 Bitmap）改成 loadPageRaw 读原图字节 → Coil
   再解码（字节+Bitmap 双份内存 + bitmapCache），堆耗尽。
2. **SMB/WebDAV 表单"不能输入"**：无任何崩溃/异常栈，键盘有弹出活动（MIUI HandWritingStub），
   判定 MIUI + Compose AlertDialog 焦点兼容问题。

### 修复（本地动图 + 翻页内存完整回退 v1.2 方案，仅本地；远程路径保持不动）
- `settings.gradle.kts` / `app/build.gradle.kts`：恢复 APNG4Android 4 个 source module
  （app/lib/apng4android/{frameanimation,apng,awebp,gif}，源码一直在项目内未删）+ vectordrawable-animated
- `native/PagerReaderView.kt`：
  - `makeAnimDrawable(raw, kind)` 恢复 APNG4Android 构造（WebPDrawable/APNGDrawable/GifDrawable，
    含 v1.2 的 header 预热防 0×0 空白；去掉 API 28 门槛——APNG4Android 全 API 兼容）
  - `loadPageAsync` 本地分支三分流：动图（peekAnimKindFast → raw → APNG Drawable → pageAnimDrawables）/
    静图（engine.loadPage 原生下采样，**修复 OOM**）→ trim → SR → cache；远程分支保持 raw→Coil 不变
- `native/AnimPagerReaderView.kt`：`makeAnimDrawable` 同步恢复 APNG4Android（本地专用视图）
- `SmbServersScreen.kt` / `WebDavServersScreen.kt`：表单从 Compose AlertDialog 改为
  **原生 AlertDialog.Builder + EditText**（ScrollView 可滚动，校验失败不关窗），绕开 MIUI 输入兼容层
- `ui/SettingsScreen.kt`：设置页顶部新增「导出运行日志（临时）」——打包崩溃/ANR/logcat(3000行)
  写入 log_export_*.txt 并弹系统分享（FileProvider，file_paths.xml 补 files_root）
- Coil 解码器注册（App.kt）保持不变：仅服务封面与远程书路径，本地阅读不再经过 Coil

### 悬留事项
- Rust 动图解码器（task 8）需 nightly 工具链（libwebp-sys2 用 #![feature]，stable 报 E0554），
  装 nightly 后在 build.gradle.kts 恢复 `-DENABLE_RUST_WEBP=ON`（当前已注释）
- WebDAV 表单同样原生实现；若原生表单仍无法输入，需设备端输入法日志进一步排查

## 缓存上限与删除对应清理（2026-09-06 晚）

### OriginDiskCache.kt（参考文件，1 行修改，已记录）
- `originBudgetBytes()`：`readCacheSize <= 0`（哨兵）→ 返回 `Long.MAX_VALUE`，trim 不再删除任何缓存 = **无上限**。
  其余数值仍钳制 320~5120 MB。

### 设置 UI
- 两个设置屏的「阅读页缓存上限」改为选择式：无上限 / 5GB / 2GB / 1GB / 640MB（默认）/ 自定义(MB)，
  native 单选对话框 + 自定义数值输入（与表单同思路，绕开 MIUI 输入兼容层）。

### 删除服务器 = 对应清理（用户要求，对齐本地删除语义）
- 删除前按该书源书籍清单逐本清理：整档/页缓存文件（SmbCache.cachePathForRemoteFile）、
  封面缩略图（thumbCachePath）、流式页目录（ArchiveStreamPageCache.dirFor(cacheKey)），
  然后再走原有 SmbRepository.delete + 书籍级联删除。WebDAV 同构。
- DAO 新增 getSmbBooks / getWebDavBooks（删除前枚举书籍清单）。
- 未覆盖：浏览过但未入架的零散缩略图（小体积，走设置页「清理全部」兜底）。

---

## 真实服务器 E2E 实测与按源缓存布局重构（2026-09-06 深夜）

### 背景（高级测试工程师实测轮次）
桌面 JVM 直跑 app 真实源码（73 文件 + 21 个 Android 专属桩），连真实 Windows SMB 服务器
（smbtest 共享）与 wsgidav WebDAV 服务器，33 项端到端用例
（SmbRealE2eTest 21 + WebDavRealE2eTest 14；资产含中文/特殊字符/深层嵌套/60页67MB zip/
381MB 真实 zip/损坏 zip/无图片 zip/空目录；range 流式读做 sha256 字节级对照）。
测试工程留存：`C:\Users\dhgj\.zcode\workspace\default\jvmtest`（回归可复跑）。

### 发现并修复：WebDAV 文件名含 `#` 整层目录不可用（P1）
- 现象：PROPFIND href 解码后直接赋 URL `encodedPath`，`#` 被 Url 当 fragment 截断 ——
  目录名显示缺尾（`漫画B【特殊】!@#` → `漫画B【特殊】!@`），进入该目录子列表全空（207 但 0 条目）。
- 修复：`WebDavClient.resolveHref` 对解码后路径**按段重编码**（`encodeRawPathSegments`）；
  相对 href 的父段先 `decodeHref` 再统一编码，避免对已编码 %xx 二次编码成 %25xx。
- 回归：WebDavRealE2eTest wd03/wd06 覆盖；漫画命名 `#01` 类场景恢复可用。

### 结构级改造：SMB/WebDAV 磁盘缓存按源分目录（删除源 = 磁盘缓存全清）
- 旧布局：页缓存/缩略图以 `sha256(含 sourceId 的 key)` **平铺**在
  `cache/smb_cache`、`cache/smb_thumb_cache`（webdav 同）根下 —— 单向哈希无法反查来源，
  删除服务器时只能按"已入架书籍清单"逐本清，浏览过但未入架的缓存成孤儿（只能 LRU 兜底）。
- 新布局：页 = `<root>/<sourceId>/<hash>.<ext>`，缩略图 = `<root>/<sourceId>/<hash>.jpg`。
  - `SmbCache.kt` / `WebDavCache.kt`：`cachePath` / `cachePathForRemoteFile` / `thumbCachePath`
    加 sourceId 目录段（写路径本就 `parent.mkdirs()`，自动兼容）
  - 新增 `deleteSourceCache(sourceId)`：整目录删除两个子目录 + 清内存 knownPresent
  - `sweepLegacyFlatCache()`：旧平铺散文件一次性清扫（挂 `ensureRootDirs`，原子标记幂等；
    哈希不可逆无法迁移，缓存可重下，不做迁移）
- `OriginDiskCache.kt`：`collectFlatOrigin` / `collectThumbFiles` 平铺一层扫描改 `walkTopDown`
  递归 —— 保持新布局仍受容量预算/LRU 管理（trim 的 markAbsent 路径回调不受影响）。
- 删除链补全：`SmbRepository.delete` / `WebDavRepository.delete` 接入 `deleteSourceCache`。
  与 UI 层既有"按书籍清单逐本清理"（clearServerBookCachesSmb/WebDav）叠加不冲突：
  书籍清单负责已入架的确定性文件（含 archive_pages 流式页目录），按源目录兜底清未入架的孤儿。
- UI 文案如实化：SmbServersScreen / WebDavServersScreen 删除对话框改为
  「配置、密码、目录索引、页缓存与封面缩略图」。

### 验证
- jvmtest E2E：SMB 21/21、WebDAV 14/14（新增 smb21/wd14 缓存整目录删除断言）
- app 全量编译 + 单测通过

### 补记：SettingsCollector.kt（UI 依赖清理，偏差清单 D 类）
- 漫读版移除了依赖已删 UI/主题系统的设置收集项（`UiModeManager` 深色模式跟随、
  `keepNoMediaFileStatus` 媒体扫描等 import 与对应 update 函数）。
- 原因：这些功能依赖参考项目 `ui/` 包的 `keepNoMediaFileStatus` 扩展与 AppCompat 主题
  基建（当轮有意删减），编译必需。SMB/WebDAV/阅读相关设置字段的收集漫读设置页已全部接入。
