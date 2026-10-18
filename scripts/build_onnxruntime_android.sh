#!/usr/bin/env bash
# Builds ONNX Runtime for Android from its source code, so the app has no prebuilt native
# libraries (F-Droid requires this). Then build the app with:  ./gradlew assembleRelease -PonnxFromSource=true
#
#   ORT_VERSION   ONNX Runtime release to build (must match the Java API the app uses). Default 1.20.0.
#   ABIS          Phone types to build for. Default "arm64-v8a armeabi-v7a".
#   ANDROID_HOME  Android SDK. ANDROID_NDK_HOME picks the NDK; otherwise the newest installed one is used.
#
# Output: build/onnxruntime-src (the source, whose Java API the app compiles) and
#         build/onnxruntime-jni/<abi>/libonnxruntime.so, libonnxruntime4j_jni.so
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ORT_VERSION="${ORT_VERSION:-1.20.0}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
API=29
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME to the Android SDK}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "No Android NDK found; set ANDROID_NDK_HOME" >&2; exit 1; }
SRC="$ROOT/build/onnxruntime-src"
OUT="$ROOT/build/onnxruntime-jni"

# The same output on every machine.
export SOURCE_DATE_EPOCH=1700000000 TZ=UTC

if [ ! -f "$SRC/tools/ci_build/build.py" ]; then
  rm -rf "$SRC"
  git clone --depth 1 --branch "v$ORT_VERSION" --recurse-submodules --shallow-submodules \
    https://github.com/microsoft/onnxruntime.git "$SRC"
fi

GEN=()
if command -v ninja >/dev/null; then GEN=(--cmake_generator Ninja); fi

for ABI in $ABIS; do
  echo "== Building ONNX Runtime $ORT_VERSION for $ABI"
  python3 "$SRC/tools/ci_build/build.py" \
    --build_dir "$ROOT/build/onnxruntime-$ABI" \
    --config Release \
    --android --android_sdk_path "$SDK" --android_ndk_path "$NDK" \
    --android_abi "$ABI" --android_api "$API" \
    --build_shared_lib --build_java \
    --parallel --skip_tests --skip_submodule_sync --compile_no_warning_as_error \
    "${GEN[@]}"
  mkdir -p "$OUT/$ABI"
  for lib in libonnxruntime.so libonnxruntime4j_jni.so; do
    found="$(find "$ROOT/build/onnxruntime-$ABI/Release" -name "$lib" -not -path '*/intermediates/*' | head -1)"
    [ -n "$found" ] || { echo "Missing $lib for $ABI" >&2; exit 1; }
    cp "$found" "$OUT/$ABI/$lib"
  done
  STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)"
  "$STRIP" --strip-unneeded "$OUT/$ABI/"*.so
  ls -l "$OUT/$ABI"
done
echo "Done. Now: ./gradlew assembleRelease -PonnxFromSource=true"
