# 仿真翻页（Page Curl）实施任务列表

> 基于 `spec.md`（需求规格）与 `design.md`（技术设计）生成。
> 范围限定为 v1（单页模式 + 静态图片）。
> 关键约束：代码不加注释；中文 commit message；非侵入式（不修改 `PagerReaderView`/`PagerSSIV`/`WebtoonReaderView`）；新增类统一放入 `com.mangareader.native.curl` 子包；构建后复制 APK 到 X 盘；完成后自我审查两次。
> 开关策略：新增独立开关 `isPageCurlAnimation`（默认 false），与既有 `isFlipAnimation` 配合——`isFlipAnimation=false` 无动画；`true`+`isPageCurlAnimation=false` 3D旋转（既有行为不变）；`true`+`isPageCurlAnimation=true` 仿真翻页。

---

## 1. 依赖声明与基础环境

### 1.1 新增 PageCurl 依赖
- [ ] 在 `app/build.gradle.kts` 的 `dependencies` 块新增 `implementation("io.github.oleksandrbalan:pagecurl:1.5.1")`，锁定版本 1.5.1
- [ ] 在项目依赖许可证清单中如实记录 `pagecurl:1.5.1` 及 Apache 2.0 许可证（与项目 GPL v3 兼容）
- **依赖**：无
- **涉及文件**：`app/build.gradle.kts`、项目许可证清单
- **验证方法**：执行 `./gradlew :app:dependencies` 能解析到 pagecurl:1.5.1；许可证清单含该条目
- **预估代码行数**：1 行（build.gradle.kts）+ 许可证清单 1 条

### 1.2 创建 curl 子包目录
- [ ] 创建目录 `app/src/main/java/com/mangareader/native/curl/`，承载全部新增类，与既有 `PagerReaderView` 等物理隔离
- **依赖**：无
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/`
- **验证方法**：目录存在
- **预估代码行数**：0

---

## 2. 配置与设置界面

### 2.1 ReaderConfig 新增 isPageCurlAnimation 字段
- [ ] 在 `app/src/main/java/com/mangareader/data/ReaderConfig.kt` 新增字段 `isPageCurlAnimation: Boolean = false`，默认关闭（用户主动开启才用仿真翻页，既有体验不变）
- [ ] 在 ReaderConfig 的持久化读写处新增 `prefs.getBoolean("isPageCurlAnimation", false)` 读取与 `prefs.putBoolean("isPageCurlAnimation", value)` 写入，与既有 `isFlipAnimation` 持久化方式对齐
- [ ] 不修改既有字段与既有持久化逻辑（仅追加一个字段）
- **依赖**：无
- **涉及文件**：`app/src/main/java/com/mangareader/data/ReaderConfig.kt`
- **验证方法**：编译通过；新字段默认 false；进程重建后恢复用户设置值；`git diff` 仅含新增字段与持久化行，无既有字段改动
- **预估代码行数**：5 行变更

### 2.2 ReaderSettingsSheet 新增"仿真翻页效果"开关项
- [ ] 在 `app/src/main/java/com/mangareader/ui/ReaderSettingsSheet.kt` 新增开关项，文案为"仿真翻页效果"，绑定 `ReaderConfig.isPageCurlAnimation`
- [ ] 复用既有开关项样式（与"翻页动画"开关项视觉一致，不引入新样式），开关切换时持久化并通知配置变更
- [ ] 开关项位置紧邻既有"翻页动画"开关项之后，便于用户理解两者关系
- **依赖**：2.1
- **涉及文件**：`app/src/main/java/com/mangareader/ui/ReaderSettingsSheet.kt`
- **验证方法**：打开阅读器设置面板可见"仿真翻页效果"开关项；切换开关后持久化生效；进程重建后开关状态恢复；视觉与既有开关项一致
- **预估代码行数**：15 行变更

---

## 3. 启用判定模块

### 3.1 实现 CurlEnabledPredicate 纯函数
- [ ] 在 `app/src/main/java/com/mangareader/native/curl/CurlEnabledPredicate.kt` 新增顶层函数 `isCurlEnabled(flipAnimationEnabled: Boolean, pageCurlAnimationEnabled: Boolean, isVertical: Boolean, verticalPager: Boolean, doublePageMode: Boolean, tileModeEnabled: Boolean): Boolean`，返回 `flipAnimationEnabled && pageCurlAnimationEnabled && !isVertical && !verticalPager && !doublePageMode && !tileModeEnabled`
- **依赖**：1.2
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlEnabledPredicate.kt`
- **验证方法**：单元测试覆盖 6 个条件全真→true、任一不满足→false 共 7 种组合（含 `pageCurlAnimationEnabled=false` 单独为 false）
- **预估代码行数**：15 行

---

## 4. 位图缓存模块

### 4.1 实现 CurlBitmapCache
- [ ] 在 `app/src/main/java/com/mangareader/native/curl/CurlBitmapCache.kt` 新增 `class CurlBitmapCache(maxSize: Int = 12)`，内部持有 `LruCache<Int, Bitmap>(maxSize)`（`sizeOf` 恒 1，与 `PagerReaderView` 对齐）与 `protectedSet: MutableSet<Int>`
- [ ] 实现 `get(idx: Int): Bitmap?`、`put(idx: Int, bmp: Bitmap)`、`remove(idx: Int)`：`put` 时若 `idx` 在 `protectedSet` 中，先 `lru.remove(idx)` 再 put，防 LRU 误淘汰受保护项
- [ ] 实现 `protectThreeLayers(center: Int)`：`protectedSet = setOf(center - 1, center, center + 1)`
- [ ] 实现 `drainAll(): Map<Int, Bitmap>`：取 `lru.snapshot()`，逐项 `lru.remove`，返回未回收位图 Map（归调用方所有，不 recycle）
- [ ] 实现 `adopt(map: Map<Int, Bitmap>)`：过滤 `!bmp.isRecycled` 后 `lru.put`
- [ ] 实现 `trimNonProtected(level: Int)`：遍历 `lru.snapshot()`，移除索引不在 `protectedSet` 的项；`level` 较高时全清非保护项
- **依赖**：1.2
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlBitmapCache.kt`
- **验证方法**：单元测试覆盖——LRU 淘汰非保护项；`protectThreeLayers` 后三层不被淘汰；`drainAll` 后缓存清空且位图未 recycle；`adopt` 跳过 recycled 位图
- **预估代码行数**：80 行

---

## 5. 页面加载模块

### 5.1 实现 CurlPageLoader 基础结构
- [ ] 在 `app/src/main/java/com/mangareader/native/curl/CurlPageLoader.kt` 新增 `class CurlPageLoader(context: Context, cache: CurlBitmapCache, scope: CoroutineScope, remoteSupply: RemotePageSupply?)`，持有 `inflightLoads: MutableMap<Int, Long>`（5s 去重窗口）、`preloadSemaphore: Semaphore`（预载限流，与 `PagerReaderView` 对齐）、`engine: ArchiveEngine?`、`treePath: String`
- [ ] 实现 `bind(engine: ArchiveEngine, treePath: String)` 设置加载源
- [ ] 实现 `cancelAll()`：`scope.cancel()` + `inflightLoads.clear()`
- **依赖**：4.1
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlPageLoader.kt`
- **验证方法**：编译通过；`cancelAll` 后 `inflightLoads` 为空
- **预估代码行数**：30 行

### 5.2 实现 loadPageAsync 加载逻辑
- [ ] 实现 `loadPageAsync(idx: Int, isPreload: Boolean = false)`：`cache.get(idx)` 命中→超分 `attachOrScheduleFast`→返回；`inflightLoads` 5s 内同索引去重；本地走 `engine.loadPage(meta, treePath)`；远程走 `supply.loadPageRaw(idx)` + Coil3 解码；仅当前页请求切远端焦点（`remoteSupply.onFocus`），预载不抢焦点
- [ ] 解码后应用白边裁剪（复用 `PagerReaderView` 既有 `trimWhiteBorder` 逻辑）与超分注入（`attachOrSchedule`），再 `cache.put(idx, bmp)`
- [ ] 失败分支：`cache.remove(idx)` + `inflightLoads.remove(idx)`，不抛异常（占位由渲染层处理）
- [ ] 剔除动图（`makeAnimDrawable`）、tile（`obtainTileFile`）、双页、`notifyItemChanged` 分支（仿真翻页无 RecyclerView Adapter）
- **依赖**：5.1
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlPageLoader.kt`
- **验证方法**：本地漫画加载后 `cache.get(idx)` 非空；同索引 5s 内重复调用仅触发一次解码；解码失败不崩溃
- **预估代码行数**：120 行

### 5.3 实现 preloadAround 预加载
- [ ] 实现 `preloadAround(centerIdx: Int, totalPages: Int)`：先 `loadPageAsync(centerIdx)`（非 isPreload），再 `loadPageAsync(centerIdx + 1, isPreload = true)` 与 `loadPageAsync(centerIdx - 1, isPreload = true)`，边界索引跳过
- [ ] 调用 `cache.protectThreeLayers(centerIdx)` 保护三层
- **依赖**：5.2
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlPageLoader.kt`
- **验证方法**：调用后 `cache` 含 center 及可达的 ±1 索引；边界（首页/末页）不越界
- **预估代码行数**：20 行

---

## 6. 仿真翻页 Composable 层

### 6.1 实现 CurlPageContent 单页 Composable
- [ ] 在 `app/src/main/java/com/mangareader/native/curl/CurlReaderContent.kt` 新增 `@Composable fun CurlPageContent(idx: Int, bmp: Bitmap?, pageBgColor: Int, landscapeZoom: Boolean, fitScreen: Boolean, onError: (Int) -> Unit)`：`AndroidView(factory = { PagerSSIV(it) })`
- [ ] `update` 块：位图非空且 `!isRecycled` → `ssiv.setImage(ImageSource.bitmap(bmp))`；位图空 → 占位背景色；加载失败标记 → 错误占位
- [ ] 应用渲染配置：`pageBgColor`/`landscapeZoom`/`fitScreen`（白边裁剪在加载阶段已完成，此处不再重复）
- [ ] 不主动通知缩放态变更——`PagerSSIV` 未缩放时 `ACTION_MOVE` return false 自动让出手势给父级 PageCurl（见 design 2.6）
- **依赖**：1.2
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlReaderContent.kt`、`app/src/main/java/com/mangareader/native/PagerSSIV.kt`（仅引用，不修改）
- **验证方法**：Composable 预览能渲染位图；位图为空时显示占位背景色；不修改 `PagerSSIV.kt`
- **预估代码行数**：60 行

### 6.2 实现 CurlReaderContent PageCurl 包装
- [ ] 在同文件新增 `@Composable fun CurlReaderContent(totalPages: Int, state: PageCurlState, isRtl: Boolean, pageBgColor: Int, cache: CurlBitmapCache, landscapeZoom: Boolean, fitScreen: Boolean, onPageChanged: (Int) -> Unit)`
- [ ] 构造 `PageCurlConfig`：`backPageColor = pageBgColor`；`shadowColor = 0x66000000`；`dragInteraction` 按 `isRtl` 构造 `StartEndDragInteraction`——FLIP：forward(start=右半屏, end=左半屏)、backward(start=左半屏, end=右半屏)；RTL：交换 forward 与 backward
- [ ] 调用 `PageCurl(count = totalPages, state = state, config = config) { idx -> CurlPageContent(idx, cache.get(idx), pageBgColor, landscapeZoom, fitScreen, onError) }`
- [ ] 用 `LaunchedEffect` 观察 `state.current` 变更，过滤同页，回调 `onPageChanged`
- [ ] 方向缺失/非法默认按 FLIP
- **依赖**：6.1
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlReaderContent.kt`
- **验证方法**：Composable 渲染 PageCurl；FLIP 时从右向左拖拽前进；RTL 时从左向右拖拽前进；`state.current` 变更触发 `onPageChanged`
- **预估代码行数**：100 行

---

## 7. 仿真翻页视图门面

### 7.1 实现 CurlReaderView 基础结构
- [ ] 在 `app/src/main/java/com/mangareader/native/curl/CurlReaderView.kt` 新增 `class CurlReaderView(context: Context) : FrameLayout(context), ReaderViewDelegate`，持有 `pageCurlState: PageCurlState`、`cache: CurlBitmapCache`、`loader: CurlPageLoader`、`composeView: ComposeView`、`currentPage: Int`、`isRtl: Boolean` 及渲染配置项（`pageBgColor`/`trimWhiteBorder`/`trimThreshold`/`trimWhiteRatio`/`landscapeZoom`/`fitScreen`/`panNavigation`）
- [ ] 内部 `scope: CoroutineScope` 用于加载器
- **依赖**：4.1、5.3、6.2
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlReaderView.kt`、`app/src/main/java/com/mangareader/native/NativeReaderView.kt`（引用 `ReaderViewDelegate`，不修改）
- **验证方法**：编译通过；能被 `remember { }` 创建
- **预估代码行数**：40 行

### 7.2 实现 setup 与 goToPage
- [ ] 实现 `setup(engine, treePath, pages, startPage, vertical, clickFlip, rtl, verticalPager)`：初始化 `CurlBitmapCache`/`CurlPageLoader`；`loader.bind(engine, treePath)`；构造 `PageCurlState`（`rememberSaveable` 初值 `startPage`，进程重建恢复）；`isRtl = rtl`；`composeView.setContent { CurlReaderContent(...) }`；`loader.preloadAround(startPage, pages)`
- [ ] 实现 `goToPage(page)`：`pageCurlState.snapTo(page)`；`currentPage = page`；`loader.preloadAround(page, total)`
- [ ] `onPageChanged` 回调内部：更新 `currentPage` → `cache.protectThreeLayers(newPage)` → `loader.preloadAround(newPage, total)` → 外部 `onPageChanged?.invoke(newPage)`
- **依赖**：7.1
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlReaderView.kt`
- **验证方法**：`setup` 后当前页开始加载并渲染；`goToPage` 跳转后 `currentPage` 与 `pageCurlState.current` 一致
- **预估代码行数**：80 行

### 7.3 实现 release/trimMemory/isCurrentPageReady
- [ ] 实现 `release()`：`loader.cancelAll()`；`composeView` dispose；不 recycle 位图（交由 drain 或 GC）
- [ ] 实现 `trimMemory(level)`：`cache.trimNonProtected(level)`
- [ ] 实现 `isCurrentPageReady()`：查当前页 `PagerSSIV.hasImage()`（通过持有当前页 SSIV 引用或经 Compose 状态查询）
- **依赖**：7.2
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlReaderView.kt`
- **验证方法**：`release` 后加载停止、ComposeView dispose；`trimMemory` 仅移除非保护项；`isCurrentPageReady` 在位图就绪后返回 true
- **预估代码行数**：30 行

### 7.4 实现 drainLiveBitmaps/adoptBitmaps 交接
- [ ] 实现 `drainLiveBitmaps(): Map<Int, Bitmap>`：委托 `cache.drainAll()`，release 不回收已移交位图
- [ ] 实现 `adoptBitmaps(map: Map<Int, Bitmap>)`：委托 `cache.adopt(map)`，首帧命中缓存实现零重解码
- **依赖**：7.3
- **涉及文件**：`app/src/main/java/com/mangareader/native/curl/CurlReaderView.kt`
- **验证方法**：`drainLiveBitmaps` 后 cache 为空且位图未 recycle；`adoptBitmaps` 后 `cache.get` 命中移交位图
- **预估代码行数**：15 行

---

## 8. 阅读器外壳集成

### 8.1 NativeReaderScreen 新增参数与三分支判定
- [ ] 在 `app/src/main/java/com/mangareader/native/NativeReaderScreen.kt` 新增参数 `flipAnimationEnabled: Boolean = true` 与 `pageCurlAnimationEnabled: Boolean = false`（默认值保证向后兼容）
- [ ] `activeDelegate` 由二分支（条漫/回退）扩为三分支（条漫/仿真/回退）：调用 `isCurlEnabled(flipAnimationEnabled, pageCurlAnimationEnabled, isVertical, verticalPager, doublePageMode, tileModeEnabled)` 判定，true 时选择 `CurlReaderView`，false 时保持既有逻辑（由 `flipAnimationEnabled` 决定3D旋转或无动画，既有 `PagerReaderView` 行为不变）
- [ ] `AndroidView` factory 增加 `curlReaderView` 子视图加入 `FrameLayout`；`syncVis` 扩为三态（条漫/仿真/回退）切 VISIBLE/GONE
- [ ] `CurlReaderView` 实例化用 try-catch 包裹，构造/setup 抛异常时标记不可用并回退 `PagerReaderView`（初始化失败回退）
- **依赖**：3.1、7.4
- **涉及文件**：`app/src/main/java/com/mangareader/native/NativeReaderScreen.kt`
- **验证方法**：`pageCurlAnimationEnabled = false`（默认）时行为与改动前完全一致（零影响，仍为3D旋转或无动画）；两开关均开且条件满足时选择 `CurlReaderView`；构造异常时回退不崩溃
- **预估代码行数**：55 行变更

### 8.2 DisposableEffect key 增补与回退切换
- [ ] `DisposableEffect` key 增补 `flipAnimationEnabled`/`pageCurlAnimationEnabled`/`doublePageMode`/`tileModeEnabled`，任一变更触发 dispose → 重建
- [ ] dispose 时旧 delegate（`CurlReaderView` 或 `PagerReaderView`）`drainLiveBitmaps` → `SrBitmapHandoffBus.put(BitmapHandoff(..., treePath))`
- [ ] 重建时 `isCurlEnabled` 重新评估选择新 delegate；新 delegate `setup` 后 `SrBitmapHandoffBus.take()?.let { delegate.adoptBitmaps(it.bitmaps) }`，`treePath` 不符则回收防错配
- [ ] 当前页索引与方向经 `savedPage`/`isRtl` 保持（既有机制，不改动）
- **依赖**：8.1
- **涉及文件**：`app/src/main/java/com/mangareader/native/NativeReaderScreen.kt`
- **验证方法**：运行期开启/关闭仿真翻页开关、开启双页/tile/垂直模式即时切换视图，无闪屏，当前页与方向保持；模式切换首帧命中缓存（零重解码）
- **预估代码行数**：30 行变更

### 8.3 Reader.kt 传入 flipAnimationEnabled 与 pageCurlAnimationEnabled
- [ ] 在 `app/src/main/java/com/mangareader/ui/Reader.kt` 的 `NativeReaderScreen(...)` 调用处新增参数 `flipAnimationEnabled = config.isFlipAnimation` 与 `pageCurlAnimationEnabled = config.isPageCurlAnimation`
- **依赖**：8.1、2.1
- **涉及文件**：`app/src/main/java/com/mangareader/ui/Reader.kt`
- **验证方法**：编译通过；`config.isFlipAnimation`/`config.isPageCurlAnimation` 变更能透传至 `NativeReaderScreen`
- **预估代码行数**：6 行变更

---

## 9. 编译与构建验证

### 9.1 全量编译通过
- [ ] 执行 `./gradlew :app:assembleRelease` 全量编译，确认无编译错误、无未解析引用、无 lint 致命错误
- [ ] 确认 `PagerReaderView.kt`/`PagerSSIV.kt`/`WebtoonReaderView.kt` 未被修改（非侵入式校验）；`ReaderConfig.kt`/`ReaderSettingsSheet.kt` 仅含新增字段/开关项，无既有逻辑改动
- **依赖**：8.2、8.3
- **涉及文件**：无（验证步骤）
- **验证方法**：构建成功退出码 0；`git diff --name-only` 不含 `PagerReaderView.kt`/`PagerSSIV.kt`/`WebtoonReaderView.kt`
- **预估代码行数**：0

### 9.2 复制 APK 到 X 盘
- [ ] 构建完成后将 APK 从 `app/build/outputs/apk/release/` 复制到 `X:\` 盘（与项目既有部署流程一致）
- **依赖**：9.1
- **涉及文件**：无（部署步骤）
- **验证方法**：`X:\` 盘存在最新 APK 文件
- **预估代码行数**：0

---

## 10. 端到端功能验证

### 10.1 启用场景验证
- [ ] 在横向单页、非 tile、非双页模式下同时开启翻页动画开关与仿真翻页开关，打开漫画，拖拽翻页：验证可见页面沿拖拽方向卷曲、背面呈镜像、卷曲处有阴影；翻页动画维持 ≥30fps；首帧 ≤500ms
- **依赖**：9.2
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-启用；肉眼观察卷曲/镜像/阴影；帧率与首帧延迟达标
- **预估代码行数**：0

### 10.2 开关并存与默认验证
- [ ] 翻页动画开关开 + 仿真翻页开关关（默认）：验证使用既有3D旋转翻页效果（`FlipPageTransformer`），行为与改动前完全一致
- [ ] 翻页动画开关关 + 仿真翻页开关任意：验证无翻页动画（仿真翻页开关不产生效果）
- [ ] 翻页动画开关开 + 仿真翻页开关开：验证使用仿真翻页
- [ ] 运行期切换仿真翻页开关（开↔关）：验证即时在仿真翻页与3D旋转间切换，当前页与方向保持，无闪屏
- **依赖**：10.1
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-开关并存、场景-开关默认、场景-开关即时生效；默认状态为3D旋转
- **预估代码行数**：0

### 10.3 开关持久化验证
- [ ] 开启仿真翻页开关后杀死进程并重建：验证开关状态恢复为开启，仿真翻页模式恢复
- [ ] 关闭仿真翻页开关后杀死进程并重建：验证开关状态恢复为关闭，使用3D旋转
- **依赖**：10.1
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-开关持久化、5.7.1 第3条
- **预估代码行数**：0

### 10.4 回退场景验证
- [ ] 仿真翻页模式下开启双页模式：验证即时回退至原有翻页视图（3D旋转或无动画，由翻页动画开关决定），当前页与方向保持，无闪屏
- [ ] 关闭仿真翻页开关：验证回退至3D旋转翻页，行为与改动前完全一致
- [ ] 关闭翻页动画开关：验证无翻页动画
- [ ] 切换至垂直/垂直分页/tile 模式：验证回退
- [ ] 模拟 `CurlReaderView` 构造异常：验证无缝回退，无崩溃
- **依赖**：10.1
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-回退-双页、场景-初始化失败回退
- **预估代码行数**：0

### 10.5 RTL 方向验证
- [ ] 阅读方向设为 RTL：从左向右拖拽验证前进一页（进度递增）；从右向左拖拽验证后退一页
- [ ] 阅读方向设为 FLIP：从右向左拖拽验证前进一页
- [ ] 运行期切换 FLIP↔RTL：验证即时更新拖拽区映射，无需重启
- **依赖**：10.1
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-RTL；前进方向与阅读顺序一致
- **预估代码行数**：0

### 10.6 缩放抑制翻页验证
- [ ] 当前页已放大（双击或捏合）：单指拖拽验证页面平移、索引不变、不翻页
- [ ] 缩放比例回到 1.0（双击复位）：拖拽验证重新触发卷曲翻页
- [ ] 多指落下：验证进入缩放不翻页
- **依赖**：10.1
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-缩放抑制翻页、场景-缩放复位
- **预估代码行数**：0

### 10.7 邻接预加载与三层缓存验证
- [ ] 当前页就绪后：验证前后页异步进入缓存，翻页动画期间不出现空白
- [ ] 翻页动画进行中：验证当前页 ±1 三层位图均可用、不被淘汰
- [ ] 同索引重复请求：验证仅发起一次解码（5s 去重窗口）
- **依赖**：10.1
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-邻接预加载；可结合日志观察加载与缓存命中
- **预估代码行数**：0

### 10.8 越界与异常验证
- [ ] 首页向后翻：验证不产生翻页动画，页面保持原位
- [ ] 末页向前翻：验证不产生翻页动画，页面保持原位
- [ ] 解码失败页：验证显示错误占位，可继续翻页，不影响其他页
- **依赖**：10.1
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-越界、5.5.3 解码失败
- **预估代码行数**：0

### 10.9 进程重建验证
- [ ] 仿真翻页模式下翻至第 N 页，杀死进程并重建：验证当前页索引恢复为 N、阅读方向恢复、仿真翻页模式恢复
- **依赖**：10.1
- **涉及文件**：无（运行时验证）
- **验证方法**：对照 spec 场景-进程重建
- **预估代码行数**：0

---

## 11. 自我审查

### 11.1 第一轮自我审查
- [ ] 全量审查新增的 5 个 curl 子包文件与 5 个修改文件，重点核查：非侵入式（`PagerReaderView`/`PagerSSIV`/`WebtoonReaderView` 未改）；`ReaderConfig`/`ReaderSettingsSheet` 仅新增字段/开关项无既有逻辑改动；代码无注释；命名遵循 camelCase；接口契约与 design.md 一致；回退路径完备；手势冲突解决方案未引入新路由代码
- [ ] 审查 `NativeReaderScreen` 三分支判定与 `DisposableEffect` key 完整性（含 `flipAnimationEnabled`/`pageCurlAnimationEnabled`），确认任一启用条件变更触发重建且无中间态残留
- [ ] 审查 `CurlEnabledPredicate` 判定逻辑含 `pageCurlAnimationEnabled`，默认 false 时回退至3D旋转
- [ ] 审查 `SrBitmapHandoffBus` 交接路径覆盖 `CurlReaderView`，`drainLiveBitmaps` 不 recycle 已移交位图
- **依赖**：10.9
- **涉及文件**：全部新增与修改文件
- **验证方法**：审查清单逐项确认通过；发现问题回到对应任务修正
- **预估代码行数**：0

### 11.2 第二轮自我审查
- [ ] 复审第一轮发现问题的修正点，确认已闭环
- [ ] 全量回归验证：重复 10.1–10.9 关键场景，确认修正未引入新问题
- [ ] 确认 `git diff --stat` 变更范围与 design.md 文件变更清单一致（5 新增 + 5 修改，无多余改动）
- [ ] 确认 commit message 为中文
- **依赖**：11.1
- **涉及文件**：全部新增与修改文件
- **验证方法**：第二轮审查清单逐项确认通过；变更范围与设计一致
- **预估代码行数**：0

---

## 任务依赖关系总览

```
1.1, 1.2（无依赖）
2.1（无依赖）
2.2 ← 2.1
3.1 ← 1.2
4.1 ← 1.2
5.1 ← 4.1
5.2 ← 5.1
5.3 ← 5.2
6.1 ← 1.2
6.2 ← 6.1
7.1 ← 4.1, 5.3, 6.2
7.2 ← 7.1
7.3 ← 7.2
7.4 ← 7.3
8.1 ← 3.1, 7.4
8.2 ← 8.1
8.3 ← 8.1, 2.1
9.1 ← 8.2, 8.3
9.2 ← 9.1
10.1 ← 9.2
10.2 ← 10.1
10.3 ← 10.1
10.4 ← 10.1
10.5 ← 10.1
10.6 ← 10.1
10.7 ← 10.1
10.8 ← 10.1
10.9 ← 10.1
11.1 ← 10.9
11.2 ← 11.1
```

## 关键验证检查点（里程碑）

| 检查点 | 对应任务 | 里程碑标志 |
|--------|---------|-----------|
| 依赖可解析 | 1.1 | `./gradlew :app:dependencies` 含 pagecurl:1.5.1 |
| 配置字段就绪 | 2.1 | `isPageCurlAnimation` 默认 false，进程重建恢复 |
| 设置开关项就绪 | 2.2 | 设置面板可见"仿真翻页效果"开关，视觉与既有一致 |
| 启用判定纯函数就绪 | 3.1 | 单元测试 7 种组合通过（含 pageCurlAnimationEnabled） |
| 位图缓存三层保护就绪 | 4.1 | 单元测试 LRU 淘汰与三层保护通过 |
| 页面加载器就绪 | 5.3 | 本地漫画加载、去重、预载通过 |
| Composable 渲染就绪 | 6.2 | PageCurl 渲染当前页、FLIP/RTL 拖拽方向正确 |
| 视图门面就绪 | 7.4 | `ReaderViewDelegate` 全方法实现，drain/adopt 交接通过 |
| 编译通过 | 9.1 | `assembleRelease` 成功，非侵入式校验通过 |
| 基本渲染 | 10.1 | 卷曲/镜像/阴影可见，帧率与首帧达标 |
| 开关并存 | 10.2 | 默认3D旋转，两开关均开才仿真翻页，运行期切换即时生效 |
| 开关持久化 | 10.3 | 进程重建后开关状态恢复 |
| 手势交互 | 10.6 | 缩放抑制翻页、缩放复位翻页恢复 |
| RTL 方向 | 10.5 | RTL 从左向右拖拽前进，FLIP 从右向左拖拽前进 |
| 回退 | 10.4 | 双页/tile/垂直/开关关/构造异常均回退，无闪屏 |
| 邻接预加载 | 10.7 | 翻页无空白层，三层常驻 |
| 越界 | 10.8 | 首末页不越界动画 |
| 进程重建 | 10.9 | 索引与方向恢复 |
| 自我审查 | 11.2 | 两轮审查通过，变更范围与设计一致 |

## 预估代码行数汇总

| 文件 | 类型 | 预估行数 |
|------|------|---------|
| `app/build.gradle.kts` | 修改 | 1 |
| `curl/CurlEnabledPredicate.kt` | 新增 | 15 |
| `curl/CurlBitmapCache.kt` | 新增 | 80 |
| `curl/CurlPageLoader.kt` | 新增 | 170 |
| `curl/CurlReaderContent.kt` | 新增 | 160 |
| `curl/CurlReaderView.kt` | 新增 | 165 |
| `NativeReaderScreen.kt` | 修改 | 85 |
| `Reader.kt` | 修改 | 6 |
| `ReaderConfig.kt` | 修改 | 5 |
| `ReaderSettingsSheet.kt` | 修改 | 15 |
| **合计** | — | **约 702 行** |
