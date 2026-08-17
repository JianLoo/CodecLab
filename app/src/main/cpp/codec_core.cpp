#include "codec_core/codec_core.h"

#include "backend.h"

#include <atomic>
#include <string>

namespace {

std::atomic_bool g_cancelled{false};
thread_local std::string g_result;

std::string quote(const std::string& text) {
    std::string out;
    out.reserve(text.size() + 8);
    for (char c : text) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

const char* save(std::string value) {
    g_result = std::move(value);
    return g_result.c_str();
}

}  // namespace

extern "C" const char* codec_core_version(void) {
    return "0.1.0";
}

extern "C" const char* codec_core_capabilities(void) {
    return save(std::string("{\"version\":\"") + codec_core_version()
            + "\",\"ffmpeg\":" + (codec_core::ffmpeg_available() ? "true" : "false")
            + ",\"vmaf\":" + (codec_core::vmaf_available() ? "true" : "false")
            + ",\"ffmpegVersion\":\"" + quote(codec_core::ffmpeg_version())
            + "\",\"codecs\":[\"h264\",\"hevc\"],"
              "\"decodeModes\":[\"auto\",\"software\",\"hardware\"],"
              "\"encodeModes\":[\"software\",\"hardware\"]}");
}

extern "C" const char* codec_core_probe(const char* input_path) {
    if (input_path == nullptr || *input_path == '\0') {
        return save("{\"ok\":false,\"error\":\"input path is empty\"}");
    }
    return save(codec_core::probe_with_ffmpeg(input_path));
}

extern "C" const char* codec_core_transcode(
        const char* request_json,
        codec_core_progress_cb progress,
        void* opaque) {
    g_cancelled.store(false);
    if (request_json == nullptr || *request_json == '\0') {
        return save("{\"ok\":false,\"error\":\"request JSON is empty\"}");
    }
    codec_core::Progress callback = [progress, opaque](int percent, const std::string& stage) {
        if (progress != nullptr) progress(percent, stage.c_str(), opaque);
    };
    callback(0, "准备任务");
    if (g_cancelled.load()) return save("{\"ok\":false,\"cancelled\":true}");
    return save(codec_core::transcode_with_ffmpeg(request_json, callback));
}

extern "C" void codec_core_cancel(void) {
    g_cancelled.store(true);
}

