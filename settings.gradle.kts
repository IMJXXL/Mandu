pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "MangaReader"
include(":app")
// ★ §24 P1：APNG4Android 4 个 module（source include 到 app/lib/apng4android/<m>/）
//   恢复本地动图 v1.2 方案（用户 2026-09-05 裁定 APNG 引擎更好；仅本地路径使用）
include(":frameanimation")
include(":apng")
include(":awebp")
include(":gif")

project(":frameanimation").projectDir = file("app/lib/apng4android/frameanimation")
project(":apng").projectDir = file("app/lib/apng4android/apng")
project(":awebp").projectDir = file("app/lib/apng4android/awebp")
project(":gif").projectDir = file("app/lib/apng4android/gif")

