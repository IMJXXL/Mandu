# ★ 2026-08-27 GIF 闪退修复：io.Reader 接口被 gif native 库 JNI_OnLoad 按硬编码路径
#   FindClass，整模块冻结类名防 R8 改名（上游 AAR 自带 consumer 规则，源码内嵌时丢失）
-keep class com.github.penfeizhou.animation.io.** { *; }
-keep class com.github.penfeizhou.animation.loader.** { *; }
-keep class com.github.penfeizhou.animation.decode.** { *; }
-keep class com.github.penfeizhou.animation.FrameAnimationDrawable { *; }
