# Android FFmpeg link directory

This directory contains the published/prebuilt Android media archives used by the default Run path. The current delivery includes arm64-v8a. A vendor bundle may add another ABI using the same layout.

For each ABI, the complete backend expects:

```text
<abi>/include/libavcodec/avcodec.h
<abi>/lib/libavformat.a
<abi>/lib/libavcodec.a
<abi>/lib/libavfilter.a
<abi>/lib/libpostproc.a
<abi>/lib/libavutil.a
<abi>/lib/libswscale.a
<abi>/lib/libswresample.a
```

The build scripts and exact source versions are in the project-level `third_party/` and `scripts/` directories, but are not part of the normal Run path. If these files are absent, `scripts/fetch_media_prebuilt.sh` fails with an actionable message instead of silently producing a diagnostic backend.
