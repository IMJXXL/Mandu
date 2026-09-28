import java.io.File

plugins {
    id("com.android.application")

    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.mangareader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mangareader"
        minSdk = 24
        targetSdk = 37
versionCode = 35
versionName = "1.35"

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DCMAKE_BUILD_PARALLEL_LEVEL=1"
                // ★ Rust 动图 WebP 解码器（task 8 自研）：libwebp-sys2 需 nightly 工具链，
                //   stable 构建报 E0554——待装 nightly 后再启用；当前动图走 v1.2 弹窗+AnimPager 方案
                // arguments += "-DENABLE_RUST_WEBP=ON"
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // 拷入的参考项目代码使用实验API（contracts/atomics），全局opt-in避免修改参考文件
            freeCompilerArgs.add("-opt-in=kotlin.contracts.ExperimentalContracts")
            freeCompilerArgs.add("-opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi")
        }
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

baselineProfile {
    mergeIntoMain = true
}

dependencies {
    baselineProfile(project(":baseline-profile"))
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
    implementation("com.github.tachiyomiorg:subsampling-scale-image-view:66e0db195d") {
        exclude(group = "com.github.tachiyomiorg", module = "image-decoder")
    }

    // PageCurl - simulation page curl effect (Apache 2.0, compatible with GPL v3)
    // Local fork with PR #39: spread leaf turns + real back-face content
    implementation(project(":pagecurl"))

    // Room
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Apache Commons Compress - ZIP/7Z handling
    implementation("org.apache.commons:commons-compress:1.26.1")

    // XZ - required by Commons Compress for LZMA2 (7z/CB7 extraction)
    implementation("org.tukaani:xz:1.10")

    // JunRAR - RAR/CBR support
    implementation("com.github.junrar:junrar:7.5.5")

    // Coil3 - image loading with memory+disk cache (upgraded from Coil 2.7.0)
    implementation(platform("io.coil-kt.coil3:coil-bom:3.4.0"))
    implementation("io.coil-kt.coil3:coil-compose")
    implementation("io.coil-kt.coil3:coil-gif")

    // okio - required by Coil3 DiskCache (okio.Path)
    implementation("com.squareup.okio:okio:3.9.1")

    // zhanghai libarchive-android (open source, Apache 2.0)
    implementation(files("libs/libarchive-android.aar"))

    // ★ §24 P1：APNG4Android source include（2026-09-05 恢复 v1.2 本地动图引擎，用户裁定）
    //   4 个 module 在 app/lib/apng4android/（penfeizhou 3.0.5 源码，settings.gradle.kts 已 include）
    implementation(project(":frameanimation"))
    implementation(project(":apng"))
    implementation(project(":awebp"))
    implementation(project(":gif"))
    // ★ §24 P3c-P8：FrameAnimationDrawable 间接 extends Animatable2Compat，显式声明保证编译可见
    implementation("androidx.vectordrawable:vectordrawable-animated:1.1.0")

    // SMB - smbj (SMB protocol library, same version as reference project)
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("com.hierynomus:asn-one:0.6.0")

    // kotlinx-serialization (NetworkFolderIndexCache JSON persistence)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // splitties (reference project appCtx dependency, zero-modification compat)
    implementation("com.louiscad.splitties:splitties-appctx:3.0.0")
    implementation("com.louiscad.splitties:splitties-systemservices:3.0.0")

    // kotlinx-io (reference project core/files dependency)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")

    // kotlinx-datetime (reference project util dependency)
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")

    // kotlinx-serialization-cbor (GalleryPreview CborArray)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.11.0")

    // ktor (WebDAV + Komga HTTP client)
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-android:3.5.0")
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-client-okhttp:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")

    // Arrow-kt (reference project autoCloseScope/Either/bracketCase)
    implementation(platform("io.arrow-kt:arrow-stack:2.2.2.1"))
    implementation("io.arrow-kt:arrow-fx-coroutines")
    implementation("io.arrow-kt:arrow-functions")
    implementation("io.arrow-kt:arrow-resilience")

    // logcat (reference project core/util logging)
    implementation("com.squareup.logcat:logcat:0.4")

    // androidx datastore (reference project preferences)
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // androidx paging (reference project DAO paging)
    implementation("androidx.paging:paging-compose:3.5.0")
    implementation("androidx.room:room-paging:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
