plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.choury.bridgemcp"
    compileSdk = 37
    // 本机 SDK 37 以次版本目录 android-37.0 提供（OkHttp 5.5 要求 compileSdk ≥ 37）
    compileSdkMinor = 0


    defaultConfig {
        applicationId = "com.choury.bridgemcp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1100
        versionName = "1.1.0"
    }

    signingConfigs {
        System.getenv("APP_KEYSTORE")?.let { path ->
            create("envKeystore") {
                storeFile = file(path)
                storePassword = System.getenv("APP_KEYSTORE_PASS")
                keyAlias = System.getenv("APP_KEY_ALIAS")
                keyPassword = System.getenv("APP_KEY_PASS")
                    ?: System.getenv("APP_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("envKeystore")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
