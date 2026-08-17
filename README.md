# CodecLab

CodecLab 是一个面向 Android 的视频转码与客观画质分析实验室。它把 Android 平台能力、FFmpeg/x264/x265 软件媒体栈、MediaCodec 硬件媒体栈和 libvmaf 质量评价组合在一个可复现的工程里。

项目的重点不是“调用一个转码命令”，而是把每一步的实现边界和评价基准说清楚：用户选择软件还是硬件路径，系统就严格执行对应路径；混合模式通过显式的 YUV 阶段连接；PSNR/VMAF 使用编码器实际收到的预编码帧作为参考，避免把原视频解码、缩放或抽帧差异误算成编码损失。

## 能力概览

- Android Java UI：批量导入、任务队列、任务状态、结果列表和视频预览。
- Native 核心：单文件探测、软件解码、软件编码、裸流输出、MP4 预览和客观质量评价。
- 编码格式：H.264 / H.265；输出 Annex-B `.h264` / `.hevc` 和可播放 `preview.mp4`。
- 四种显式编解码组合：软件/软件、软件/硬件、硬件/软件、硬件/硬件。
- 硬件转码：Java `MediaCodec`，协商真实颜色格式、stride 和 slice-height，不静默切换到其他实现。
- 硬件播放：优先 `MediaExtractor + MediaCodec + SurfaceView` 直接渲染，失败时回退到 `MediaPlayer + TextureView`。
- 质量指标：逐帧 PSNR 与内置 `vmaf_v0.6.1` 模型的 VMAF；移动端默认每 2 帧采样 VMAF。
- 跨平台边界：公共 C ABI 使用字符串 JSON，不让 Android JNI 或未来 iOS bridge 依赖 FFmpeg 结构体。

## 架构总览

```text
Android Activity / File Picker / 播放器
        │
        ├─ 硬件解码 ── Java MediaCodec ──┐
        ├─ 硬件编码 ── Java MediaCodec ◄─┤  YUV 阶段边界
        │                                │
        └─ JNI ── libcodec_core.so ──────┘
                    │
                    ├─ FFmpeg demux / decode / scale
                    ├─ x264 / x265 software encode
                    ├─ Annex-B + MP4 output
                    └─ PSNR + libvmaf quality metrics
```

核心实现位于 `app/src/main/cpp/`：

| 模块 | 责任 |
| --- | --- |
| `codec_core.cpp` | JSON 请求、单文件探测、任务状态、输出编排和指标计算 |
| `ffmpeg_backend.cpp` | FFmpeg 解码、像素格式处理、x264/x265 编码和封装 |
| `jni_bridge.cpp` | Java 字符串与 C ABI 之间的薄桥接 |
| `MediaCodecTranscoder.java` | Android 硬解/硬编、颜色格式和对齐处理 |
| `HardwareVideoPlayer.java` | SurfaceView 硬件播放及 MediaPlayer 回退 |
| `NativeCodecEngine.java` | Native 库加载和 JSON API 封装 |

完整设计说明见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，其中包含播放链路、四种组合、异常边界和 iOS 迁移策略。

## 四种编解码组合

| 解码 | 编码 | 主实现 | 阶段边界 |
| --- | --- | --- | --- |
| 软件 | 软件 | FFmpeg → x264/x265 | native 内部帧 |
| 软件 | 硬件 | FFmpeg → 临时 YUV → MediaCodec | `intermediate.nv12` |
| 硬件 | 软件 | MediaCodec → 临时 YUV → x264/x265 | `intermediate.yuv` |
| 硬件 | 硬件 | MediaCodec 直接连接 | 内存中的 YUV |

硬件选择只枚举真实硬件 codec，软件选择只调用 FFmpeg 和已链接的 x264/x265；任一路径失败都会返回可诊断错误，不自动换成另一种实现。

## 质量评价基准

每个任务会保存编码器实际收到的紧凑 YUV 帧，再把编码后 `preview.mp4` 解码成输出帧，按帧序配对计算：

```text
pre_encode_yuv  ──► encoder ──► preview.mp4 ──► decoder
       │                                      │
       └──────────── PSNR / VMAF ────────────┘
```

报告中的 `metricBasis` 为 `pre_encode_yuv_vs_decoded_output`。这意味着指标只描述编码与编码结果解码带来的差异，不把原始解码、旋转、缩放或抽帧方式的变化混进来。批量任务完成后，UI 对成功文件的指标做算术平均，同时展示有效文件数和统计帧数。

## 在 Android Studio 中运行

### 环境

- Android Studio，Android SDK Platform 35
- JDK 17
- NDK `27.2.12479018`
- CMake `3.22.1`
- 仅支持 `arm64-v8a` 的完整媒体构建

### 快速构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。Android Studio 直接打开仓库根目录即可完成 Gradle Sync。为了保持 GitHub 仓库可维护，仓库不提交本机生成的媒体库、CMake 中间目录和第三方源码缓存；在包含供应商媒体包的本地工作区中，默认 Run 路径只校验媒体包，不会每次重新编译第三方源码。

### 完整媒体依赖

FFmpeg、x264、x265 和 libvmaf 的源码版本记录在 `third_party/DEPENDENCIES.lock`。维护依赖时：

```bash
./scripts/prepare_dependencies.sh
./scripts/build_media_android.sh
```

也可以通过 `MEDIA_BUNDLE_URL` 指向供应商发布的 Android 媒体包。构建脚本要求固定的 ABI 目录结构，避免依赖用户机器上的隐式路径。更多说明见 [`docs/BUILDING.md`](docs/BUILDING.md)。当前仓库只保留依赖锁定文件和构建入口，不包含本机预编译二进制。

## 工程取舍与限制

- 默认只发布 ARM64；如果要支持 x86_64，需要匹配的 FFmpeg/x264/x265/libvmaf bundle 和 ABI 配置。
- 批量任务当前串行执行，优先保证内存占用和任务稳定性。
- VMAF 默认隔帧采样，是移动端耗时与指标覆盖率之间的折中。
- x264/x265 与 FFmpeg 的组合可能触发 GPL 义务；正式分发前必须完成许可证、NOTICE 和模型授权审核。
- `build/`、`.gradle/`、`.cxx/`、`work/`、本地 SDK 配置和第三方源码缓存不属于版本化源代码，已由 `.gitignore` 排除。

## 目录结构

```text
app/src/main/java/             Android UI、MediaCodec、播放和 Native API
app/src/main/cpp/              C++ 核心、JNI bridge、FFmpeg backend
app/src/main/res/              布局、主题、图标和文件共享配置
docs/                          架构与构建文档
scripts/                       依赖准备、媒体包校验和源码构建脚本
third_party/                   依赖版本锁定与许可证边界
```

## License

CodecLab 的应用层代码可按项目需要继续补充许可证。FFmpeg、x264、x265、libvmaf 及其模型遵循各自上游许可证；发布二进制前请单独完成合规审核。
