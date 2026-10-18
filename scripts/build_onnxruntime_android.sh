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
# F-Droid's build server can hand over the NDK path with "$ANDROID_HOME" still unexpanded.
expand() { local p="$1"; p="${p/#\$\{ANDROID_HOME\}/$SDK}"; p="${p/#\$ANDROID_HOME/$SDK}"; p="${p/#\~/$HOME}"; printf '%s' "$p"; }
NDK=""
for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK:-}" "${ANDROID_NDK_ROOT:-}" "$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"; do
  c="$(expand "$c")"
  if [ -n "$c" ] && [ -d "$c" ]; then NDK="$c"; break; fi
done
[ -n "$NDK" ] || { echo "No Android NDK found (ANDROID_NDK_HOME='${ANDROID_NDK_HOME:-}', SDK '$SDK'); set ANDROID_NDK_HOME" >&2; exit 1; }
echo "Using the NDK at $NDK"
SRC="$ROOT/build/onnxruntime-src"
OUT="$ROOT/build/onnxruntime-jni"
# ONNX Runtime 1.20 downloads Eigen as a GitLab archive, which GitLab often refuses to build
# servers; the same Eigen commit is fetched with git instead (EIGEN_SRC can point at a copy).
EIGEN_COMMIT=e7248b26a1ed53fa030c5c459f7ea095dfd276ac
EIGEN="${EIGEN_SRC:-$ROOT/build/eigen}"

# The same output on every machine.
export SOURCE_DATE_EPOCH=1700000000 TZ=UTC

if [ ! -f "$SRC/tools/ci_build/build.py" ]; then
  rm -rf "$SRC"
  git clone --depth 1 --branch "v$ORT_VERSION" --recurse-submodules --shallow-submodules \
    https://github.com/microsoft/onnxruntime.git "$SRC"
fi

if [ ! -f "$EIGEN/Eigen/Core" ]; then
  rm -rf "$EIGEN" && git init -q "$EIGEN"
  if ! git -C "$EIGEN" fetch -q --depth 1 https://gitlab.com/libeigen/eigen.git "$EIGEN_COMMIT"; then
    git -C "$EIGEN" fetch -q --depth 200 https://gitlab.com/libeigen/eigen.git 3.4
  fi
  git -C "$EIGEN" checkout -q "$EIGEN_COMMIT"
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
    --use_preinstalled_eigen --eigen_path "$EIGEN" \
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
