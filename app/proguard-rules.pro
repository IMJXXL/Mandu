# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.mangareader.data.** { *; }

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
