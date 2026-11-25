// Standalone Android library module. Put it in its own project (or include it), then
// ./gradlew :meng-qjs:assembleRelease  -> build/outputs/aar/
plugins { id("com.android.library") }

android {
    namespace = "org.fcitx.fcitx5.android.script.qjs"
    compileSdk = 35
    ndkVersion = "27.2.12479018" // r27c
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
