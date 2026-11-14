plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.idlemate.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.idlemate.app"
        minSdk = 26
        targetSdk = 35
        // 發布時由 GitHub Actions 提供：版本名稱取自標籤（v0.2.0 → 0.2.0），版本號取自建置次數（越來越大才能覆蓋安裝）
        versionCode = System.getenv("IDLEMATE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("IDLEMATE_VERSION_NAME") ?: "0.1.0-dev"
    }

    // 固定的除錯簽章：每次編譯的 APK 簽章相同，才能直接覆蓋安裝
    signingConfigs {
        getByName("debug") {
            val ks = file("idlemate-debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // 正式簽章：只在 GitHub Actions 裡由加密的 Secrets 提供，程式碼裡沒有簽章檔與密碼
        create("release") {
            val path = System.getenv("IDLEMATE_KEYSTORE_FILE")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storeType = "pkcs12"
                storePassword = System.getenv("IDLEMATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("IDLEMATE_KEY_ALIAS")
                keyPassword = System.getenv("IDLEMATE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("IDLEMATE_KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.15.0")
}
