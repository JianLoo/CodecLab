#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_ROOT="$PROJECT_DIR/third_party/sources"
PREFIX_ROOT="$PROJECT_DIR/work/deps"
ABIS="${ABIS:-arm64-v8a x86_64}"
ANDROID_API="${ANDROID_API:-26}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/$NDK_VERSION}"
CMAKE_BIN="${CMAKE_BIN:-$SDK_DIR/cmake/3.22.1/bin/cmake}"

if [[ ! -d "$SOURCE_ROOT/FFmpeg" || ! -d "$SOURCE_ROOT/x264" || ! -d "$SOURCE_ROOT/x265" || ! -d "$SOURCE_ROOT/libvmaf" ]]; then
  echo "Missing dependency source. Run ./scripts/prepare_dependencies.sh first." >&2
  exit 1
fi
if [[ ! -x "$CMAKE_BIN" ]]; then
  CMAKE_BIN="$(command -v cmake || true)"
fi
[[ -x "$CMAKE_BIN" ]] || { echo "CMake is required; install Android SDK CMake 3.22.1" >&2; exit 1; }

HOST_TAG="darwin-$(uname -m)"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
[[ -d "$TOOLCHAIN" ]] || TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64"
[[ -d "$TOOLCHAIN" ]] || { echo "NDK toolchain not found: $NDK_DIR" >&2; exit 1; }

for ABI in $ABIS; do
  case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android; CMAKE_ARCH=arm64-v8a ;;
    x86_64) TARGET=x86_64-linux-android; CMAKE_ARCH=x86_64 ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
  esac
  PREFIX="$PREFIX_ROOT/$ABI"
  mkdir -p "$PREFIX"
  export CC="$TOOLCHAIN/bin/$TARGET$ANDROID_API-clang"
  export CXX="$TOOLCHAIN/bin/$TARGET$ANDROID_API-clang++"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  export CFLAGS="-fPIC"
  export CXXFLAGS="-fPIC"

  echo "[$ABI] building x264"
  pushd "$SOURCE_ROOT/x264" >/dev/null
  X264_ASM_ARGS=()
  # x264's x86 back-end requires NASM/YASM. Keep the Android build portable:
  # when the host does not provide an assembler, build the scalar C path.
  if ! command -v nasm >/dev/null 2>&1 && ! command -v yasm >/dev/null 2>&1; then
    X264_ASM_ARGS+=(--disable-asm)
    echo "[$ABI] NASM/YASM not found; building x264 without assembly"
  fi
  ./configure --host="$TARGET" --prefix="$PREFIX/x264" --enable-static --disable-cli --enable-pic "${X264_ASM_ARGS[@]}"
  make -j"$(sysctl -n hw.logicalcpu)" && make install && make distclean
  popd >/dev/null

  echo "[$ABI] building x265"
  rm -rf "$PROJECT_DIR/work/x265-$ABI"
  "$CMAKE_BIN" -S "$SOURCE_ROOT/x265/source" -B "$PROJECT_DIR/work/x265-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$CMAKE_ARCH" -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX/x265" -DENABLE_SHARED=OFF -DENABLE_CLI=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DENABLE_ASSEMBLY=OFF -DHIGH_BIT_DEPTH=OFF -DMAIN12=OFF -DNUMA_ROOT_DIR=OFF
  "$CMAKE_BIN" --build "$PROJECT_DIR/work/x265-$ABI" -j"$(sysctl -n hw.logicalcpu)"
  "$CMAKE_BIN" --install "$PROJECT_DIR/work/x265-$ABI"
  mkdir -p "$PREFIX/x265/lib/pkgconfig"
  cat > "$PREFIX/x265/lib/pkgconfig/x265.pc" <<EOF
prefix=$PREFIX/x265
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include
Name: x265
Description: H.265/HEVC encoder library
Version: 4.1
Libs: -L\${libdir} -lx265 -lc++_shared -lm
Cflags: -I\${includedir}
EOF

  X264_PREFIX="$PREFIX/x264" X265_PREFIX="$PREFIX/x265" \
    ABIS="$ABI" FFMPEG_SRC="$SOURCE_ROOT/FFmpeg" ./scripts/build_ffmpeg_android.sh

  # Keep every static archive needed by codec-core inside the Android project.
  # This makes Android Studio builds independent from work/deps after this task
  # has completed once.
  cp "$PREFIX/x264/lib/libx264.a" "$PROJECT_DIR/app/src/main/cpp/third_party/ffmpeg/android/$ABI/lib/"
  cp "$PREFIX/x265/lib/libx265.a" "$PROJECT_DIR/app/src/main/cpp/third_party/ffmpeg/android/$ABI/lib/"
  mkdir -p "$PROJECT_DIR/app/src/main/cpp/third_party/ffmpeg/android/$ABI/include"
  cp -R "$PREFIX/x264/include/." "$PROJECT_DIR/app/src/main/cpp/third_party/ffmpeg/android/$ABI/include/" 2>/dev/null || true
  cp -R "$PREFIX/x265/include/." "$PROJECT_DIR/app/src/main/cpp/third_party/ffmpeg/android/$ABI/include/" 2>/dev/null || true
done

echo "Media libraries are ready under app/src/main/cpp/third_party/ffmpeg/android/"
