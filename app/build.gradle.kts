plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.ormem.orson"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.ormem.orson"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "1.5"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            // Signed like debug builds so it installs over them. The debug build is too slow for the rings.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        jniLibs { excludes += listOf("lib/x86/**", "lib/x86_64/**") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("com.google.zxing:core:3.5.3")
}
