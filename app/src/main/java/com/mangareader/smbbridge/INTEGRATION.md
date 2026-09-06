# SMB + WebDAV 集成架构（INTEGRATION）

> smb_embed_lv119 任务 9.4 / 19.4。本文记录 SMB/WebDAV 嵌入漫读的完整架构、路径格式、
> 配置项、升级路径。对参考项目代码的修改点见同目录 `MODIFICATIONS.md` 与
> `../webdavbridge/MODIFICATIONS.md`。

## 一、总体架构

```
书架/阅读 UI (漫读 Compose + 原生视图)
  │  filePath 前缀分流（Reader.enterReader / CoverFetcher.fetch）
  ├─ smb: / cv:smb:   → com.mangareader.smbbridge（SMB 胶水层，API≥31 守卫）
  ├─ webdav: / cv:webdav: → com.mangareader.webdavbridge（WebDAV 胶水层，无 API 守卫）
  └─ a:/d:/z:/content:// → ArchiveEngine（本地，100% 未动，入口有 require 守卫）
                │
                ▼
  参考项目基础设施（com.hippo.ehviewer，零 import 修改拷入）
  ├─ smb/    SmbGateway（smbj 连接池）+ SmbRepository/SmbPasswordStore/SmbCache
  ├─ webdav/ WebDavGateway/WebDavClient（ktor CIO）+ WebDavRepository/WebDavCache
  ├─ library/ ArchiveByteSource/StreamArchivePageCache/NetworkFolderIndexCache/MediaTypes…
  ├─ gallery/ PageLoader 家族（Stream/TarChunk/SolidExtract/DocumentExtract/Folder）
  └─ image/   Image 解码 / hdr / coil 扩展
```

- **一个 APK**：SMB + WebDAV + 本地共存，未拆分。
- **本地 100% 不动**：所有远程改造都在 `smb:`/`webdav:`/`cv:smb:`/`cv:webdav:` 守卫内；
  ArchiveEngine 六个公开方法入口有 `requireNotRemote` 守卫兜底（远程路径误入即抛）。
- **页面供给抽象**（`com.mangareader.native.RemotePageSupply`）：
  - PagerReaderView 在 `loadPageRaw` 处分流：`remotePageSupply != null` 时取远程原始字节，
    之后仍走自身 Coil3 解码 → 动图识别 → 裁边 → 超分 → SSIV 全管线（与本地一致）。
  - WebtoonReaderView 在 `loadPage` 处分流：`loadBitmap` 直接供给位图。
  - 阅读视图对 `remotePageSupply == null` 的行为与历史版本逐字节一致。

## 二、路径格式（spec 6.2）

| 场景 | 格式 |
|------|------|
| SMB 书籍 filePath | `smb:{serverId}:{share}/{relativePath}`（可含 `a.zip:inner/1.png` 成员后缀） |
| SMB 封面 coverPath | `cv:smb:{serverId}:{share}/{relativePath}` |
| WebDAV 书籍 filePath | `webdav:{serverId}:{relativePath}`（无 share 段） |
| WebDAV 封面 coverPath | `cv:webdav:{serverId}:{relativePath}` |

- `serverId` = SMB_SOURCES / WEBDAV_SOURCES 表主键（漫读 MangaDatabase v13）。
- 密码不接入 Room：SmbPasswordStore / WebDavPasswordStore（AndroidKeyStore AES-GCM）。
- 书籍行：`Book(source="smb"|"webdav", fileType=扩展名, coverPath=cv:...)`；
  阅读进度复用漫读 `reading_progress` 表（bookId+filePath 维度，与本地一致）。

## 三、阅读会话链路（任务4/14）

1. `Reader.enterReader(path)` 检测 `smb:`/`webdav:` → `enterRemoteReader`。
2. `SmbBookRouter.openSmbBook` / `WebDavBookRouter.openWebDavBook`：
   守卫 → 格式识别（MediaTypes）→ SmbArchiveByteSource/WebDavArchiveByteSource →
   按格式分发参考项目 PageLoader（Stream/TarChunk/SolidExtract(失败回退整档)/DocumentExtract/Folder）。
3. PageLoader 就绪 → `SmbPageAdapter/WebDavPageAdapter.attach` → Reader 持有
   `RemotePageSupply` → NativeReaderScreen 的视图经 `remotePageSupply` 取页。
4. 退出/切书：`supply.close()` → PageLoader 作用域 block 返回 → autoCloseScope 关闭字节源。
5. 格式分类：zip/cbz→Stream；tar/cbt→TarChunk；pdf/epub→DocumentExtract；
   7z/rar/cbr→SolidExtract（失败回退整档+本地打开）；mobi/azw3→Unsupported（占位+下载）；
   含图片目录→Folder。
6. WebDAV 特有：服务器不支持 RANGE（200 而非 206）→ `WebDavRangeFallbackHandler`
   整档下载 → 本地 `useArchivePageLoader` 继续（v1 静默降级，扩展点见 MODIFICATIONS）。

## 四、扫描（任务3/13）

`SmbScanner.scanSmbServer(sourceId)` / `WebDavScanner.scanWebDavServer(sourceId)`：
递归列目录（`*_Gateway.listDirectory` 自带 NetworkFolderIndexCache 增量缓存）→
识别 SCANNABLE_FORMATS（zip/cbz/rar/cbr/7z/cb7/tar/cbt/pdf/epub/mobi/azw3）与
含图片目录（FolderGallery）→ `insertBooksBatch`（IGNORE 去重）→ markOk/markError。
扫描不全量下载；本地书籍零影响。扫描触发：设置页 → SMB/WebDAV 服务器管理 → 每项"扫描"按钮。

## 五、封面（任务5/15）

`CoverFetcher.fetch()`（漫读 `CoverMapper.kt`）新增二级前缀分流：
`cv:smb:` → `SmbCoverLoader.loadCoverRawSmb`；`cv:webdav:` → `WebDavCoverLoader`。
缓存：SmbCache/WebDavCache 的 thumb 缓存；提取：与阅读同构的 `openTransient` 会话取第 0 页原始字节；
并发去重：per-cover Mutex；失败/超时 → null → Coil 占位图。

## 六、配置项

- SMB/WebDAV 服务器 CRUD：设置页入口 → 服务器管理屏（列表/添加/编辑/删除/测试/扫描）。
- 密码：仅存 AndroidKeyStore；DB 表无密码列。
- 归档密码（SMB/WebDAV 共用）：参考项目 `Settings.archivePasswds`（DataStore stringSet），
  当前为空集合；需要密码的归档打开时报"该归档需要密码"（design 2.1.3.8 扩展点 1）。
- TLS 自签名证书：WebDavClient v1 为 trust-all（与参考项目一致；WebDavSourceEntity 无
  acceptAnyCert 列），交互式证书确认对话框留作扩展点（MODIFICATIONS.md 已记录）。

## 七、升级路径

1. DB：v12→v13 迁移只新增 SMB_SOURCES/LIBRARY_ROOTS/LOCAL_GALLERIES/WEBDAV_SOURCES 四表，
   旧数据零改动；后续加列（如 acceptAnyCert）走 v13→v14 新迁移。
2. 参考项目代码升级：重拷文件后按 `smbbridge/MODIFICATIONS.md` / `webdavbridge/MODIFICATIONS.md`
   逐条重新应用修改点（LocalLibrary.kt 的 DB 桥接、LocalTokens.kt/Snackbar.kt 提取件、
   R.kt 映射、opt-in 编译参数——均与参考源码文件级独立，重拷不覆盖）。
3. ProGuard：`com.hippo.ehviewer.**`/`com.ehviewer.core.**` 等整包冻结（见 proguard-rules.pro）。
