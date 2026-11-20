# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate
# Keep JNI interface
-keep class org.fcitx.fcitx5.android.core.* { *; }
-keep class org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase {
    public <init>(...);
}
# Keep dependency magic
-keep class ** extends org.mechdancer.dependency.Component {
    int hashCode();
    boolean equals(java.lang.Object);
}
# Keep kotlinx.serialization generated serializers (R8 shrink)
-keep,includedescriptorclasses class org.fcitx.fcitx5.android.**$$serializer { *; }
-keepclassmembers class org.fcitx.fcitx5.android.** {
    *** Companion;
}
-keepclasseswithmembers class org.fcitx.fcitx5.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep native-loaded JNI entry classes (external fun 声明所在类)
-keep class org.fcitx.fcitx5.android.data.pinyin.** {
    public *;
}
-keep class org.fcitx.fcitx5.android.data.table.** {
    public *;
}
# android.app.Application 子类（Manifest 入口）
-keep class * extends android.app.Application { *; }

# remove kotlin null checks
-processkotlinnullchecks remove

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
