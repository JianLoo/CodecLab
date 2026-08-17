#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${FFMPEG_SRC:-$PROJECT_DIR/third_party/sources/FFmpeg}"
if [[ -d "$TARGET/.git" ]]; then
  git -C "$TARGET" fetch --depth 1 origin release/7.1
  git -C "$TARGET" checkout FETCH_HEAD
else
  git clone --depth 1 --branch release/7.1 https://github.com/FFmpeg/FFmpeg.git "$TARGET"
fi
echo "FFmpeg source ready at $TARGET"
