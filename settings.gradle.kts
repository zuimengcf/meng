pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "meng"

// Only modules needed to assemble :app are included.
// The pure-native modules (lib:fcitx5, lib:fcitx5-lua, lib:libime,
// lib:fcitx5-chinese-addons) and the standalone plugin APKs are intentionally
// excluded: their compiled .so and runtime data are shipped statically as
// jniLibs + assets (prebuilt from the official release), so no NDK/CMake build
// is required anymore.
include(":lib:common")
include(":codegen")
include(":app")
