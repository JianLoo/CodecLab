#!/usr/bin/env bash
set -euo pipefail

# Runtime build path: use already-published/prebuilt Android archives.  The
# repository may also carry a checked-in cache under app/src/main/cpp/... so
# Android Studio can build without a compiler toolchain for FFmpeg itself.
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT_ROOT="$PROJECT_DIR/app/src/main/cpp/third_party/ffmpeg/android"
ABIS="${ABIS:-arm64-v8a}"
MEDIA_BUNDLE_URL="${MEDIA_BUNDLE_URL:-}"
MEDIA_BUNDLE_SHA256="${MEDIA_BUNDLE_SHA256:-}"

libraries=(libavcodec.a libavformat.a libavfilter.a libavutil.a libswscale.a libx264.a libx265.a)
vmaf_library=libvmaf.a
is_ready() {
  local abi="$1" lib
  for lib in "${libraries[@]}"; do
    [[ -s "$OUTPUT_ROOT/$abi/lib/$lib" ]] || return 1
  done
  [[ -f "$OUTPUT_ROOT/$abi/include/libavcodec/avcodec.h" ]]
}

vmaf_ready() {
  local abi="$1"
  [[ -s "$PROJECT_DIR/app/src/main/cpp/third_party/vmaf/android/$abi/lib/$vmaf_library" ]] || return 1
  [[ -f "$PROJECT_DIR/app/src/main/cpp/third_party/vmaf/android/$abi/include/libvmaf/libvmaf.h" ]]
}

missing=()
for abi in $ABIS; do
  is_ready "$abi" || missing+=("$abi")
done

if ((${#missing[@]} == 0)); then
  all_vmaf_ready=1
  for abi in $ABIS; do
    vmaf_ready "$abi" || all_vmaf_ready=0
  done
  if ((all_vmaf_ready)); then
    echo "Prebuilt media libraries and libvmaf are ready: $ABIS"
    exit 0
  fi
fi

if [[ -n "$MEDIA_BUNDLE_URL" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  archive="$tmp/media-bundle.tar.gz"
  echo "Downloading published media bundle: $MEDIA_BUNDLE_URL"
  curl --fail --location --retry 3 --connect-timeout 15 -o "$archive" "$MEDIA_BUNDLE_URL"
  if [[ -n "$MEDIA_BUNDLE_SHA256" ]]; then
    echo "$MEDIA_BUNDLE_SHA256  $archive" | shasum -a 256 -c -
  fi
  tar -xzf "$archive" -C "$PROJECT_DIR"
fi

for abi in $ABIS; do
  is_ready "$abi" || {
    echo "Missing published media libraries for ABI '$abi'." >&2
    echo "Place the vendor archive under app/src/main/cpp/third_party/ffmpeg/android or set MEDIA_BUNDLE_URL." >&2
    echo "Source compilation is opt-in only: MEDIA_BUILD_FROM_SOURCE=1 ./scripts/build_media_android.sh" >&2
    exit 1
  }
  vmaf_ready "$abi" || {
    echo "Missing bundled libvmaf for ABI '$abi'." >&2
    echo "Build it with ./scripts/build_vmaf_android.sh or provide a matching archive." >&2
    exit 1
  }
done

echo "Published/prebuilt media libraries are ready: $ABIS"
