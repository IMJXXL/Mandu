# 实施任务：点击漫画画面漂移修复（REQ-006）

- **需求ID**: REQ-006
- **优先级**: P1（高）
- **基线 commit**: eb2ac412

## 1. 根因确认

### 1.1 确认漂移源
- [ ] 在阅读中点击画面观察漂移方向/幅度，确认与 chrome 显隐同步
- [ ] 确认漂移来自 Compose 层 Scaffold 槽位（ReaderTopBar/ReaderBottomBar），非系统栏（系统栏用 LAYOUT_STABLE|LAYOUT_FULLSCREEN 已不漂移）
- [ ] 确认横屏 `LandscapeReaderChrome` 是否已是 overlay 胶囊式（`LandscapeReaderChrome.kt:116` 用 align 叠加，疑似已是 overlay），若横屏不漂移则不动

## 2. chrome 移出 Scaffold 槽位

### 2.1 清空 Scaffold topBar/bottomBar 槽位
- [ ] 修改 `com/mangareader/ui/Reader.kt:2581-2698`，将 `ReaderTopBar`（含 AnimatedBarVisibility 包裹）从 Scaffold `topBar = {}` 移出
- [ ] 将 `ReaderBottomBar`（含 AnimatedBarVisibility 包裹）从 Scaffold `bottomBar = {}` 移出
- [ ] Scaffold 的 topBar/bottomBar 设为空（或仅保留系统状态栏 inset 处理，不占可见高度）

## 3. 内容区改 Box 叠加

### 3.1 chrome 作为 overlay 叠加在内容区
- [ ] 修改 `com/mangareader/ui/Reader.kt:2808` 附近，Scaffold content 区改为 `Box(Modifier.fillMaxSize())`
- [ ] 内层顺序：① `NativeReaderScreen(Modifier.fillMaxSize())`（画面，固定全屏）；② `ReaderTopBar` overlay `Modifier.align(Alignment.TopStart)`；③ `ReaderBottomBar` overlay `Modifier.align(Alignment.BottomStart)`，后绘制者浮于上层
- [ ] AnimatedBarVisibility 保留包裹 chrome overlay 控制显隐动画（alpha/位移），因不再在 Scaffold 槽位内，高度塌缩不影响 content padding → 画面不漂移

### 3.2 横屏处理
- [ ] 若横屏也走 Scaffold 槽位则同改；若横屏已是 overlay 胶囊式则不动

## 4. 验证与测试

### 4.1 画面稳定性验证
- [ ] 验证点击画面弹出上下窗口时，画面无可见漂移/跳动
- [ ] 验证再次点击收起窗口时，画面同样不漂移
- [ ] 验证连续多次显隐切换，画面始终稳定
- [ ] 验证缩放/平移状态下显隐 chrome，锚点不变

### 4.2 回归验证
- [ ] 验证 chrome overlay 动画行为（滑入滑出）与改动前一致
- [ ] 验证 chrome 点击区与画面点击区不冲突（chrome 仅占顶部/底部条带高度，中间区域点击穿透到 NativeReaderScreen）
- [ ] 验证沉浸阅读时画面全屏无遮挡（chrome 本就临时显示 10s 自动隐藏）
- [ ] grep Scaffold padding 消费点，确认无其他依赖 padding 的逻辑被破坏

## 5. 审查与确认

### 5.1 代码审查
- [ ] 确认内容区固定全屏后，缩放/平移基准不再因 padding 变化重定位
- [ ] 确认系统栏显隐由 hideSystemBars/showSystemBars（Window 级）控制，不依赖 Scaffold 槽位
- [ ] 确认 overlay 用精确高度非全屏，不遮挡中间画面点击区