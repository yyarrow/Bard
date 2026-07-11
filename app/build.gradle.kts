import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 后端代理地址与应用口令，可在 local.properties 覆盖（本地调试指向 vercel dev）。
// OpenRouter key 只存在服务端，不再进 APK。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val bardApiBase: String =
    localProps.getProperty("BARD_API_BASE")?.takeIf { it.isNotBlank() }
        ?: "https://bard-api.warmbeing.com"
val bardAppSecret: String =
    localProps.getProperty("BARD_APP_SECRET")?.takeIf { it.isNotBlank() } ?: ""

android {
    namespace = "app.bard"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.bard"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1"
        buildConfigField("String", "BARD_API_BASE", "\"$bardApiBase\"")
        buildConfigField("String", "BARD_APP_SECRET", "\"$bardAppSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 自测阶段用 debug keystore 签名，能直接安装；上架前换正式签名
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
