# 实施任务：合并/增强按钮重组与合并功能（REQ-005）

- **需求ID**: REQ-005
- **优先级**: P1（高）
- **基线 commit**: eb2ac412

## 1. 数据模型扩展

### 1.1 Book 实体加合并关系字段
- [ ] 修改 `com/mangareader/data/Database.kt:29-56`，Book 实体加 `mergedParentId: Long? = null` 字段（null=独立书/虚拟书，非null=某虚拟书的成员）
- [ ] 新增 Room migration ALTER TABLE 加列 `mergedParentId`，加列有默认 null 兼容旧数据，严格测试 migration 版本号/迁移路径

### 1.2 合并/解散 DAO
- [ ] 在 `Database.kt` MangaDao 新增 `suspend fun mergeBooks(memberIds: List<Long>, newName: String): Long`（返回虚拟书 id）：展开虚拟书成员→创建虚拟书行（source="merged"）→成员 mergedParentId 指向虚拟书→封面取首成员 displayCoverPath→旧虚拟书删除
- [ ] 新增 `suspend fun dissolveMerge(virtualBookId: Long)`：成员 mergedParentId 置 null→删除虚拟书行
- [ ] 新增 `suspend fun getMergeMembers(parentId: Long): List<Book>`：查 mergedParentId == parentId 的成员，按原 addedAt 排序
- [ ] 改 `getAllBooks` 等书架主列表查询加 `WHERE mergedParentId IS NULL`，成员不显示在主列表，虚拟书显示；grep 全部书架查询变体统一加过滤

## 2. 上方按钮重组

### 2.1 增强菜单移至上方 TopAppBar
- [ ] 修改 `com/mangareader/ui/Library.kt:1364-1383`，TopAppBar actions 保留超分（AutoFixHigh）+全选（SelectAll），新增"增强▾"DropdownMenu（迁移自底部 line 1467-1511 的三项菜单：批量增强/整本增强/清除产物）

## 3. 底部合并/解散按钮

### 3.1 实现按钮状态计算
- [ ] 在 `Library.kt` 新增纯函数 `fun computeMergeButtonState(selected: List<Book>): MergeButtonState`，按选择集合计算：空→不显示；仅1本普通书→"合并"置灰；≥2本普通书→"合并"可点；仅1本虚拟书→"解散"可点；含A+其他（混合）→"合并"可点；仅多本虚拟书→"合并"可点

### 3.2 底部按钮替换
- [ ] 修改 `com/mangareader/ui/Library.kt:1462-1515`，底部原"增强▾"位置改为"合并/解散"按钮，按 `computeMergeButtonState(selectedBooks)` 计算显隐/置灰/文案

### 3.3 合并弹窗
- [ ] 在 `Library.kt` 或新文件新增 `MergeBooksDialog` Compose 弹窗（输入新名，默认名取首成员 title 或"合并合集"，确认/取消），命名区分于既有 MergeTargetPickerDialog

## 4. 合并/解散执行

### 4.1 合并操作
- [ ] 点"合并"→弹 `MergeBooksDialog`→确认→`mergeBooks(memberIds, newName)`→书架刷新（只显示虚拟书不显示成员）
- [ ] 取消→不执行合并，选择集合与书架状态不变
- [ ] 混合合并（A+B）：mergeBooks 展开 A 的成员 + B，全部挂新虚拟书 A'，旧 A 删除

### 4.2 解散操作
- [ ] 点"解散"→`dissolveMerge(A.id)`→成员回书架主列表→成员的进度/书签/标签不动（按各自 bookId 保留）

## 5. 成员视图

### 5.1 点击虚拟书进成员列表
- [ ] 修改 `com/mangareader/ui/Library.kt` 书卡点击逻辑，点击虚拟书 A（source=="merged"）→进成员列表（LazyColumn/LazyGrid 复用 BookCard/BookListRow 样式，数据源 `getMergeMembers(A.id)`），非直接打开阅读
- [ ] 点成员进阅读，返回回书架

## 6. 验证与测试

### 6.1 功能验证
- [ ] 验证多选模式上方有"超分"与"增强"，增强点击出选项菜单（与原底部增强选项一致）
- [ ] 验证底部原增强位置为"合并"（或选中已合并书时为"解散"）
- [ ] 验证选中≥2本普通书可合并，弹窗改名，书架显示单本 A，点 A 见成员
- [ ] 验证封面取自成员正常逻辑（displayCoverPath）
- [ ] 验证仅选1本时合并置灰，至少两本以上才能合并
- [ ] 验证仅选已合并 A 时按钮为"解散"，点击后 A 消失、成员回书架
- [ ] 验证选 A + B 时仍为"合并"，点击后执行合并

### 6.2 回归验证
- [ ] 验证合并/解散不丢成员的进度/书签/标签（按各自 bookId 保留）
- [ ] 验证合并弹窗提供默认书名，可修改后确认或取消
- [ ] 验证 Room migration 成功，旧数据升级不崩溃
- [ ] 验证搜索/过滤/标签页对成员书的处理（加 mergedParentId IS NULL 过滤或保留成员可搜）

## 7. 审查与确认

### 7.1 代码审查
- [ ] 确认所有书架查询均加 mergedParentId IS NULL 过滤，无成员泄漏到主列表
- [ ] 确认点击虚拟书 A 先判 source=="merged"→进成员视图，else 进阅读（避免无物理文件误打开）
- [ ] 确认删除虚拟书时级联处理成员（mergedParentId=null 或一并删，无孤儿）
- [ ] 确认 MergeBooksDialog 与既有 MergeTargetPickerDialog 命名区分不混淆