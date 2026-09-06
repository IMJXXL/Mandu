# 参考项目 WebDAV 代码修改记录

本文件记录 WebDAV 侧对参考项目 `com.hippo.ehviewer.webdav` 包代码的所有修改点
（smb_embed_lv119 任务 11.4/19.4）。

## 结论：零修改

拷入的 `com/hippo/ehviewer/webdav/`（WebDavGateway/WebDavClient/WebDavCache/
WebDavArchiveByteSource/WebDavRepository/WebDavPasswordStore）与
`gallery/WebDavFolderPageLoader.kt` **保持参考项目原样，零 import / 零代码修改**。

WebDAV 侧的全部适配都放在 `com.mangareader.webdavbridge` 新建胶水层：
- `WebDavPaths.kt`：`webdav:` / `cv:webdav:` 路径解析
- `WebDavBookRouter.kt`：格式分发 + RANGE 降级
- `WebDavPageAdapter.kt`：RemotePageSupply 实现
- `WebDavCoverLoader.kt` / `WebDavScanner.kt` / `WebDavUnsupportedFormatHandler.kt`
- `WebDavBridges.kt`：WebDavDbBridge / WebDavAppContextBridge（复用 SmbAppContextBridge）

## v1 决策偏离（design.md 对照）

1. **WebDavTlsCertDialog 未实现**（任务12.3）：拷入的 WebDavClient 为无条件 trust-all
   TrustManager（参考项目原行为，L161-164），且 WebDavSourceEntity（任务1.3建的表）
   无 `acceptAnyCert` 列——无法承载开关。v1 行为 = 恒接受任意证书（等价 acceptAnyCert=true）。
   扩展路径：DB v14 加列 + WebDavClient 增加确认回调钩子（需记入本文件）。
2. **RANGE 降级提示对话框延后**（任务14.3 的 silentFallbackPref=false 分支）：
   v1 恒静默降级（等价开关默认值 true）；WebDavRangeFallbackHandler.silentFallbackPref
   已保留为扩展点，漫读 Preferences 持久化待接。
3. **降级后用本地 useArchivePageLoader**（design 原文为 FileArchiveByteSource）：
   用户结果一致（本地流式读取），复用 SolidExtract 失败回退同款机制，实现更少。

## v1.2.1 变更（2026-09-06 实测修复 —— 打破上文"零修改"结论）

真实服务器 E2E 实测（详见 smbbridge/MODIFICATIONS.md 同日章节）后，以下 3 个参考项目文件
出现行为级修改，升级重拷参考项目代码时需按 smbbridge 同日记录重新应用：

- `WebDavClient.kt`：`resolveHref` 新增 `encodeRawPathSegments` 按段重编码 ——
  修复文件名含 `#` 时被 URL fragment 截断（目录名缺尾、子列表为空）。
- `WebDavCache.kt`：页/缩略图缓存路径改为 `<root>/<sourceId>/…` 按源分目录 +
  `deleteSourceCache(sourceId)` + `sweepLegacyFlatCache()` 旧平铺一次性清扫。
- `WebDavRepository.kt`：`delete()` 接入 `WebDavCache.deleteSourceCache(source.id)`。

`WebDavGateway.kt` / `WebDavArchiveByteSource.kt` / `WebDavPasswordStore.kt` 仍为零修改。
