#!/usr/bin/env bash
set -Eeuo pipefail

# Build the Rime addon against the checked-in static librime archive.  This
# deliberately never links the old app/src/main/jniLibs librime.so.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ABI=${ABI:-arm64-v8a}
API=${ANDROID_PLATFORM:-23}
INSTALL=0
[[ "${1:-}" == "--install" ]] && INSTALL=1
OUT=${OUT_DIR:-"$ROOT/build/smart-native"}

# Prefer explicit environment values, then local.properties, and finally tools
# discoverable on PATH.  Do not bake a developer-specific SDK path into this file.
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$SDK" && -f "$ROOT/local.properties" ]]; then
  SDK=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -n1 || true)
  SDK=${SDK//\\:/:}
  SDK=${SDK//\\\\/\\}
fi
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
if [[ -z "$NDK" && -n "$SDK" ]]; then
  for v in 28.0.13004108 27.2.12479018; do
    [[ -d "$SDK/ndk/$v" ]] && { NDK="$SDK/ndk/$v"; break; }
  done
fi
CMAKE=${CMAKE:-}
if [[ -z "$CMAKE" && -n "$SDK" ]]; then
  for v in 3.31.6 3.22.1; do
    [[ -x "$SDK/cmake/$v/bin/cmake" ]] && { CMAKE="$SDK/cmake/$v/bin/cmake"; break; }
  done
fi
[[ -x "$CMAKE" ]] || CMAKE=$(command -v cmake || true)
[[ -d "$NDK" && -f "$NDK/build/cmake/android.toolchain.cmake" ]] || { echo "error: Android NDK not found (set ANDROID_NDK or configure local.properties)" >&2; exit 2; }
[[ -x "$CMAKE" ]] || { echo "error: cmake not found (set CMAKE)" >&2; exit 2; }
[[ "$ABI" == arm64-v8a ]] || { echo "error: this delivery is intentionally arm64-v8a only" >&2; exit 2; }

SRC="$ROOT/plugin/rime/src/main/cpp/fcitx5-rime/src"
FCROOT="$ROOT/lib/fcitx5/src/main/cpp/fcitx5/src"
PREBUILT="$ROOT/lib/fcitx5/src/main/cpp/prebuilt"
RIME_INC="$PREBUILT/librime/$ABI/include"
LIBDIR="$ROOT/app/src/main/jniLibs/$ABI"
RIME_A="$PREBUILT/librime/$ABI/lib/librime.a"
for f in "$SRC/rimestate.cpp" "$RIME_INC/rime_api.h" "$RIME_A" \
  "$PREBUILT/glog/$ABI/lib/libglog.a" "$PREBUILT/leveldb/$ABI/lib/libleveldb.a" \
  "$PREBUILT/lua/$ABI/lib/liblua_static.a" "$PREBUILT/marisa/$ABI/lib/libmarisa.a" \
  "$PREBUILT/opencc/$ABI/lib/libopencc.a" "$PREBUILT/yaml-cpp/$ABI/lib/libyaml-cpp.a" \
  "$LIBDIR/libFcitx5Core.so" "$LIBDIR/libFcitx5Config.so" "$LIBDIR/libFcitx5Utils.so" "$LIBDIR/libc++_shared.so"; do
  [[ -e "$f" ]] || { echo "error: required artifact missing: $f" >&2; exit 3; }
done

rm -rf "$OUT/cmake" "$OUT/stage"
mkdir -p "$OUT/cmake" "$OUT/stage/$ABI" "$OUT/generated/fcitx-utils" "$OUT/generated/fcitx-config" "$OUT/generated/fcitx"
cat > "$OUT/generated/fcitx-utils/fcitxutils_export.h" <<'EOF'
#pragma once
#define FCITXUTILS_EXPORT
#define FCITXUTILS_DEPRECATED_EXPORT
#define FCITXUTILS_DEPRECATED
EOF
cat > "$OUT/generated/fcitx-config/fcitxconfig_export.h" <<'EOF'
#pragma once
#define FCITXCONFIG_EXPORT
#define FCITXCONFIG_DEPRECATED_EXPORT
#define FCITXCONFIG_DEPRECATED
EOF
cat > "$OUT/generated/fcitx/fcitxcore_export.h" <<'EOF'
#pragma once
#define FCITXCORE_EXPORT
#define FCITXCORE_DEPRECATED_EXPORT
#define FCITXCORE_DEPRECATED
EOF
cat > "$OUT/cmake/CMakeLists.txt" <<EOF
cmake_minimum_required(VERSION 3.18)
project(smart_rime LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-O2 -DNDEBUG -fvisibility=hidden)
add_link_options(-Wl,--hash-style=gnu -Wl,--build-id=none -Wl,--no-undefined)
add_library(rime SHARED
  "$SRC/rimestate.cpp" "$SRC/rimeengine.cpp" "$SRC/rimecandidate.cpp"
  "$SRC/rimesession.cpp" "$SRC/rimeaction.cpp" "$SRC/rimefactory.cpp")
# Match upstream: without this, _() queries the default catalog, not fcitx5-rime.
target_compile_definitions(rime PRIVATE FCITX_GETTEXT_DOMAIN=\"fcitx5-rime\" RIME_DATA_DIR="usr/share/rime-data" FCITX_INSTALL_LOCALEDIR=\"/usr/share/locale\" FCITX_RIME_NO_DBUS FCITX_RIME_VERSION=\"5.1.16\")
target_compile_options(rime PRIVATE -include sys/types.h)
target_include_directories(rime PRIVATE "$SRC" "$OUT/generated" "$FCROOT" "$FCROOT/lib" "$FCROOT/modules/notifications" "$RIME_INC")
# librime is fully included so its API and Lua/plugin registration objects are
# present; its six dependency archives remain static as required by upstream CMake.
target_link_libraries(rime PRIVATE
  "-Wl,--whole-archive" "$RIME_A" "-Wl,--no-whole-archive"
  "$PREBUILT/glog/$ABI/lib/libglog.a"
  "$PREBUILT/leveldb/$ABI/lib/libleveldb.a"
  "$PREBUILT/lua/$ABI/lib/liblua_static.a"
  "$PREBUILT/marisa/$ABI/lib/libmarisa.a"
  "$PREBUILT/opencc/$ABI/lib/libopencc.a"
  "$PREBUILT/yaml-cpp/$ABI/lib/libyaml-cpp.a"
  "$LIBDIR/libFcitx5Core.so" "$LIBDIR/libFcitx5Config.so" "$LIBDIR/libFcitx5Utils.so"
  log dl android)
# liblibrime.so is intentionally not an output name: Android expects librime.so.
set_target_properties(rime PROPERTIES OUTPUT_NAME rime LIBRARY_OUTPUT_DIRECTORY "$OUT/stage/$ABI" INSTALL_RPATH "\$ORIGIN")
EOF

"$CMAKE" -S "$OUT/cmake" -B "$OUT/cmake/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
"$CMAKE" --build "$OUT/cmake/build" --target rime --parallel "${JOBS:-2}"
cp -f "$OUT/stage/$ABI/librime.so" "$OUT/librime.so"
if (( INSTALL )); then
  install -m 0644 "$OUT/librime.so" "$LIBDIR/librime.so"
  echo "Installed: $LIBDIR/librime.so"
fi
printf '\nBuilt: %s\n' "$OUT/librime.so"
file "$OUT/librime.so"
readelf -d "$OUT/librime.so" | grep -E 'SONAME|NEEDED|RPATH|RUNPATH' || true
nm -D --defined-only "$OUT/librime.so" | grep -E '(^| )rime_get_api$|fcitx_addon_factory_instance_rime' || true
