import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mangareader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mangareader"
        minSdk = 26
        targetSdk = 36
versionCode = 19
versionName = "1.2.1"

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DCMAKE_BUILD_PARALLEL_LEVEL=1"
                // QNN SDK 路径（可选；不存在则编译无 NPU 的 sr_engine）
                val qnnCandidates = listOfNotNull(
                    properties["QNN_SDK_DIR"] as? String,
                    System.getenv("QNN_SDK_DIR"),
                    "X:/115/manduAAAAAAA/v2.49.0.260730/qairt/2.49.0.260730",
                )
                val qnnRoot = qnnCandidates.firstOrNull { File("$it/include/QNN/QnnInterface.h").exists() }
                if (qnnRoot != null) {
                    arguments += "-DQNN_SDK_DIR=$qnnRoot"
                }
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "29.0.14206865"

    signingConfigs {
        create("release") {
            storeFile = file("../manga-reader.jks")
            storePassword = "M@ngaR3ader#2026"
            keyAlias = "manga-reader"
            keyPassword = "M@ngaR3ader#2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true   // 关于页动态显示版本号
    }

    packaging {
        jniLibs {
            keepDebugSymbols += setOf("**/libzip.so", "**/libpdfium.so")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // ViewPager2 for horizontal page flip
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // RecyclerView (transitive but explicit for clarity)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // SubsamplingScaleImageView for efficient large image rendering
    implementation("com.github.tachiyomiorg:subsampling-scale-image-view:66e0db195d")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Apache Commons Compress - ZIP/7Z handling
    implementation("org.apache.commons:commons-compress:1.26.1")

    // JunRAR - RAR/CBR support
    implementation("com.github.junrar:junrar:7.5.5")

    // Coil - image loading with memory+disk cache
    implementation("io.coil-kt:coil-compose:2.7.0")
    // ★ Coil AnimatedImageDecoder（系统 ImageDecoder 路线，API≥28 原生 WEBP/GIF/APNG 动图）
    //   与 Kototoro 的 coil3.gif.AnimatedImageDecoder 一致，比 APNG4Android 更稳（API≥28 原生解码）。
    //   坑警示（Mihon）：必须在 ImageLoader 组件中注册此 Factory，否则动图只出首帧 BitmapDrawable。
    implementation("io.coil-kt:coil-gif:2.7.0")

    // zhanghai libarchive-android (open source, Apache 2.0)
    implementation(files("libs/libarchive-android.aar"))

    // ★ §24 P1：APNG4Android 走 source include（4 个 module 拷到 app/lib/apng4android/）
    //   原方案走 JitPack 拉 `com.github.penfeizhou:APNG4Android:3.0.5`（确实 jitpack build ok），
    //   但实际解析超时——改用 source include 走项目内模块依赖，编译更稳，可控。
    //   4 个 module 是从 penfeizhou/APNG4Android 3.0.5 拷贝（X:\115\APNG4Android-3.0.5）
    //   含 cpp（gif 用的 giflib CMake）：漫读本身已配 NDK/CMake，兼容。
    implementation(project(":frameanimation"))
    implementation(project(":apng"))
    implementation(project(":awebp"))
    implementation(project(":gif"))
    // ★ §24 P3c-P8：APNG4Android 的 FrameAnimationDrawable 间接 extends Animatable2Compat
    //   只显式声明 vectordrawable-animated 让 app 编译可见
    implementation("androidx.vectordrawable:vectordrawable-animated:1.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
