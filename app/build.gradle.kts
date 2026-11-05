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
    // Offline OCR: Tesseract 5 (Apache-2.0) through Tesseract4Android (Apache-2.0).
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // Gallery + viewer UI: Material 3 with wallpaper-derived (dynamic) colours.
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.activity)
}
