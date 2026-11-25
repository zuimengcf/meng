#!/usr/bin/env bash
# Build libmengqjs.so for Android ABIs using zig's clang/lld + NDK sysroot.
# (On a normal x86_64 host you can use the Gradle/CMake project instead.)
set -euo pipefail
cd "$(dirname "$0")"
NDK=${NDK:-$PWD/ndk/android-ndk-r27c}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
SR=$TC/sysroot
RT=$(echo $TC/lib/clang/*/lib/linux)
API=24
CLANG="python3 -m ziglang clang"
LLD="python3 -m ziglang ld.lld"
OUT=build/jniLibs
mkdir -p $OUT build/obj

ZINC=$(python3 -c "import ziglang,os;print(os.path.dirname(ziglang.__file__))")/lib/include
CFLAGS=(-nostdlibinc -isystem $ZINC -isystem $SR/usr/include -Oz -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections
  -fstack-protector-strong -D_FORTIFY_SOURCE=2 -D_GNU_SOURCE
  -fno-omit-frame-pointer -funwind-tables -w
  -Isrc/main/cpp/quickjs)

build() {
  local abi=$1 triple=$2 rtarch=$3 extra=${4:-}
  local o=build/obj/$abi; mkdir -p $o $OUT/$abi
  local tgt="--target=${triple}${API} --sysroot=$SR -isystem $SR/usr/include/$(echo $triple | sed s/armv7a/arm/)"
  $CLANG $tgt "${CFLAGS[@]}" $extra -c src/main/cpp/quickjs/quickjs-amalgam.c -o $o/quickjs.o
  $CLANG $tgt "${CFLAGS[@]}" $extra -fvisibility=default -c src/main/cpp/qjs_jni.c -o $o/qjs_jni.o
  local lib=$SR/usr/lib/$triple/$API
  [ "$triple" = armv7a-linux-androideabi ] && lib=$SR/usr/lib/arm-linux-androideabi/$API
  $LLD -shared -soname libmengqjs.so --gc-sections --as-needed -z noexecstack -z relro -z now \
    -z max-page-size=16384 --hash-style=gnu --build-id=sha1 --exclude-libs ALL --strip-all \
    -o $OUT/$abi/libmengqjs.so \
    $lib/crtbegin_so.o $o/qjs_jni.o $o/quickjs.o \
    -L$lib -lm -lc -ldl \
    $RT/libclang_rt.builtins-$rtarch-android.a \
    $lib/crtend_so.o
  ls -la $OUT/$abi/libmengqjs.so
}

build arm64-v8a   aarch64-linux-android   aarch64
build armeabi-v7a armv7a-linux-androideabi arm "-mthumb"
build x86_64      x86_64-linux-android    x86_64
build x86         i686-linux-android      i686
