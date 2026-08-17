#include "backend.h"

namespace codec_core {

bool ffmpeg_available() { return false; }
bool vmaf_available() { return false; }
std::string ffmpeg_version() { return "not linked"; }

std::string probe_with_ffmpeg(const std::string&) {
    return R"({"ok":false,"error":"FFmpeg prebuilts are not linked. Run scripts/build_ffmpeg_android.sh, then rebuild the app."})";
}

std::string transcode_with_ffmpeg(const std::string&, const Progress& progress) {
    progress(100, "缺少 FFmpeg 运行库");
    return R"({"ok":false,"error":"This build contains the portable codec-core ABI and UI, but no FFmpeg binaries. Build/link FFmpeg with libx264, libx265 and libvmaf as described in docs/BUILDING.md."})";
}

std::string decode_yuv_with_ffmpeg(const std::string&, const Progress& progress) {
    if (progress) progress(100, "缺少 FFmpeg 软件解码库");
    return R"({"ok":false,"error":"FFmpeg software decoder is not linked"})";
}

std::string encode_yuv_with_x26x(const std::string&, const Progress& progress) {
    if (progress) progress(100, "缺少 x264/x265 软件编码库");
    return R"({"ok":false,"error":"x264/x265 software encoder is not linked"})";
}

std::string metrics_with_ffmpeg(const std::string&, const std::string&, int, int, int,
                                const std::string&, const Progress& progress) {
    if (progress) progress(100, "缺少 FFmpeg 质量评估库");
    return R"({"frames":0,"psnr":null,"vmaf":null,"vmafAvailable":false,"error":"FFmpeg is not linked"})";
}

std::string metrics_yuv_with_ffmpeg(const std::string&, const std::string&,
                                    const std::string&, int, int, int,
                                    const std::string&, const Progress& progress) {
    if (progress) progress(100, "缺少 FFmpeg 质量评估库");
    return R"({"frames":0,"psnr":null,"vmaf":null,"vmafAvailable":false,"error":"FFmpeg is not linked"})";
}

}  // namespace codec_core
