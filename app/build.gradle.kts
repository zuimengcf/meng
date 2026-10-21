plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.fcitx.fcitx5.android"
    // Compile the plain-Java integration example as part of the delivery verification.
    sourceSets.getByName("main").java.srcDir(rootProject.file("sdk/java"))
    defaultConfig {
        applicationId = "com.meng.ime"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native libraries are prebuilt and shipped under src/main/jniLibs.
        // No C/C++ compilation is needed; only ship the arm64-v8a ABI.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            // .debug 后缀包名：与正式包（com.meng.ime）隔离并存，互不覆盖
            applicationIdSuffix = ".debug"
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

// fcitx5 / libime / chinese-addons runtime data is shipped as static assets
// under src/main/assets/usr (prebuilt from official release), so no fcitxComponent
// CMake install tasks are needed. Native .so ships under src/main/jniLibs.

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    // Note: lib:fcitx5 / lib:fcitx5-lua / lib:libime / lib:fcitx5-chinese-addons
    // are pure native (C++) modules that only produce .so. Their prebuilt .so
    // are shipped via src/main/jniLibs, so they are intentionally NOT linked here.
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(libs.snakeyaml)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}

// Rebuild the Rime addon from the checked-in static archives before every
// Android build when any native input changes. The script reads SDK settings
// from the environment or ../local.properties and --install updates jniLibs,
// so stale librime.so files cannot silently be packaged.
val buildSmartRime = tasks.register<Exec>("buildSmartRime") {
    val root = rootProject.layout.projectDirectory
    val prebuilt = root.dir("lib/fcitx5/src/main/cpp/prebuilt")
    val rimeSources = root.dir("plugin/rime/src/main/cpp/fcitx5-rime")
    inputs.file(root.file("tools/build-smart-rime.sh"))
    inputs.dir(rimeSources)
    inputs.dir(prebuilt)
    inputs.files(
        root.file("app/src/main/jniLibs/arm64-v8a/libFcitx5Core.so"),
        root.file("app/src/main/jniLibs/arm64-v8a/libFcitx5Config.so"),
        root.file("app/src/main/jniLibs/arm64-v8a/libFcitx5Utils.so"),
        root.file("app/src/main/jniLibs/arm64-v8a/libc++_shared.so")
    )
    outputs.file(root.file("app/src/main/jniLibs/arm64-v8a/librime.so"))
    commandLine(root.file("tools/build-smart-rime.sh").asFile.absolutePath, "--install")
}
tasks.named("preBuild").configure { dependsOn(buildSmartRime) }
