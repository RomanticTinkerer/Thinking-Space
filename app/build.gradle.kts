plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "com.openthinking.blankassistant"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.openthinking.blankassistant"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // ONNX Runtime ships a native library per CPU type. Phones are arm64; skipping the
        // rest saves about 100 MB. Add "armeabi-v7a" or "x86_64" here for old phones or an emulator.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Vendored Dimowner/AudioRecorder MediaRecorder engine (Apache-2.0); brings in coroutines.
    implementation(project(":recorder"))
    // Vendored ReFra markup + crop editor. Launched by class name; its manifest merges in.
    implementation(project(":editor"))
    implementation("com.jakewharton.timber:timber:5.0.1")
    // Offline OCR: PaddleOCR models (Apache-2.0) run through ONNX Runtime (the editor module uses it too).
    implementation(libs.onnxruntime.android)
    // Gallery + viewer UI: Material 3 with wallpaper-derived (dynamic) colours.
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.activity)
    testImplementation("junit:junit:4.13.2")
}
