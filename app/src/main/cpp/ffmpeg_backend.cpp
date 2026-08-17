#include "backend.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
#if defined(CODEC_CORE_HAS_VMAF)
#include <libvmaf/libvmaf.h>
#include <libvmaf/model.h>
#include <libvmaf/picture.h>
#endif
}

#include <cmath>
#include <cstring>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

namespace codec_core {
namespace {

std::string escape(const std::string& text) {
    std::string out;
    for (char c : text) {
        if (c == '\\') out += "\\\\";
        else if (c == '"') out += "\\\"";
        else if (c == '\n') out += "\\n";
        else out += c;
    }
    return out;
}

std::string fferr(int value) {
    char buffer[AV_ERROR_MAX_STRING_SIZE]{};
    av_strerror(value, buffer, sizeof(buffer));
    return buffer;
}

std::string json_string(const std::string& json, const char* key, const std::string& fallback = {}) {
    std::regex pattern(std::string("\\\"") + key + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    std::smatch match;
    if (!std::regex_search(json, match, pattern)) return fallback;
    const std::string encoded = match[1].str();
    std::string decoded;
    decoded.reserve(encoded.size());
    for (size_t i = 0; i < encoded.size(); ++i) {
        if (encoded[i] != '\\' || i + 1 >= encoded.size()) {
            decoded += encoded[i];
            continue;
        }
        const char escaped = encoded[++i];
        switch (escaped) {
            case '\\': decoded += '\\'; break;
            case '"': decoded += '"'; break;
            case '/': decoded += '/'; break;
            case 'n': decoded += '\n'; break;
            case 'r': decoded += '\r'; break;
            case 't': decoded += '\t'; break;
            default: decoded += escaped; break;
        }
    }
    return decoded;
}

int json_int(const std::string& json, const char* key, int fallback) {
    std::regex pattern(std::string("\\\"") + key + "\\\"\\s*:\\s*([0-9]+)");
    std::smatch match;
    return std::regex_search(json, match, pattern) ? std::stoi(match[1].str()) : fallback;
}

bool json_bool(const std::string& json, const char* key, bool fallback) {
    std::regex pattern(std::string("\\\"") + key + "\\\"\\s*:\\s*(true|false)");
    std::smatch match;
    return std::regex_search(json, match, pattern) ? match[1].str() == "true" : fallback;
}

class VideoReader {
public:
    ~VideoReader() { reset(); }

    void reset() {
        if (sws_) sws_freeContext(sws_);
        sws_ = nullptr;
        av_frame_free(&decoded_);
        av_packet_free(&packet_);
        avcodec_free_context(&decoder_);
        avformat_close_input(&format_);
        stream_ = nullptr;
        stream_index_ = -1;
        decoder_eof_ = false;
        preserve_all_frames_ = false;
        decoded_count_ = 0;
        output_index_ = 0;
        next_output_seconds_ = 0;
        duration_seconds_ = 0;
        mode_used_.clear();
        codec_name_.clear();
        error_.clear();
    }

    int open(const std::string& path, const std::string& mode, int width, int height,
             int fps, AVPixelFormat target_format, bool preserve_all_frames = false) {
        int rc = avformat_open_input(&format_, path.c_str(), nullptr, nullptr);
        if (rc < 0) { error_ = "avformat_open_input: " + fferr(rc); return rc; }
        if ((rc = avformat_find_stream_info(format_, nullptr)) < 0) { error_ = "avformat_find_stream_info: " + fferr(rc); return rc; }
        stream_index_ = av_find_best_stream(format_, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        if (stream_index_ < 0) { error_ = "av_find_best_stream: " + fferr(stream_index_); return stream_index_; }
        stream_ = format_->streams[stream_index_];
        const AVCodec* codec = find_decoder(stream_->codecpar->codec_id, mode);
        if (!codec) { error_ = "decoder lookup failed"; return AVERROR_DECODER_NOT_FOUND; }
        decoder_ = avcodec_alloc_context3(codec);
        if (!decoder_) { error_ = "decoder allocation failed"; return AVERROR(ENOMEM); }
        if ((rc = avcodec_parameters_to_context(decoder_, stream_->codecpar)) < 0) { error_ = "avcodec_parameters_to_context: " + fferr(rc); return rc; }
        decoder_->pkt_timebase = stream_->time_base;
        if (mode != "hardware") {
            // Let FFmpeg use the available cores. The previous single-thread
            // setting made software decode unnecessarily slow on phones.
            decoder_->thread_count = 0;
            decoder_->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
        }
        rc = avcodec_open2(decoder_, codec, nullptr);
        if (rc < 0) { error_ = "avcodec_open2(" + std::string(codec->name) + "): " + fferr(rc); return rc; }
        mode_used_ = std::string(codec->name).find("_mediacodec") != std::string::npos
                ? "hardware" : "software";
        codec_name_ = codec->name;
        decoded_ = av_frame_alloc();
        packet_ = av_packet_alloc();
        if (!decoded_ || !packet_) return AVERROR(ENOMEM);
        output_width_ = width > 0 ? width : decoder_->width;
        output_height_ = height > 0 ? height : decoder_->height;
        output_fps_ = fps > 0 ? fps : static_cast<int>(std::round(av_q2d(av_guess_frame_rate(format_, stream_, nullptr))));
        if (output_fps_ <= 0) output_fps_ = 30;
        target_format_ = target_format;
        preserve_all_frames_ = preserve_all_frames;
        duration_seconds_ = format_->duration > 0 ? format_->duration / static_cast<double>(AV_TIME_BASE) : 0.0;
        return 0;
    }

    // The caller owns the returned frame. Frames are scaled and timestamped at
    // the requested output cadence; lower requested frame rates drop frames.
    int next(AVFrame** output) {
        *output = nullptr;
        while (true) {
            int rc = avcodec_receive_frame(decoder_, decoded_);
            if (rc == 0) {
                double seconds = decoded_->best_effort_timestamp == AV_NOPTS_VALUE
                        ? decoded_count_ / std::max(1.0, av_q2d(av_guess_frame_rate(format_, stream_, nullptr)))
                        : decoded_->best_effort_timestamp * av_q2d(stream_->time_base);
                ++decoded_count_;
                if (!preserve_all_frames_) {
                    if (seconds + 0.5 / output_fps_ < next_output_seconds_) {
                        av_frame_unref(decoded_);
                        continue;
                    }
                    next_output_seconds_ += 1.0 / output_fps_;
                }
                AVFrame* scaled = av_frame_alloc();
                if (!scaled) return AVERROR(ENOMEM);
                scaled->format = target_format_;
                scaled->width = output_width_;
                scaled->height = output_height_;
                if ((rc = av_frame_get_buffer(scaled, 32)) < 0) {
                    av_frame_free(&scaled);
                    return rc;
                }
                sws_ = sws_getCachedContext(sws_, decoded_->width, decoded_->height,
                        static_cast<AVPixelFormat>(decoded_->format), output_width_, output_height_,
                        target_format_, SWS_BILINEAR, nullptr, nullptr, nullptr);
                if (!sws_) {
                    av_frame_free(&scaled);
                    return AVERROR(EINVAL);
                }
                sws_scale(sws_, decoded_->data, decoded_->linesize, 0, decoded_->height,
                          scaled->data, scaled->linesize);
                scaled->pts = output_index_++;
                av_frame_unref(decoded_);
                *output = scaled;
                return 1;
            }
            if (rc != AVERROR(EAGAIN) && rc != AVERROR_EOF) return rc;
            if (decoder_eof_) return 0;
            rc = av_read_frame(format_, packet_);
            if (rc < 0) {
                decoder_eof_ = true;
                avcodec_send_packet(decoder_, nullptr);
                continue;
            }
            if (packet_->stream_index == stream_index_) rc = avcodec_send_packet(decoder_, packet_);
            else rc = 0;
            av_packet_unref(packet_);
            if (rc < 0 && rc != AVERROR(EAGAIN)) return rc;
        }
    }

    int width() const { return output_width_; }
    int height() const { return output_height_; }
    int fps() const { return output_fps_; }
    double duration() const { return duration_seconds_; }
    const std::string& error() const { return error_; }
    const std::string& mode_used() const { return mode_used_; }
    const std::string& codec_name() const { return codec_name_; }

private:
    static const AVCodec* find_decoder(AVCodecID id, const std::string& mode) {
        (void)mode;
        return avcodec_find_decoder(id);
    }

    AVFormatContext* format_ = nullptr;
    AVCodecContext* decoder_ = nullptr;
    AVStream* stream_ = nullptr;
    AVFrame* decoded_ = nullptr;
    AVPacket* packet_ = nullptr;
    SwsContext* sws_ = nullptr;
    int stream_index_ = -1;
    int output_width_ = 0;
    int output_height_ = 0;
    int output_fps_ = 30;
    AVPixelFormat target_format_ = AV_PIX_FMT_YUV420P;
    bool decoder_eof_ = false;
    bool preserve_all_frames_ = false;
    int64_t decoded_count_ = 0;
    int64_t output_index_ = 0;
    double next_output_seconds_ = 0;
    double duration_seconds_ = 0;
    std::string error_;
    std::string mode_used_;
    std::string codec_name_;
};

struct Muxer {
    AVFormatContext* format = nullptr;
    AVStream* stream = nullptr;
    bool header_written = false;

    ~Muxer() { close(); }

    int open(const std::string& path, const char* name, AVCodecContext* encoder) {
        int rc = avformat_alloc_output_context2(&format, nullptr, name, path.c_str());
        if (rc < 0 || !format) return rc < 0 ? rc : AVERROR(EINVAL);
        stream = avformat_new_stream(format, nullptr);
        if (!stream) return AVERROR(ENOMEM);
        stream->time_base = encoder->time_base;
        if ((rc = avcodec_parameters_from_context(stream->codecpar, encoder)) < 0) return rc;
        if (!(format->oformat->flags & AVFMT_NOFILE)) {
            if ((rc = avio_open(&format->pb, path.c_str(), AVIO_FLAG_WRITE)) < 0) return rc;
        }
        rc = avformat_write_header(format, nullptr);
        header_written = rc >= 0;
        return rc;
    }

    int write(const AVPacket* packet, AVRational source_time_base) {
        AVPacket* copy = av_packet_clone(packet);
        if (!copy) return AVERROR(ENOMEM);
        av_packet_rescale_ts(copy, source_time_base, stream->time_base);
        copy->stream_index = stream->index;
        int rc = av_interleaved_write_frame(format, copy);
        av_packet_free(&copy);
        return rc;
    }

    void close() {
        if (!format) return;
        if (header_written) av_write_trailer(format);
        if (!(format->oformat->flags & AVFMT_NOFILE) && format->pb) avio_closep(&format->pb);
        avformat_free_context(format);
        format = nullptr;
    }
};

const AVCodec* find_encoder(AVCodecID id, const std::string& mode) {
    (void)mode;
    const char* name = id == AV_CODEC_ID_H264 ? "libx264" : "libx265";
    return avcodec_find_encoder_by_name(name);
}

AVPixelFormat encoder_pixel_format(const AVCodec* encoder, bool prefer_nv12) {
    if (!encoder->pix_fmts) return AV_PIX_FMT_YUV420P;
    if (prefer_nv12) {
        for (const AVPixelFormat* item = encoder->pix_fmts;
             *item != AV_PIX_FMT_NONE; ++item) {
            if (*item == AV_PIX_FMT_NV12) return AV_PIX_FMT_NV12;
        }
    }
    for (const AVPixelFormat* item = encoder->pix_fmts; *item != AV_PIX_FMT_NONE; ++item) {
        if (*item == AV_PIX_FMT_YUV420P) return *item;
    }
    return encoder->pix_fmts[0];
}

std::string pixel_format_name(AVPixelFormat format) {
    const char* name = av_get_pix_fmt_name(format);
    return name == nullptr ? "unknown" : name;
}

struct ElementaryStreamStats {
    uint64_t bytes = 0;
    int nal_units = 0;
    int keyframes = 0;
};

ElementaryStreamStats parse_elementary_stream(const std::string& path, AVCodecID codec_id) {
    ElementaryStreamStats stats;
    std::ifstream file(path, std::ios::binary);
    if (!file) return stats;
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(file)),
                              std::istreambuf_iterator<char>());
    stats.bytes = data.size();
    for (size_t i = 0; i + 3 < data.size();) {
        size_t start = i;
        size_t prefix = 0;
        if (i + 4 <= data.size() && data[i] == 0 && data[i + 1] == 0
                && data[i + 2] == 0 && data[i + 3] == 1) prefix = 4;
        else if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) prefix = 3;
        if (prefix == 0) { ++i; continue; }
        const size_t nal = start + prefix;
        if (nal < data.size()) {
            ++stats.nal_units;
            if (codec_id == AV_CODEC_ID_H264) {
                if ((data[nal] & 0x1f) == 5) ++stats.keyframes;
            } else if (codec_id == AV_CODEC_ID_HEVC && nal + 1 < data.size()) {
                const int type = (data[nal] >> 1) & 0x3f;
                if (type == 19 || type == 20 || type == 21) ++stats.keyframes;
            }
        }
        i = nal;
    }
    return stats;
}

bool write_yuv_frame(std::ofstream& output, const AVFrame* frame) {
    const auto format = static_cast<AVPixelFormat>(frame->format);
    if (format == AV_PIX_FMT_NV12) {
        for (int y = 0; y < frame->height; ++y) {
            output.write(reinterpret_cast<const char*>(frame->data[0] + y * frame->linesize[0]),
                         frame->width);
        }
        const int chroma_height = (frame->height + 1) / 2;
        for (int y = 0; y < chroma_height; ++y) {
            output.write(reinterpret_cast<const char*>(frame->data[1] + y * frame->linesize[1]),
                         frame->width);
        }
        return output.good();
    }
    if (format != AV_PIX_FMT_YUV420P) return false;
    for (int plane = 0; plane < 3; ++plane) {
        const int width = plane == 0 ? frame->width : (frame->width + 1) / 2;
        const int height = plane == 0 ? frame->height : (frame->height + 1) / 2;
        for (int y = 0; y < height; ++y) {
            output.write(reinterpret_cast<const char*>(frame->data[plane] + y * frame->linesize[plane]),
                         width);
        }
    }
    return output.good();
}

bool read_yuv_frame(std::ifstream& input, AVFrame* frame) {
    const auto format = static_cast<AVPixelFormat>(frame->format);
    if (format == AV_PIX_FMT_NV12) {
        for (int y = 0; y < frame->height; ++y) {
            input.read(reinterpret_cast<char*>(frame->data[0] + y * frame->linesize[0]), frame->width);
            if (!input) return false;
        }
        for (int y = 0; y < (frame->height + 1) / 2; ++y) {
            input.read(reinterpret_cast<char*>(frame->data[1] + y * frame->linesize[1]), frame->width);
            if (!input) return false;
        }
        return true;
    }
    if (format != AV_PIX_FMT_YUV420P) return false;
    for (int plane = 0; plane < 3; ++plane) {
        const int width = plane == 0 ? frame->width : (frame->width + 1) / 2;
        const int height = plane == 0 ? frame->height : (frame->height + 1) / 2;
        for (int y = 0; y < height; ++y) {
            input.read(reinterpret_cast<char*>(frame->data[plane] + y * frame->linesize[plane]), width);
            if (!input) return false;
        }
    }
    return true;
}

int drain_encoder(AVCodecContext* encoder, Muxer& raw, Muxer& preview) {
    AVPacket* packet = av_packet_alloc();
    if (!packet) return AVERROR(ENOMEM);
    int rc = 0;
    while ((rc = avcodec_receive_packet(encoder, packet)) == 0) {
        int raw_rc = raw.write(packet, encoder->time_base);
        int preview_rc = preview.write(packet, encoder->time_base);
        av_packet_unref(packet);
        if (raw_rc < 0 || preview_rc < 0) {
            rc = raw_rc < 0 ? raw_rc : preview_rc;
            break;
        }
    }
    av_packet_free(&packet);
    return rc == AVERROR(EAGAIN) || rc == AVERROR_EOF ? 0 : rc;
}

double calculate_psnr(const AVFrame* ref, const AVFrame* dist, long double& squared_error,
                      uint64_t& samples) {
    for (int plane = 0; plane < 3; ++plane) {
        int width = plane == 0 ? ref->width : (ref->width + 1) / 2;
        int height = plane == 0 ? ref->height : (ref->height + 1) / 2;
        for (int y = 0; y < height; ++y) {
            const uint8_t* a = ref->data[plane] + y * ref->linesize[plane];
            const uint8_t* b = dist->data[plane] + y * dist->linesize[plane];
            for (int x = 0; x < width; ++x) {
                int delta = static_cast<int>(a[x]) - static_cast<int>(b[x]);
                squared_error += static_cast<long double>(delta * delta);
            }
        }
        samples += static_cast<uint64_t>(width) * height;
    }
    if (squared_error == 0) return INFINITY;
    return 10.0 * std::log10(255.0 * 255.0 / static_cast<double>(squared_error / samples));
}

#if defined(CODEC_CORE_HAS_VMAF)
class VmafEvaluator {
public:
    ~VmafEvaluator() { close(); }

    bool open() {
        VmafConfiguration configuration{};
        configuration.log_level = VMAF_LOG_LEVEL_NONE;
        configuration.n_threads = 0; // libvmaf selects the device's worker count.
        // Mobile quality evaluation is intentionally sampled every other
        // frame. This keeps the score representative while avoiding a full
        // VMAF feature-extraction pass on every 1080p frame.
        configuration.n_subsample = 2;
        if (vmaf_init(&context_, configuration) != 0) return false;
        VmafModelConfig model_config{};
        model_config.flags = VMAF_MODEL_FLAG_ENABLE_TRANSFORM;
        if (vmaf_model_load(&model_, &model_config, "vmaf_v0.6.1") != 0 ||
            vmaf_use_features_from_model(context_, model_) != 0) {
            close();
            return false;
        }
        return true;
    }

    bool add(const AVFrame* reference, const AVFrame* distorted, unsigned index) {
        if (!context_ || !model_) return false;
        VmafPicture ref{};
        VmafPicture dist{};
        if (!copy(reference, &ref) || !copy(distorted, &dist)) {
            vmaf_picture_unref(&ref);
            vmaf_picture_unref(&dist);
            return false;
        }
        const int rc = vmaf_read_pictures(context_, &ref, &dist, index);
        if (rc != 0) {
            vmaf_picture_unref(&ref);
            vmaf_picture_unref(&dist);
            return false;
        }
        frame_count_ = index + 1;
        return true;
    }

    double finish() {
        if (!context_ || !model_ || frame_count_ == 0) return NAN;
        if (vmaf_read_pictures(context_, nullptr, nullptr, frame_count_) != 0) return NAN;
        double score = NAN;
        if (vmaf_score_pooled(context_, model_, VMAF_POOL_METHOD_MEAN, &score,
                              0, frame_count_ - 1) != 0) return NAN;
        return score;
    }

private:
    static bool copy(const AVFrame* source, VmafPicture* target) {
        if (!source || source->format != AV_PIX_FMT_YUV420P) return false;
        if (vmaf_picture_alloc(target, VMAF_PIX_FMT_YUV420P, 8,
                               static_cast<unsigned>(source->width),
                               static_cast<unsigned>(source->height)) != 0) return false;
        for (int plane = 0; plane < 3; ++plane) {
            const int width = plane == 0 ? source->width : (source->width + 1) / 2;
            const int height = plane == 0 ? source->height : (source->height + 1) / 2;
            auto* destination = static_cast<uint8_t*>(target->data[plane]);
            for (int row = 0; row < height; ++row) {
                std::memcpy(destination + row * target->stride[plane],
                            source->data[plane] + row * source->linesize[plane], width);
            }
        }
        return true;
    }

    void close() {
        if (model_) vmaf_model_destroy(model_);
        model_ = nullptr;
        if (context_) vmaf_close(context_);
        context_ = nullptr;
        frame_count_ = 0;
    }

    VmafContext* context_ = nullptr;
    VmafModel* model_ = nullptr;
    unsigned frame_count_ = 0;
};
#else
class VmafEvaluator {
public:
    bool open() { return false; }
    bool add(const AVFrame*, const AVFrame*, unsigned) { return false; }
    double finish() { return NAN; }
};
#endif

std::string metrics_json(const std::string& input, const std::string& preview,
                         const std::string& decoder, int width, int height, int fps,
                         const std::string& report_path, const Progress& progress) {
    progress(91, "计算 PSNR / VMAF");
    auto error_result = [&](const std::string& error) {
        const bool available = vmaf_available();
        std::ofstream report(report_path);
        if (report) {
            report << "{\n  \"frames\": 0,\n  \"psnr\": null,\n  \"vmaf\": null,\n"
                      "  \"vmafAvailable\": " << (available ? "true" : "false")
                   << ",\n  \"error\": \"" << escape(error) << "\"\n}\n";
        }
        return std::string("{\"frames\":0,\"psnr\":null,\"vmaf\":null,\"vmafAvailable\":")
                + (available ? "true" : "false") + ",\"error\":\""
                + escape(error) + "\"}";
    };
    VideoReader reference;
    VideoReader distorted;
    int rc = reference.open(input, decoder, width, height, fps, AV_PIX_FMT_YUV420P);
    if (rc < 0) return error_result(reference.error().empty() ? fferr(rc) : reference.error());
    rc = distorted.open(preview, "software", width, height, fps,
                        AV_PIX_FMT_YUV420P, true);
    if (rc < 0) return error_result(distorted.error().empty() ? fferr(rc) : distorted.error());

    VmafEvaluator vmaf;
    bool has_vmaf = vmaf.open();
    long double error = 0;
    uint64_t samples = 0;
    int64_t frame_index = 0;
    while (true) {
        AVFrame* ref = nullptr;
        AVFrame* dist = nullptr;
        int a = reference.next(&ref);
        int b = distorted.next(&dist);
        if (a <= 0 || b <= 0) {
            av_frame_free(&ref);
            av_frame_free(&dist);
            break;
        }
        ref->pts = dist->pts = frame_index++;
        calculate_psnr(ref, dist, error, samples);
        if (has_vmaf && !vmaf.add(ref, dist, static_cast<unsigned>(frame_index - 1))) {
            has_vmaf = false;
        }
        av_frame_free(&ref);
        av_frame_free(&dist);
    }
    double vmaf_score = has_vmaf ? vmaf.finish() : NAN;
    double psnr = samples == 0 ? NAN : (error == 0 ? INFINITY
            : 10.0 * std::log10(255.0 * 255.0 / static_cast<double>(error / samples)));
    std::ofstream report(report_path);
    report << "{\n  \"frames\": " << frame_index << ",\n  \"psnr\": ";
    if (std::isfinite(psnr)) report << psnr; else report << "null";
    report << ",\n  \"vmaf\": ";
    if (std::isfinite(vmaf_score)) report << vmaf_score; else report << "null";
    report << ",\n  \"vmafAvailable\": " << (has_vmaf ? "true" : "false");
    report << "\n}\n";

    std::ostringstream json;
    json << "{\"frames\":" << frame_index << ",\"psnr\":";
    if (std::isfinite(psnr)) json << psnr; else json << "null";
    json << ",\"vmaf\":";
    if (std::isfinite(vmaf_score)) json << vmaf_score; else json << "null";
    json << ",\"vmafAvailable\":" << (has_vmaf ? "true" : "false");
    json << "}";
    return json.str();
}

std::string metrics_yuv_json(const std::string& reference_path,
                             const std::string& pixel_format,
                             const std::string& preview,
                             int width, int height, int fps,
                             const std::string& report_path,
                             const Progress& progress) {
    progress(91, "按编码前帧计算 PSNR / VMAF");
    const char* metric_basis = "pre_encode_yuv_vs_decoded_output";
    auto error_result = [&](const std::string& error) {
        const bool available = vmaf_available();
        std::ofstream report(report_path);
        if (report) {
            report << "{\n  \"metricBasis\": \"" << metric_basis
                   << "\",\n  \"frames\": 0,\n  \"psnr\": null,\n  \"vmaf\": null,\n"
                      "  \"vmafAvailable\": " << (available ? "true" : "false")
                   << ",\n  \"error\": \"" << escape(error) << "\"\n}\n";
        }
        return std::string("{\"metricBasis\":\"") + metric_basis
                + "\",\"frames\":0,\"psnr\":null,\"vmaf\":null,\"vmafAvailable\":"
                + (available ? "true" : "false") + ",\"error\":\""
                + escape(error) + "\"}";
    };

    if (width <= 0 || height <= 0) return error_result("Invalid reference dimensions");
    const AVPixelFormat source_format = pixel_format == "nv12"
            ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUV420P;
    std::ifstream reference_file(reference_path, std::ios::binary);
    if (!reference_file) return error_result("Unable to read pre-encode YUV reference");

    VideoReader distorted;
    int rc = distorted.open(preview, "software", width, height, fps,
                            AV_PIX_FMT_YUV420P, true);
    if (rc < 0) return error_result(distorted.error().empty() ? fferr(rc) : distorted.error());

    AVFrame* source = av_frame_alloc();
    AVFrame* converted = nullptr;
    SwsContext* convert = nullptr;
    if (!source) return error_result("Reference frame allocation failed");
    source->format = source_format;
    source->width = width;
    source->height = height;
    rc = av_frame_get_buffer(source, 32);
    if (rc < 0) {
        av_frame_free(&source);
        return error_result(fferr(rc));
    }
    if (source_format != AV_PIX_FMT_YUV420P) {
        converted = av_frame_alloc();
        if (!converted) {
            av_frame_free(&source);
            return error_result("Reference conversion frame allocation failed");
        }
        converted->format = AV_PIX_FMT_YUV420P;
        converted->width = width;
        converted->height = height;
        rc = av_frame_get_buffer(converted, 32);
        if (rc < 0) {
            av_frame_free(&converted);
            av_frame_free(&source);
            return error_result(fferr(rc));
        }
        convert = sws_getContext(width, height, source_format, width, height,
                                 AV_PIX_FMT_YUV420P, SWS_POINT, nullptr, nullptr, nullptr);
        if (!convert) {
            av_frame_free(&converted);
            av_frame_free(&source);
            return error_result("Unable to convert YUV reference to I420");
        }
    }

    VmafEvaluator vmaf;
    bool has_vmaf = vmaf.open();
    long double error = 0;
    uint64_t samples = 0;
    int64_t frame_index = 0;
    while (read_yuv_frame(reference_file, source)) {
        AVFrame* dist = nullptr;
        int decoded = distorted.next(&dist);
        if (decoded <= 0) {
            av_frame_free(&dist);
            break;
        }
        AVFrame* ref = source;
        if (convert) {
            sws_scale(convert, source->data, source->linesize, 0, height,
                      converted->data, converted->linesize);
            ref = converted;
        }
        ref->pts = dist->pts = frame_index++;
        calculate_psnr(ref, dist, error, samples);
        if (has_vmaf && !vmaf.add(ref, dist, static_cast<unsigned>(frame_index - 1))) {
            has_vmaf = false;
        }
        av_frame_free(&dist);
    }
    if (convert) sws_freeContext(convert);
    av_frame_free(&converted);
    av_frame_free(&source);

    double vmaf_score = has_vmaf ? vmaf.finish() : NAN;
    double psnr = samples == 0 ? NAN : (error == 0 ? INFINITY
            : 10.0 * std::log10(255.0 * 255.0 / static_cast<double>(error / samples)));
    std::ofstream report(report_path);
    report << "{\n  \"metricBasis\": \"" << metric_basis << "\",\n  \"frames\": "
           << frame_index << ",\n  \"psnr\": ";
    if (std::isfinite(psnr)) report << psnr; else report << "null";
    report << ",\n  \"vmaf\": ";
    if (std::isfinite(vmaf_score)) report << vmaf_score; else report << "null";
    report << ",\n  \"vmafAvailable\": " << (has_vmaf ? "true" : "false") << "\n}\n";

    std::ostringstream json;
    json << "{\"metricBasis\":\"" << metric_basis << "\",\"frames\":"
         << frame_index << ",\"psnr\":";
    if (std::isfinite(psnr)) json << psnr; else json << "null";
    json << ",\"vmaf\":";
    if (std::isfinite(vmaf_score)) json << vmaf_score; else json << "null";
    json << ",\"vmafAvailable\":" << (has_vmaf ? "true" : "false") << "}";
    return json.str();
}

}  // namespace

bool ffmpeg_available() { return true; }
bool vmaf_available() {
#if defined(CODEC_CORE_HAS_VMAF)
    return true;
#else
    return false;
#endif
}
std::string ffmpeg_version() { return av_version_info(); }

std::string probe_with_ffmpeg(const std::string& input_path) {
    AVFormatContext* context = nullptr;
    int rc = avformat_open_input(&context, input_path.c_str(), nullptr, nullptr);
    if (rc >= 0) rc = avformat_find_stream_info(context, nullptr);
    int index = rc < 0 ? rc : av_find_best_stream(context, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (index < 0) {
        avformat_close_input(&context);
        return "{\"ok\":false,\"error\":\"" + escape(fferr(index)) + "\"}";
    }
    AVStream* stream = context->streams[index];
    AVRational rate = av_guess_frame_rate(context, stream, nullptr);
    std::ostringstream json;
    json << "{\"ok\":true,\"codec\":\"" << avcodec_get_name(stream->codecpar->codec_id)
         << "\",\"width\":" << stream->codecpar->width
         << ",\"height\":" << stream->codecpar->height
         << ",\"fps\":" << av_q2d(rate)
         << ",\"bitrate\":" << stream->codecpar->bit_rate
         << ",\"duration\":" << (context->duration / static_cast<double>(AV_TIME_BASE)) << "}";
    avformat_close_input(&context);
    return json.str();
}

std::string transcode_with_ffmpeg(const std::string& request, const Progress& progress) {
    const std::string input = json_string(request, "input");
    const int input_fd = json_int(request, "inputFd", -1);
    const std::string output_dir = json_string(request, "outputDir");
    const int output_dir_fd = json_int(request, "outputDirFd", -1);
    const std::string codec_name = json_string(request, "outputCodec", "h264");
    const std::string decoder_mode = json_string(request, "decoder", "software");
    const std::string encoder_mode = json_string(request, "encoder", "software");
    const int width = json_int(request, "width", 720);
    const int height = json_int(request, "height", 1280);
    const int fps = json_int(request, "fps", 30);
    const int bitrate = json_int(request, "bitrateKbps", 4000);
    const bool metrics = json_bool(request, "metrics", true);
    const std::string selection_warning = json_string(request, "selectionWarning");
    if (input.empty() || output_dir.empty()) {
        return R"({"ok":false,"error":"input and outputDir are required"})";
    }
    const std::string native_output_dir = output_dir_fd >= 0
            ? "/proc/self/fd/" + std::to_string(output_dir_fd) : output_dir;
    const AVCodecID codec_id = codec_name == "hevc" ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
    // Hardware jobs are handled by the Java MediaCodec pipeline. Native is
    // deliberately limited to FFmpeg software decode plus x264/x265 encode.
    if (decoder_mode == "hardware" || encoder_mode == "hardware") {
        return R"({"ok":false,"error":"Hardware mode must use the Java MediaCodec pipeline"})";
    }
    const bool requested_hardware = false;
    std::string effective_encoder_mode = "software";
    const AVCodec* encoder_codec = find_encoder(codec_id, effective_encoder_mode);
    if (!encoder_codec) {
        return "{\"ok\":false,\"error\":\"Requested encoder is unavailable: "
                + escape(encoder_mode + " " + codec_name) + "\"}";
    }
    // MediaCodec on Qualcomm/OnePlus commonly rejects planar YUV420 input
    // (AMEDIA_ERROR_UNSUPPORTED / err -61) and only accepts semi-planar NV12.
    // Select NV12 for the hardware attempt; software encoders retain their
    // normal YUV420P input format.
    AVPixelFormat pixel_format = encoder_pixel_format(encoder_codec, requested_hardware);
    VideoReader reader;
    std::string native_input = input_fd >= 0 ? "/proc/self/fd/" + std::to_string(input_fd) : input;
    int rc = reader.open(native_input, decoder_mode, width, height, fps, pixel_format);
    if (rc < 0) return "{\"ok\":false,\"error\":\"Decoder open failed (" + escape(input)
            + ", mode=" + escape(decoder_mode) + "): " + escape(reader.error().empty() ? fferr(rc) : reader.error()) + "\"}";

    auto make_encoder = [&](const AVCodec* codec, const std::string& mode) {
        AVCodecContext* ctx = avcodec_alloc_context3(codec);
        if (!ctx) return static_cast<AVCodecContext*>(nullptr);
        ctx->codec_id = codec_id;
        ctx->width = reader.width();
        ctx->height = reader.height();
        ctx->pix_fmt = encoder_pixel_format(codec, mode == "hardware");
        ctx->time_base = AVRational{1, reader.fps()};
        ctx->framerate = AVRational{reader.fps(), 1};
        ctx->bit_rate = static_cast<int64_t>(bitrate) * 1000;
        ctx->gop_size = reader.fps() * 2;
        ctx->max_b_frames = mode == "software" ? 0 : 0;
        if (mode == "software") {
            // Faster presets are much more appropriate for an interactive
            // Android app; PSNR remains calculated independently afterwards.
            av_opt_set(ctx->priv_data, "preset", codec_id == AV_CODEC_ID_HEVC ? "ultrafast" : "veryfast", 0);
            av_opt_set(ctx->priv_data, "tune", "psnr", 0);
        }
        return ctx;
    };

    AVCodecContext* encoder = make_encoder(encoder_codec, effective_encoder_mode);
    if (!encoder) return R"({"ok":false,"error":"Encoder allocation failed"})";
    rc = avcodec_open2(encoder, encoder_codec, nullptr);
    if (rc < 0) {
        std::string error = fferr(rc);
        avcodec_free_context(&encoder);
        return "{\"ok\":false,\"error\":\"Encoder open failed: " + escape(error) + "\"}";
    }

    const std::string raw_path = native_output_dir + (codec_id == AV_CODEC_ID_H264 ? "/output.h264" : "/output.hevc");
    const std::string preview_path = native_output_dir + "/preview.mp4";
    const std::string report_path = native_output_dir + "/metrics.json";
    const std::string reference_path = native_output_dir + "/pre_encode_reference.yuv";
    const std::string logical_raw_path = output_dir + (codec_id == AV_CODEC_ID_H264 ? "/output.h264" : "/output.hevc");
    const std::string logical_preview_path = output_dir + "/preview.mp4";
    const std::string logical_report_path = output_dir + "/metrics.json";
    Muxer raw;
    Muxer preview;
    rc = raw.open(raw_path, codec_id == AV_CODEC_ID_H264 ? "h264" : "hevc", encoder);
    if (rc >= 0) rc = preview.open(preview_path, "mp4", encoder);
    if (rc < 0) {
        std::string error = fferr(rc);
        avcodec_free_context(&encoder);
        return "{\"ok\":false,\"error\":\"Muxer open failed: " + escape(error) + "\"}";
    }

    std::ofstream reference_yuv;
    if (metrics) {
        reference_yuv.open(reference_path, std::ios::binary | std::ios::trunc);
        if (!reference_yuv) {
            raw.close();
            preview.close();
            avcodec_free_context(&encoder);
            return R"({"ok":false,"error":"Unable to create pre-encode metric reference"})";
        }
    }

    int64_t frames = 0;
    while (true) {
        AVFrame* frame = nullptr;
        rc = reader.next(&frame);
        if (rc <= 0) {
            av_frame_free(&frame);
            break;
        }
        frame->pts = frames++;
        AVFrame* encode_frame = frame;
        AVFrame* converted_frame = nullptr;
        if (frame->format != encoder->pix_fmt) {
            converted_frame = av_frame_alloc();
            if (!converted_frame) {
                av_frame_free(&frame);
                rc = AVERROR(ENOMEM);
                break;
            }
            converted_frame->format = encoder->pix_fmt;
            converted_frame->width = reader.width();
            converted_frame->height = reader.height();
            rc = av_frame_get_buffer(converted_frame, 32);
            if (rc >= 0) {
                SwsContext* convert = sws_getCachedContext(nullptr,
                        frame->width, frame->height,
                        static_cast<AVPixelFormat>(frame->format),
                        converted_frame->width, converted_frame->height,
                        static_cast<AVPixelFormat>(converted_frame->format),
                        SWS_BILINEAR, nullptr, nullptr, nullptr);
                if (!convert) rc = AVERROR(EINVAL);
                else {
                    sws_scale(convert, frame->data, frame->linesize, 0, frame->height,
                              converted_frame->data, converted_frame->linesize);
                    converted_frame->pts = frame->pts;
                }
                if (convert) sws_freeContext(convert);
            }
            if (rc < 0) {
                av_frame_free(&converted_frame);
                av_frame_free(&frame);
                break;
            }
            encode_frame = converted_frame;
        }
        if (metrics && !write_yuv_frame(reference_yuv, encode_frame)) {
            rc = AVERROR(EIO);
            av_frame_free(&converted_frame);
            av_frame_free(&frame);
            break;
        }
        rc = avcodec_send_frame(encoder, encode_frame);
        av_frame_free(&converted_frame);
        av_frame_free(&frame);
        if (rc >= 0) rc = drain_encoder(encoder, raw, preview);
        if (rc < 0) break;
        int percent = reader.duration() > 0
                ? std::min(88, static_cast<int>(frames * 100.0 / reader.fps() / reader.duration() * 0.88))
                : std::min(88, static_cast<int>(frames % 89));
        progress(percent, effective_encoder_mode == "hardware" ? "硬件编码" : "软件编码");
    }
    if (rc == 0) {
        rc = avcodec_send_frame(encoder, nullptr);
        if (rc >= 0) rc = drain_encoder(encoder, raw, preview);
    }
    raw.close();
    preview.close();
    reference_yuv.close();
    const std::string encoder_codec_name = encoder_codec == nullptr ? "unknown" : encoder_codec->name;
    const std::string encoder_pixel_name = pixel_format_name(encoder->pix_fmt);
    avcodec_free_context(&encoder);
    if (rc < 0) return "{\"ok\":false,\"error\":\"Transcode failed: " + escape(fferr(rc)) + "\"}";

    const ElementaryStreamStats stream_stats = parse_elementary_stream(raw_path, codec_id);

    std::string metric_values = "null";
    // Keep the file descriptors alive while metrics are calculated. On some
    // Android 8/9 devices the Java-visible /data/user/0 path is not resolvable
    // by native FFmpeg, while /proc/self/fd/<fd> is. Use those native paths for
    // both the reference and distorted videos; return the stable Java paths in
    // the result JSON below.
    if (metrics) {
        metric_values = metrics_yuv_json(reference_path, encoder_pixel_name, preview_path,
                width, height, fps, report_path, progress);
        std::error_code remove_error;
        std::filesystem::remove(reference_path, remove_error);
    }
    progress(100, "完成");
    std::string warning = selection_warning;
    return "{\"ok\":true,\"encoderMode\":\"" + escape(effective_encoder_mode)
            + "\",\"decoderMode\":\"" + escape(reader.mode_used())
            + "\",\"warning\":" + (warning.empty() ? "null" : "\"" + escape(warning) + "\"")
            + ",\"codecDetails\":{\"decoder\":{\"mode\":\"" + escape(reader.mode_used())
            + "\",\"name\":\"" + escape(reader.codec_name())
            + "\",\"pixelFormat\":\"" + escape(pixel_format_name(pixel_format))
            + "\"},\"encoder\":{\"mode\":\"" + escape(effective_encoder_mode)
            + "\",\"name\":\"" + escape(encoder_codec_name)
            + "\",\"pixelFormat\":\"" + escape(encoder_pixel_name)
            + "\"},\"stream\":{\"bytes\":" + std::to_string(stream_stats.bytes)
            + ",\"nalUnits\":" + std::to_string(stream_stats.nal_units)
            + ",\"keyframes\":" + std::to_string(stream_stats.keyframes) + "}}"
            + ",\"elementaryStream\":\"" + escape(logical_raw_path)
            + "\",\"previewFile\":\"" + escape(logical_preview_path)
            + "\",\"metricsFile\":" + (metrics ? "\"" + escape(logical_report_path) + "\"" : "null")
            + ",\"metrics\":" + metric_values + "}";
}

std::string decode_yuv_with_ffmpeg(const std::string& request, const Progress& progress) {
    const std::string input = json_string(request, "input");
    const std::string output_file = json_string(request, "outputFile");
    const std::string pixel_name = json_string(request, "pixelFormat", "nv12");
    const int width = json_int(request, "width", 720);
    const int height = json_int(request, "height", 1280);
    const int fps = json_int(request, "fps", 30);
    if (input.empty() || output_file.empty()) {
        return R"({"ok":false,"error":"input and outputFile are required"})";
    }
    const AVPixelFormat format = pixel_name == "yuv420p" ? AV_PIX_FMT_YUV420P : AV_PIX_FMT_NV12;
    VideoReader reader;
    int rc = reader.open(input, "software", width, height, fps, format);
    if (rc < 0) {
        return "{\"ok\":false,\"error\":\"Software decoder open failed: "
                + escape(reader.error().empty() ? fferr(rc) : reader.error()) + "\"}";
    }
    std::ofstream output(output_file, std::ios::binary | std::ios::trunc);
    if (!output) return R"({"ok":false,"error":"Unable to create intermediate YUV file"})";
    int64_t frames = 0;
    while (true) {
        AVFrame* frame = nullptr;
        rc = reader.next(&frame);
        if (rc <= 0) {
            av_frame_free(&frame);
            break;
        }
        if (!write_yuv_frame(output, frame)) {
            av_frame_free(&frame);
            return R"({"ok":false,"error":"Failed to write decoded YUV frame"})";
        }
        av_frame_free(&frame);
        ++frames;
        if (progress) {
            int percent = reader.duration() > 0
                    ? std::min(100, static_cast<int>(frames * 100.0 / reader.fps() / reader.duration()))
                    : std::min(99, static_cast<int>(frames % 100));
            progress(percent, "FFmpeg 软件解码");
        }
    }
    output.close();
    if (rc < 0) return "{\"ok\":false,\"error\":\"Software decode failed: " + escape(fferr(rc)) + "\"}";
    if (progress) progress(100, "软件解码完成");
    return "{\"ok\":true,\"frames\":" + std::to_string(frames)
            + ",\"width\":" + std::to_string(reader.width())
            + ",\"height\":" + std::to_string(reader.height())
            + ",\"fps\":" + std::to_string(reader.fps())
            + ",\"pixelFormat\":\"" + escape(pixel_name)
            + "\",\"decoderName\":\"" + escape(reader.codec_name()) + "\"}";
}

std::string encode_yuv_with_x26x(const std::string& request, const Progress& progress) {
    const std::string yuv_file = json_string(request, "yuvFile");
    const std::string input = json_string(request, "input");
    const std::string output_dir = json_string(request, "outputDir");
    const std::string codec_name = json_string(request, "outputCodec", "h264");
    const std::string pixel_name = json_string(request, "pixelFormat", "yuv420p");
    const std::string decoder_mode = json_string(request, "decoderMode", "hardware");
    const std::string decoder_name = json_string(request, "decoderName", "MediaCodec");
    const int width = json_int(request, "width", 720);
    const int height = json_int(request, "height", 1280);
    const int fps = json_int(request, "fps", 30);
    const int bitrate = json_int(request, "bitrateKbps", 4000);
    const bool metrics = json_bool(request, "metrics", true);
    if (yuv_file.empty() || input.empty() || output_dir.empty()) {
        return R"({"ok":false,"error":"yuvFile, input and outputDir are required"})";
    }
    const AVCodecID codec_id = codec_name == "hevc" ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
    const AVCodec* encoder_codec = find_encoder(codec_id, "software");
    if (!encoder_codec) return R"({"ok":false,"error":"x264/x265 encoder is unavailable"})";
    AVCodecContext* encoder = avcodec_alloc_context3(encoder_codec);
    if (!encoder) return R"({"ok":false,"error":"Encoder allocation failed"})";
    encoder->codec_id = codec_id;
    encoder->width = width;
    encoder->height = height;
    encoder->pix_fmt = pixel_name == "nv12" ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUV420P;
    encoder->time_base = AVRational{1, fps};
    encoder->framerate = AVRational{fps, 1};
    encoder->bit_rate = static_cast<int64_t>(bitrate) * 1000;
    encoder->gop_size = fps * 2;
    encoder->max_b_frames = 0;
    av_opt_set(encoder->priv_data, "preset", codec_id == AV_CODEC_ID_HEVC ? "ultrafast" : "veryfast", 0);
    av_opt_set(encoder->priv_data, "tune", "psnr", 0);
    int rc = avcodec_open2(encoder, encoder_codec, nullptr);
    if (rc < 0) {
        std::string error = fferr(rc);
        avcodec_free_context(&encoder);
        return "{\"ok\":false,\"error\":\"Software encoder open failed: " + escape(error) + "\"}";
    }
    const std::string raw_path = output_dir + (codec_id == AV_CODEC_ID_H264 ? "/output.h264" : "/output.hevc");
    const std::string preview_path = output_dir + "/preview.mp4";
    const std::string report_path = output_dir + "/metrics.json";
    Muxer raw;
    Muxer preview;
    rc = raw.open(raw_path, codec_id == AV_CODEC_ID_H264 ? "h264" : "hevc", encoder);
    if (rc >= 0) rc = preview.open(preview_path, "mp4", encoder);
    if (rc < 0) {
        std::string error = fferr(rc);
        avcodec_free_context(&encoder);
        return "{\"ok\":false,\"error\":\"Muxer open failed: " + escape(error) + "\"}";
    }
    std::ifstream input_yuv(yuv_file, std::ios::binary);
    if (!input_yuv) {
        avcodec_free_context(&encoder);
        return R"({"ok":false,"error":"Unable to read intermediate YUV file"})";
    }
    AVFrame* frame = av_frame_alloc();
    if (!frame) {
        avcodec_free_context(&encoder);
        return R"({"ok":false,"error":"Frame allocation failed"})";
    }
    frame->format = encoder->pix_fmt;
    frame->width = width;
    frame->height = height;
    rc = av_frame_get_buffer(frame, 32);
    int64_t frames = 0;
    while (rc >= 0 && read_yuv_frame(input_yuv, frame)) {
        frame->pts = frames++;
        rc = avcodec_send_frame(encoder, frame);
        if (rc >= 0) rc = drain_encoder(encoder, raw, preview);
        if (progress) progress(std::min(88, static_cast<int>(frames % 89)), "x264/x265 软件编码");
    }
    if (rc >= 0) {
        rc = avcodec_send_frame(encoder, nullptr);
        if (rc >= 0) rc = drain_encoder(encoder, raw, preview);
    }
    av_frame_free(&frame);
    raw.close();
    preview.close();
    const std::string encoder_pixel_name = pixel_format_name(encoder->pix_fmt);
    avcodec_free_context(&encoder);
    if (rc < 0) return "{\"ok\":false,\"error\":\"Software encode failed: " + escape(fferr(rc)) + "\"}";
    const ElementaryStreamStats stream_stats = parse_elementary_stream(raw_path, codec_id);
    std::string metric_values = "null";
    if (metrics) metric_values = metrics_yuv_json(yuv_file, pixel_name, preview_path,
            width, height, fps, report_path,
            progress ? progress : Progress([](int, const std::string&) {}));
    if (progress) progress(100, "完成");
    return "{\"ok\":true,\"encoderMode\":\"software\",\"decoderMode\":\""
            + escape(decoder_mode) + "\",\"warning\":null,\"codecDetails\":{\"decoder\":{\"mode\":\""
            + escape(decoder_mode) + "\",\"name\":\"" + escape(decoder_name)
            + "\",\"pixelFormat\":\"" + escape(pixel_name)
            + "\"},\"encoder\":{\"mode\":\"software\",\"name\":\""
            + escape(encoder_codec->name) + "\",\"pixelFormat\":\"" + escape(encoder_pixel_name)
            + "\"},\"stream\":{\"bytes\":" + std::to_string(stream_stats.bytes)
            + ",\"nalUnits\":" + std::to_string(stream_stats.nal_units)
            + ",\"keyframes\":" + std::to_string(stream_stats.keyframes)
            + "}},\"elementaryStream\":\"" + escape(raw_path)
            + "\",\"previewFile\":\"" + escape(preview_path)
            + "\",\"metricsFile\":" + (metrics ? "\"" + escape(report_path) + "\"" : "null")
            + ",\"metrics\":" + metric_values + "}";
}

std::string metrics_with_ffmpeg(const std::string& input_path, const std::string& preview_path,
                                int width, int height, int fps, const std::string& report_path,
                                const Progress& progress) {
    const Progress noop = [](int, const std::string&) {};
    return metrics_json(input_path, preview_path, "software", width, height, fps,
                        report_path, progress ? progress : noop);
}

std::string metrics_yuv_with_ffmpeg(const std::string& reference_yuv_path,
                                    const std::string& pixel_format,
                                    const std::string& preview_path,
                                    int width, int height, int fps,
                                    const std::string& report_path,
                                    const Progress& progress) {
    const Progress noop = [](int, const std::string&) {};
    return metrics_yuv_json(reference_yuv_path, pixel_format, preview_path,
                            width, height, fps, report_path,
                            progress ? progress : noop);
}

}  // namespace codec_core
