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
