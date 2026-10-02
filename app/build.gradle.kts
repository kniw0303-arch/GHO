plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scl.livesubko"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scl.livesubko"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 개인 사용용: 디버그 키로 서명해 바로 설치 가능하게 함
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
}

dependencies {
    // 온디바이스 영→한 번역 (오프라인, 수십 ms)
    implementation("com.google.mlkit:translate:17.0.3")
}
