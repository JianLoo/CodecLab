# 构建完整媒体版本

## 1. 依赖边界

最终 App 对外只加载 `libcodec_core.so`；硬件编解码由 Java 层直接调用 Android `MediaCodec`。FFmpeg、x264、x265 和 libvmaf 仅用于 native 软件解码、软件编码和质量评估，并以 `-fPIC` 静态库链接进该 SO。

源码包已经包含可直接链接的 arm64-v8a FFmpeg/x264/x265/libvmaf 静态库缓存。Run/assembleDebug 会执行 `scripts/fetch_media_prebuilt.sh`，只做完整性校验；不会重新编译第三方源码。

如果使用供应商发布包：

```bash
MEDIA_BUNDLE_URL=https://your-release/media-bundle.tar.gz \
  ./gradlew :app:assembleDebug
```

仅在维护依赖时才需要下载源码：

```bash
./scripts/prepare_dependencies.sh
```

脚本会将固定版本源码下载到当前工程的 `third_party/sources/`。如果 Android Studio 只用于安装/查看 UI，可以跳过这一步。

目录约定：

```text
app/src/main/cpp/third_party/ffmpeg/android/
  arm64-v8a/include/...
  arm64-v8a/lib/libavcodec.a ...
  x86_64/include/...
  x86_64/lib/libavcodec.a ...
```

## 2. 编译 FFmpeg

```bash
./scripts/bootstrap_ffmpeg_source.sh

# 如果完整构建依赖已由你编译到项目内前缀，设置以下变量。也可以把
# 它们放在 work/deps/<abi>/ 下再传入绝对路径；关键是不要依赖源码包之外
# 的隐式目录。
ABIS="arm64-v8a" \
X264_PREFIX=/absolute/android-arm64/x264 \
X265_PREFIX=/absolute/android-arm64/x265 \
VMAF_PREFIX=/absolute/android-arm64/vmaf \
./scripts/build_ffmpeg_android.sh
```

`build_ffmpeg_android.sh` 默认从 `work/FFmpeg` 读取 FFmpeg 源码；如果使用
`prepare_dependencies.sh`，可这样指定：

```bash
FFMPEG_SRC="$PWD/third_party/sources/FFmpeg" ./scripts/build_ffmpeg_android.sh
```

也可以显式运行项目内的组合脚本，它会用工程内源码构建 x264/x265，再构建并链接 FFmpeg（不属于默认 Run 路径）：

```bash
./scripts/build_media_android.sh
```

Android Studio 的 `Run`/`assembleDebug` 已自动依赖预编译校验任务，然后编译并链接 `libcodec_core.so`。后续运行由 Gradle 增量机制跳过未变化内容。

VMAF 静态库和头文件位于 `app/src/main/cpp/third_party/vmaf/android/arm64-v8a/`，默认模型已编译进 `libvmaf.a`。需要重建时运行 `python3 -m pip install --user meson ninja && ./scripts/build_vmaf_android.sh`。

注意：x264/x265 使发行物受 GPL 约束；libvmaf 的 Android 构建还需要 Meson/Ninja 和 C++ 运行库。正式分发前需完成许可证审核。生产构建建议锁定源码 commit、保存编译参数和 NOTICE。

## 3. 构建 APK

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew clean assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。Android Studio 直接打开本项目时，Gradle 会自动使用项目内的 wrapper；无需预装 Gradle。

## 4. 能力说明

- 解码和编码均为显式模式，不提供改变实现的自动降级；硬件模式由 Java `MediaCodec` 管线处理，且只接受真实硬件 codec；硬件/硬件组合不经过 FFmpeg `mediacodec` wrapper。
- 软件路径只使用 FFmpeg 视频解码和导入的 x264/x265 编码器；FFmpeg native transcode 收到硬件模式请求时会拒绝，避免误走旧 wrapper。
- 转码硬解使用 `MediaCodec` 的线性 ByteBuffer/Image 输出，再按编码器启动后协商到的真实颜色格式、stride 和 slice-height 写入硬件编码器，输出 MP4 和 Annex-B 裸流。播放路径仍使用 SurfaceView 直接渲染。
- 混合组合使用任务目录临时 YUV 文件连接两个阶段，成功或失败后都会清理；阶段失败会原样返回，不切换到另一模式。
- 播放预览优先使用 `MediaExtractor + MediaCodec + SurfaceView`，视频帧直接由硬件解码器输出到 Surface；硬件播放不可用时回退到原有 `MediaPlayer + TextureView`。
- 输出裸流采用 Annex B H.264/HEVC muxer；另行输出 MP4 仅用于 App 播放和质量回读。
- PSNR 使用编码器实际收到的预编码 YUV 与编码后 MP4 解码帧逐帧计算，报告中的 `metricBasis` 为 `pre_encode_yuv_vs_decoded_output`，不会把原视频解码或缩放差异计入编码损失。
- VMAF 通过独立链接的 libvmaf C API 计算，使用内置 `vmaf_v0.6.1` 模型；移动端每 2 帧采样一次，JSON 中输出有效 VMAF。
- 批量平均指标按每个成功计算文件一个样本做算术平均，同时显示有效文件数和统计帧数。
- 为保证批量稳定性，当前 UI 串行执行任务。C ABI 本身未暴露 Android UI 类型。
