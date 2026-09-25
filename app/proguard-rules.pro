# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.mangareader.data.** { *; }

# ★ GIF 闪退修复（2026-08-27 红米 diag 实锤）：APNG4Android 的 gif native 库 libanimation-decoder-gif.so
#   在 JNI_OnLoad 时用硬编码路径 FindClass("com/github/penfeizhou/animation/io/Reader")；
#   R8 改名后 FindClass 返回 null → JNI_OnLoad 返回 JNI_ERR → UnsatisfiedLinkError 闪退。
#   R8 默认规则只保"声明了 native 方法的类"（所以 GifFrame 幸存），io.Reader 是
#   纯 Java 接口会被改名。此处整包冻结类名，动图三件套（gif/apng/awebp 模块）全覆盖。
-keep class com.github.penfeizhou.animation.** { *; }

# ★ 不剥离日志（2026-08-29 哑火事件定论）：
#   此前这里用 -assumenosideeffects 移除 Log.d/v/i/w，同源码下 release 超分全哑
#   （关掉该规则的诊断版立刻恢复正常），实锤该剥离优化触发了 R8 问题。
#   永久保留 release 日志：体积代价可忽略（~0），且线上问题可用"关于-导出诊断日志"远程排障。
#   注意：将来若重新引入此规则，必须整包真机冒烟验证超分。

# 保留行号（崩溃日志定位用）
-keepattributes SourceFile,LineNumberTable

# 忽略缺失的库类（commons-compress 的 xz 依赖未打包）
-dontwarn org.tukaani.xz.**
-dontwarn org.slf4j.**

# ★ 超分 JNI 三件套（realesrgan_jni/manga_cleanup 按类名注册 native 方法）：
#   默认规则虽保 native 方法所在类名，显式冻结整包杜绝 R8 任何合并/改名风险（发布保险）。
-keep class com.mangareader.sr.** { *; }

# ★ native 包 JNI 回调（pdfium_wrapper.cpp 通过 GetMethodID 回调 PdfRangeReader.readBlock）：
#   R8 混淆 SAM 接口类名/方法名后 JNI FindClass 找不到 → NoSuchMethodError。
#   NativeEngine 有 native 方法默认保留，但 PdfRangeReader 是纯 Java 接口会被改名。
-keep class com.mangareader.native.** { *; }

# ★ 仿真翻页 pagecurl 本地模块（2026-09-25 红米真机闪退实锤）：
#   Compose 编译器在 PageCurlState$InternalState 等本模块类上生成 static $stable 字段，
#   R8 类合并/裁剪会把该字段从类上移除，而 PageCurl 内容 lambda 仍在读取 →
#   release 运行时 NoSuchFieldError: No static field $stable（debug 不混淆不复现，
#   恢复默认关掉仿真不进该路径）。整包冻结杜绝 R8 任何合并/改名/裁剪，模块小无体积负担。
-keep class eu.wewox.pagecurl.** { *; }

# ==================== SMB + WebDAV 集成（smb_embed_lv119 任务9.1/19.1） ====================
# 拷入的参考项目 com.hippo.ehviewer 包：JNI 桥接（ArchiveStreamBridge 的 nativeRead/nativeSeek
# 按 JNI 符号注册）、smbj 反射、ktor 序列化符号均需类名/成员名冻结。
-keep class com.hippo.ehviewer.** { *; }
-keep class com.ehviewer.core.** { *; }
-keep class moe.tarsin.** { *; }
-keep class eu.kanade.tachiyomi.** { *; }
# ★ 2026-09-25 无日志闪退实锤修复：libimagedecoder.so 的 JNI_OnLoad→init_java_objects
#   按硬编码签名反射 ImageDecoder.<init>(JII)V（private 构造器），该类包名是
#   tachiyomi.decoder（无 eu.kanade 前缀）未被上面的 keep 覆盖——R8 某次优化决策
#   删除了该 private 构造器，JNI_OnLoad 查不到即 abort（SIGABRT，native 退出无任何
#   Java 崩溃日志）。必须整类保留。
-keep class tachiyomi.decoder.** { *; }
# smbj（SMB 协议栈：bouncycastle/asn1 反射较重，整包保留）
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
# mbassador（smbj 事件总线依赖：SMBEventBus 反射查找 SubscriptionContext 构造函数）
-keep class net.engio.mbassy.** { *; }
# ktor CIO engine（WebDAV；反射构造协程调度器）
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
# arrow-kt（bracketCase 资源安全）
-dontwarn io.arrow.**
# kotlinx-io（参考项目 core/files 依赖）
-dontwarn kotlinx.io.**
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper