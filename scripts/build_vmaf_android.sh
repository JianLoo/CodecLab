#!/usr/bin/env bash
set -euo pipefail

# Builds the bundled libvmaf source as an ARM64 Android static library with
# the vmaf_v0.6.1 model compiled into the archive. Meson and Ninja are the only
# additional host tools required; FFmpeg itself is not rebuilt by this script.
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/$NDK_VERSION}"
API="${ANDROID_API:-26}"
ABI="${ABI:-arm64-v8a}"
VMAF_SRC="${VMAF_SRC:-$PROJECT_DIR/third_party/sources/libvmaf/libvmaf}"
OUT="$PROJECT_DIR/app/src/main/cpp/third_party/vmaf/android/$ABI"
BUILD="$PROJECT_DIR/work/vmaf-$ABI"

command -v meson >/dev/null || { echo "meson is required (python3 -m pip install --user meson)" >&2; exit 1; }
command -v ninja >/dev/null || { echo "ninja is required (python3 -m pip install --user ninja)" >&2; exit 1; }

case "$ABI" in
  arm64-v8a) TARGET=aarch64-linux-android; CPU_FAMILY=aarch64; CPU=arm64; ;;
  *) echo "Only arm64-v8a is currently packaged" >&2; exit 2 ;;
esac

HOST_TAG="darwin-$(uname -m)"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
[[ -d "$TOOLCHAIN" ]] || TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64"
mkdir -p "$PROJECT_DIR/work/vmaf-cross"
CROSS="$PROJECT_DIR/work/vmaf-cross/$ABI.ini"
cat > "$CROSS" <<EOF
[binaries]
c = '$TOOLCHAIN/bin/${TARGET}${API}-clang'
cpp = '$TOOLCHAIN/bin/${TARGET}${API}-clang++'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'

[host_machine]
system = 'android'
cpu_family = '$CPU_FAMILY'
cpu = '$CPU'
endian = 'little'

[built-in options]
c_args = ['-fPIC']
cpp_args = ['-fPIC']
EOF

meson setup "$BUILD" "$VMAF_SRC" --cross-file "$CROSS" --wipe \
  -Ddefault_library=static -Dbuilt_in_models=true -Denable_tests=false \
  -Denable_docs=false -Denable_asm=true -Denable_float=false -Dbuildtype=release
meson compile -C "$BUILD"
mkdir -p "$OUT/lib" "$OUT/include"
cp "$BUILD/src/libvmaf.a" "$OUT/lib/libvmaf.a"
rm -rf "$OUT/include/libvmaf"
cp -R "$VMAF_SRC/include/libvmaf" "$OUT/include/libvmaf"
echo "Installed libvmaf to $OUT"
