# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.mangareader.data.** { *; }

# ★ GIF 闪退修复（2026-08-27 红米 diag 实锤）：APNG4Android 的 gif native 库 libanimation-decoder-gif.so
#   在 JNI_OnLoad 时用硬编码路径 FindClass("com/github/penfeizhou/animation/io/Reader")；
#   R8 改名后 FindClass 返回 null → JNI_OnLoad 返回 JNI_ERR → UnsatisfiedLinkError 闪退。
#   R8 默认规则只保"声明了 native 方法的类"（所以 GifFrame 幸存），io.Reader 是
#   纯 Java 接口会被改名。此处整包冻结类名，动图三件套（gif/apng/awebp 模块）全覆盖。
-keep class com.github.penfeizhou.animation.** { *; }

# 移除调试日志（release 构建，debug 不受影响）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# 保留行号（崩溃日志定位用）
-keepattributes SourceFile,LineNumberTable

# 忽略缺失的库类（commons-compress 的 xz 依赖未打包）
-dontwarn org.tukaani.xz.**
-dontwarn org.slf4j.**
