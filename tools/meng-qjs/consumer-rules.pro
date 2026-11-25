# JNI looks these up by name
-keepclasseswithmembernames,includedescriptorclasses class org.fcitx.fcitx5.android.script.qjs.QjsNative {
    native <methods>;
}
-keep class org.fcitx.fcitx5.android.script.qjs.QjsException {
    <init>(int, java.lang.String);
}
