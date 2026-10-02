plugins {
    // 纯资产 APK：不用 mihonx/项目应用约定（那会注入 desugar 运行时，给无代码模块塞 ~2.2MB dex）
    id("com.android.application")
}

// NPU 模型包 APK：一个 HTP 代一个包。构建方式（在仓库根目录）：
//
//   ./gradlew.bat :modelapk:assembleRelease \
//     -PmodelId=com.mangareader.model.v75 \
//     -PmodelLabel=漫读-NPU模型-v75 \
//     -PmodelVersionCode=1
//
// 打包前把该代的 context 放进 src/main/assets/qnn-contexts/、生成好的
// models.json 放进 src/main/assets/（tools/npupack 打包脚本负责）。
// 包内无任何代码（hasCode=false、无组件）：主程序按前缀 com.mangareader.model.*
// 发现、校验签名（必须 manga-reader.jks 同签）、读清单后复制 context。
android {
    namespace = "com.mangareader.modelpkg"
    compileSdk = 36

    defaultConfig {
        applicationId = (project.findProperty("modelId") as String?)?.takeIf { it.isNotBlank() }
            ?: "com.mangareader.model.template"

        minSdk = 26
        targetSdk = 37

        versionCode = (project.findProperty("modelVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("modelVersionName") as String?) ?: "1.0"

        manifestPlaceholders["modelLabel"] =
            (project.findProperty("modelLabel") as String?)?.takeIf { it.isNotBlank() }
                ?: "漫读 NPU 模型包"
    }

    signingConfigs {
        create("mangaRelease") {
            val ks = rootProject.file("manga-reader.jks")
            if (ks.exists()) {
                storeFile = ks
                storeType = "PKCS12"
                storePassword = "M@ngaR3ader#2026"
                keyAlias = "manga-reader"
                keyPassword = "M@ngaR3ader#2026"
            }
        }
    }

    buildTypes {
        named("release") {
            // 无代码可压缩；资产就是全部载荷
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("mangaRelease")
        }
    }
}
