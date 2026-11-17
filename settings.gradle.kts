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
        // Mirror first: repo.maven.apache.org returns 403 for some artifacts
        // (e.g. splitties release variants); aliyun serves them fine.
        maven("https://maven.aliyun.com/repository/central")
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
