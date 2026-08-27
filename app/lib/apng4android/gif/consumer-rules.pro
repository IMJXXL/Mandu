# ★ 2026-08-27 GIF 闪退修复：libanimation-decoder-gif.so 的 JNI_OnLoad 硬编码 FindClass
#   com/github/penfeizhou/animation/gif/decode/GifFrame 与 io.Reader（在 frameanimation 模块）
-keep class com.github.penfeizhou.animation.gif.** { *; }
