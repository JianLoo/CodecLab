#!/usr/bin/env bash
set -euo pipefail

# Builds FFmpeg as static PIC libraries for codec-core. GPL codecs and libvmaf
# are enabled only when their prefixes are supplied. The resulting archives are
# linked into one public libcodec_core.so by CMake.

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/$NDK_VERSION}"
API="${ANDROID_API:-26}"
FFMPEG_SRC="${FFMPEG_SRC:-$PROJECT_DIR/third_party/sources/FFmpeg}"
OUTPUT_ROOT="$PROJECT_DIR/app/src/main/cpp/third_party/ffmpeg/android"
ABIS="${ABIS:-arm64-v8a x86_64}"

if [[ ! -x "$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang" && \
      ! -x "$NDK_DIR/toolchains/llvm/prebuilt/darwin-arm64/bin/clang" ]]; then
  echo "Android NDK not found: $NDK_DIR" >&2
  exit 1
fi
if [[ ! -x "$FFMPEG_SRC/configure" ]]; then
  echo "FFmpeg source not found at $FFMPEG_SRC" >&2
  echo "Example: git clone --depth 1 --branch release/7.1 https://github.com/FFmpeg/FFmpeg.git '$FFMPEG_SRC'" >&2
  exit 1
fi

HOST_TAG="darwin-$(uname -m)"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64"; fi

for ABI in $ABIS; do
  case "$ABI" in
    arm64-v8a) ARCH=aarch64; TARGET=aarch64-linux-android; CPU=armv8-a ;;
    x86_64) ARCH=x86_64; TARGET=x86_64-linux-android; CPU=x86-64 ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
  esac

  PREFIX="$OUTPUT_ROOT/$ABI"
  BUILD_DIR="$PROJECT_DIR/work/ffmpeg-build-$ABI"
  rm -rf "$BUILD_DIR"
  mkdir -p "$BUILD_DIR" "$PREFIX"

  EXTRA_CONFIG=()
  ENCODERS=""
  FILTERS="scale,format"
  EXTRA_CFLAGS="-fPIC"
  EXTRA_LDFLAGS=""
  PKG_CONFIG_PATH_VALUE=""
  for DEP in x264 x265 vmaf; do
    VAR_NAME="$(printf '%s_PREFIX' "$DEP" | tr '[:lower:]' '[:upper:]')"
    DEP_PREFIX="${!VAR_NAME:-}"
    if [[ -n "$DEP_PREFIX" ]]; then
      EXTRA_CFLAGS+=" -I$DEP_PREFIX/include"
      EXTRA_LDFLAGS+=" -L$DEP_PREFIX/lib"
      EXTRA_LDFLAGS+=" -L$DEP_PREFIX/lib/pkgconfig"
      PKG_CONFIG_PATH_VALUE+="${PKG_CONFIG_PATH_VALUE:+:}$DEP_PREFIX/lib/pkgconfig"
      if [[ "$DEP" == vmaf ]]; then
        EXTRA_CONFIG+=(--enable-libvmaf --enable-filter=libvmaf)
        FILTERS+=",libvmaf"
      else
        EXTRA_CONFIG+=(--enable-lib"$DEP")
        ENCODERS+="${ENCODERS:+,}lib$DEP"
      fi
    fi
  done

  pushd "$BUILD_DIR" >/dev/null
  PKG_CONFIG_PATH="$PKG_CONFIG_PATH_VALUE" "$FFMPEG_SRC/configure" \
    --prefix="$PREFIX" \
    --target-os=android --arch="$ARCH" --cpu="$CPU" \
    --cc="$TOOLCHAIN/bin/$TARGET$API-clang" \
    --cxx="$TOOLCHAIN/bin/$TARGET$API-clang++" \
    --ar="$TOOLCHAIN/bin/llvm-ar" --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
    --strip="$TOOLCHAIN/bin/llvm-strip" --nm="$TOOLCHAIN/bin/llvm-nm" \
    --enable-cross-compile --enable-pic --enable-static --disable-shared \
    --disable-programs --disable-doc --disable-debug --disable-avdevice \
    --disable-network --disable-jni --enable-gpl \
    --disable-asm \
    --disable-everything \
    --enable-decoder=h264,hevc \
    --enable-parser=h264,hevc \
    --enable-encoder="$ENCODERS" \
    --enable-demuxer=mov,matroska \
    --enable-muxer=h264,hevc,mp4 \
    --enable-protocol=file --enable-filter="$FILTERS" \
    --disable-postproc --disable-swresample \
    --extra-cflags="$EXTRA_CFLAGS" --extra-ldflags="$EXTRA_LDFLAGS -lc++_shared -lm" \
    "${EXTRA_CONFIG[@]}"
  make -j"$(sysctl -n hw.logicalcpu)"
  make install
  popd >/dev/null
done

echo "Installed FFmpeg prebuilts to $OUTPUT_ROOT"
