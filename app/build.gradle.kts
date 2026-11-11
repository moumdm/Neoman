plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.local.comicreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.local.comicreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/IOUtils LICENSE", "META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.md", "META-INF/NOTICE.txt")
    }
}

dependencies {
    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Compose
    val bom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 压缩包格式支持（全部为纯 Java 库，离线可用）
    implementation("com.github.junrar:junrar:8.1.1")                 // RAR / CBR（RAR4 + RAR5）
    implementation("org.apache.commons:commons-compress:1.28.0")    // 7Z / ZIP 增强
    implementation("org.tukaani:xz:1.9")                            // 7Z LZMA 解码所需
}
