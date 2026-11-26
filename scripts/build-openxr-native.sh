#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ndk="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/28.1.13356709}"
loader="${PICO_OPENXR_LOADER:-third_party/pico/openxr/arm64-v8a/libopenxr_loader.so}"
test -f "$loader" || { echo "Missing PICO OpenXR loader: $loader" >&2; exit 1; }
out=xr/app/src/main/jniLibs/arm64-v8a
mkdir -p "$out"
cp "$loader" "$out/libopenxr_loader.so"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++" \
  -std=c++17 -O2 -fPIC -shared -Wall -Wextra -Wno-missing-field-initializers \
  -static-libstdc++ -Wl,-z,max-page-size=16384 \
  -Ithird_party/openxr/include xr/app/src/main/cpp/openxr_hud.cpp \
  -L"$out" -lopenxr_loader -lEGL -lGLESv3 -landroid -ljnigraphics -llog \
  -o "$out/libsynapath_openxr.so"
