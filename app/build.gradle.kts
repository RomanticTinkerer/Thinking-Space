import java.net.URI

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

    // The speech and OCR models are bundled as assets. Store them uncompressed: they are already int8-quantised,
    // so zipping gains nothing and would only slow down loading.
    androidResources { noCompress += "onnx" }

    // sherpa's 32-bit x86 build carries its own libonnxruntime.so, which clashes with the one from :editor.
    // We only ship arm64 (see abiFilters), where sherpa links ONNX Runtime statically, so this never matters.
    packaging { jniLibs.pickFirsts += "lib/x86/libonnxruntime.so" }
    sourceSets["main"].assets.directories.add("asr-model")

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
    // Offline speech to text: sherpa-onnx (Apache-2.0). The static-link build carries its own ONNX Runtime, so it
    // does not clash with the one in :editor. The AAR is fetched by downloadAsr below, not stored in git.
    implementation(files("libs/sherpa-onnx.aar"))
    // Gallery + viewer UI: Material 3 with wallpaper-derived (dynamic) colours.
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.activity)
    testImplementation("junit:junit:4.13.2")
}

// Fetches what the voice-note transcriber needs. Everything lands in git-ignored folders, so the repo stays small
// and the app needs no internet permission: only the build machine goes online, once.
// Whisper base.en (int8, English, MIT), Silero VAD (MIT) and the sherpa-onnx library (Apache-2.0).
val downloadAsr by tasks.registering {
    // Pinned to one revision, so the sizes below always match.
    val hf = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en/resolve/59eea950fc76df2453efb57e6c0fd334548e8ffe"
    val sherpa = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    // target file -> (url, expected size in bytes). The asr/ folder becomes the asr/ folder inside the APK's assets.
    val files = mapOf(
        "asr-model/asr/encoder.int8.onnx" to (("$hf/base.en-encoder.int8.onnx") to 29_120_534L),
        "asr-model/asr/decoder.int8.onnx" to (("$hf/base.en-decoder.int8.onnx") to 130_669_978L),
        "asr-model/asr/tokens.txt" to (("$hf/base.en-tokens.txt") to 835_554L),
        "asr-model/asr/silero_vad.onnx" to (("$sherpa/asr-models/silero_vad.onnx") to 643_854L),
        "libs/sherpa-onnx.aar" to (("$sherpa/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar") to 38_691_998L),
    )
    outputs.files(files.keys.map { file(it) })
    outputs.upToDateWhen { files.all { (path, spec) -> file(path).length() == spec.second } }
    doLast {
        files.forEach { (path, spec) ->
            val target = file(path)
            if (target.length() == spec.second) return@forEach
            logger.lifecycle("Downloading $path (${spec.second / 1_000_000} MB)")
            target.parentFile.mkdirs()
            val part = File(target.path + ".part")
            URI(spec.first).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            check(part.length() == spec.second) { "$path came back ${part.length()} bytes, expected ${spec.second}" }
            part.renameTo(target)
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadAsr) }
