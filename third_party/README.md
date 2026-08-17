# Third-party media dependencies

本目录定义 Codec Lab 的第三方依赖边界。依赖不通过 Maven 动态下载，也不从用户机器的任意目录读取；源码会放在 `third_party/sources/`，Android 产物会放在 `app/src/main/cpp/third_party/ffmpeg/android/<abi>/` 和 `app/src/main/cpp/third_party/vmaf/android/<abi>/`。

Android Studio 的 Run 默认只校验/使用已发布的预编译库，不会在每次运行时编译 FFmpeg、x264、x265 或 libvmaf。当前源码包内已缓存 arm64-v8a 版本，因此新机器可直接打开工程并安装完整媒体版。

如果交付包不含二进制缓存，可通过 `MEDIA_BUNDLE_URL` 指向内部或供应商发布的 tar.gz（目录结构保持 `app/src/main/cpp/third_party/ffmpeg/android/<abi>/...`）：

```bash
MEDIA_BUNDLE_URL=https://your-release/media-bundle.tar.gz ./gradlew :app:assembleDebug
```

源码仅作为维护/升级依赖的可选工具，手动执行：

```bash
./scripts/prepare_dependencies.sh
```

该脚本使用 `third_party/DEPENDENCIES.lock` 中的固定 tag/commit，所有下载目标都位于项目内 `third_party/sources/`。它不参与日常 Run。

许可证提醒：FFmpeg 开启 libx264/libx265 后通常按 GPL 组合分发；libvmaf、x264、x265 也各有许可证与 NOTICE 要求。源码包包含构建入口，不代表替用户完成发行授权。
