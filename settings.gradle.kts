pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android is only published on JitPack. Nothing else comes from there.
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "OpenThinkingSpace"
include(":app")
include(":recorder")
// Vendored from ReFra (Apache-2.0): image cropper + its gesture helpers, and the markup editor UI.
include(":gesture")
include(":cropper")
include(":editor")
