#pragma once

#include <functional>
#include <string>

namespace codec_core {

using Progress = std::function<void(int, const std::string&)>;

bool ffmpeg_available();
bool vmaf_available();
std::string ffmpeg_version();
std::string probe_with_ffmpeg(const std::string& input_path);
std::string transcode_with_ffmpeg(const std::string& request_json, const Progress& progress);
std::string decode_yuv_with_ffmpeg(const std::string& request_json, const Progress& progress);
std::string encode_yuv_with_x26x(const std::string& request_json, const Progress& progress);
std::string metrics_with_ffmpeg(const std::string& input_path, const std::string& preview_path,
                                int width, int height, int fps, const std::string& report_path,
                                const Progress& progress);
std::string metrics_yuv_with_ffmpeg(const std::string& reference_yuv_path,
                                    const std::string& pixel_format,
                                    const std::string& preview_path,
                                    int width, int height, int fps,
                                    const std::string& report_path,
                                    const Progress& progress);

}  // namespace codec_core
