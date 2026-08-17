#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$PROJECT_DIR/third_party/DEPENDENCIES.lock"
SOURCE_ROOT="$PROJECT_DIR/third_party/sources"
mkdir -p "$SOURCE_ROOT"

while IFS='|' read -r name ref url directory; do
  [[ -z "${name:-}" || "${name:0:1}" == "#" ]] && continue
  target="$SOURCE_ROOT/$directory"
  if [[ -d "$target/.git" ]]; then
    git -C "$target" fetch --depth 1 origin "$ref" || git -C "$target" fetch --depth 1 origin
    git -C "$target" checkout --detach FETCH_HEAD
  else
    git clone --depth 1 --branch "$ref" "$url" "$target"
  fi
done < "$MANIFEST"

echo "Third-party sources are ready under: $SOURCE_ROOT"
echo "Next: read docs/BUILDING.md and build per-ABI static libraries into app/src/main/cpp/third_party/ffmpeg/android/"

