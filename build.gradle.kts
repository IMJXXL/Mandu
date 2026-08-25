plugins {
    id("com.android.application") version "8.7.3" apply false
    // ★ §24 P1：APNG4Android 4 module 是 library，需在 root 声明 plugin 让子 module 引用
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
