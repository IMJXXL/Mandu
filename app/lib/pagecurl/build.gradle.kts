plugins {
    id("com.android.library")

    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "eu.wewox.pagecurl"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
}