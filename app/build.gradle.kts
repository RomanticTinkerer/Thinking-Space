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
        // ONNX Runtime and Tesseract ship a native library per CPU type. Phones are arm64; skipping the
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

    // The speech model is bundled as assets (downloaded below). Store it uncompressed: it is
    // already int8-quantised and a 650 MB inflate on first use would just be wasted time.
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
    // Offline OCR: Tesseract 5 (Apache-2.0) through Tesseract4Android (Apache-2.0).
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
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
}

// Fetches what the voice-note transcriber needs. Everything lands in git-ignored folders, so the repo stays small
// and the app needs no internet permission: only the build machine goes online, once.
// Parakeet TDT 0.6B v2 (int8, English, CC-BY-4.0), Silero VAD (MIT) and the sherpa-onnx library (Apache-2.0).
val downloadAsr by tasks.registering {
    val hf = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
    val sherpa = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    // target file -> (url, expected size in bytes)
    val files = mapOf(
        "asr-model/encoder.int8.onnx" to (("$hf/encoder.int8.onnx") to 652_184_296L),
        "asr-model/decoder.int8.onnx" to (("$hf/decoder.int8.onnx") to 7_257_753L),
        "asr-model/joiner.int8.onnx" to (("$hf/joiner.int8.onnx") to 1_739_080L),
        "asr-model/tokens.txt" to (("$hf/tokens.txt") to 9_384L),
        "asr-model/silero_vad.onnx" to (("$sherpa/asr-models/silero_vad.onnx") to 643_854L),
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
