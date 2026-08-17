# 架构

```text
Android Activity / File Picker / MediaPlayer
          │                         │
          ├─ hardware decode ─────► Java MediaCodec decoder → YUV
          ├─ hardware encode ─────► Java MediaCodec encoder ← YUV
          │
          └─ software stages ─────► JNI → libcodec_core.so
                                    ┌────────────────────┐
                                    │ FFmpeg demux/decode│
                                    │ scale → YUV         │
                                    │ libx264 / libx265  │
                                    │ PSNR / libvmaf VMAF  │
                                    └────────────────────┘
                                      │ MP4 + Annex-B
```

播放预览是独立于转码的路径：

```text
选择原视频/preview.mp4
          │
          ├─ 优先：MediaExtractor → 真实硬件 MediaCodec → SurfaceView
          │                                      └─ 等比例布局、直接 Surface 合成
          └─ 硬件解码器不可用或配置失败：MediaPlayer → TextureView
```

硬件预览路径不经过 ImageReader、YUV 文件或 Bitmap。当前硬件预览播放器专注视频帧渲染；回退的 MediaPlayer 路径保留系统原有音视频播放能力。

四种组合由 Activity 严格路由：

| 解码 | 编码 | 实现 | 阶段边界 |
|---|---|---|---|
| 软件 | 软件 | FFmpeg → x264/x265 | native 内部帧 |
| 软件 | 硬件 | FFmpeg → YUV 文件 → Java MediaCodec | `intermediate.nv12` |
| 硬件 | 软件 | Java MediaCodec → YUV 文件 → x264/x265 | `intermediate.yuv` |
| 硬件 | 硬件 | Java MediaCodec 直接连接 | 内存中的 YUV |

混合组合的临时 YUV 在任务结束后清理。没有自动降级：硬件选择只枚举真实硬件 codec，软件选择只调用 FFmpeg 与已链接的 x264/x265。

转码硬解不使用 Surface-backed ImageReader：部分 Qualcomm 设备会把 UBWC/tiled 厂商布局包装成 `YUV_420_888`，按普通线性平面读取会产生绿色花屏。当前转码路径改用 `MediaCodec.getOutputImage()` 的线性输出；硬编则在 `start()` 后读取真实输入颜色格式，并处理 stride/slice-height 对齐。播放解码仍直接输出 SurfaceView，以保持零拷贝渲染。

公共头文件是 `app/src/main/cpp/include/codec_core/codec_core.h`。ABI 使用字符串 JSON，避免让 JNI 或未来 Swift 层依赖 FFmpeg 的结构体与版本。批量调度、文件权限和播放器属于平台 UI；单文件探测、转码、裸流/预览输出和客观评价属于核心库。

质量评价不再重新解码原输入作为参考。四种软硬组合都保存编码器实际收到的紧凑 YUV 帧，并与 `preview.mp4` 解码后的输出帧按顺序比较，因此 PSNR/VMAF 只反映编码与编码结果解码造成的差异，不混入原始解码、旋转、缩放或抽帧方式的差异。报告通过 `metricBasis=pre_encode_yuv_vs_decoded_output` 标识该基准。VMAF 使用独立的 libvmaf 静态库和内置 `vmaf_v0.6.1` 模型，移动端默认每 2 帧采样一次；PSNR 按可配对输出序列全帧计算。批量完成后 UI 对每个成功文件的 PSNR/VMAF 做算术平均。

## iOS 迁移

1. 将 `codec_core.cpp`、`backend.h` 和 FFmpeg 后端组成静态库或 XCFramework。
2. 替换 Android JNI bridge 为很薄的 Objective-C++/Swift C bridge。
3. 软件编解码路径原样复用；Android 的 Java MediaCodec 后端替换为 iOS VideoToolbox decoder/encoder，配置仍沿用 `hardware`。
4. SwiftUI/UIKit 负责 Photos/Files 导入、沙盒路径、任务列表和 AVPlayer。

核心 C ABI 应保持向后兼容；新增配置只应增加 JSON 字段，旧调用方可以忽略新响应字段。
