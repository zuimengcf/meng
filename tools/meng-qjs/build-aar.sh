#!/usr/bin/env bash
# Build meng-qjs-<ver>.aar without Gradle: 4 ABI .so + classes.jar + manifest.
# Needs: NDK r27c (unzipped), JDK 8+ (javac/jar), zip, python3 + `pip install ziglang`.
# Usage: NDK=/path/android-ndk-r27c ./build-aar.sh
set -euo pipefail
cd "$(dirname "$0")"
VER=0.18.0
PKG=org/fcitx/fcitx5/android/script/qjs
STAGE=build/aar
OUT=out/meng-qjs-$VER.aar

# 1) native: build/jniLibs/<abi>/libmengqjs.so
./build-so.sh

# 2) java -> classes.jar (Java 8 bytecode, no android.jar needed)
rm -rf build/cls $STAGE && mkdir -p build/cls $STAGE/jni out
javac --release 8 -encoding UTF-8 -d build/cls src/main/java/$PKG/*.java
jar cf $STAGE/classes.jar -C build/cls .

# 3) aar layout
cp -r build/jniLibs/* $STAGE/jni/
cat > $STAGE/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.fcitx.fcitx5.android.script.qjs">
    <uses-sdk android:minSdkVersion="24" />
</manifest>
EOF
cp consumer-rules.pro $STAGE/proguard.txt
: > $STAGE/R.txt
cp LICENSE.quickjs-ng $STAGE/

# 4) zip it (aar = zip)
rm -f $OUT
(cd $STAGE && zip -qr -X ../../$OUT .)
ls -l $OUT
unzip -l $OUT
