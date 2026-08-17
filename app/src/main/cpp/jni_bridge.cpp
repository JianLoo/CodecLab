#include "codec_core/codec_core.h"
#include "backend.h"

#include <jni.h>
#include <string>

namespace {

struct JniProgress {
    JNIEnv* env;
    jobject listener;
    jmethodID method;
};

std::string chars(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    std::string result(raw == nullptr ? "" : raw);
    if (raw != nullptr) env->ReleaseStringUTFChars(value, raw);
    return result;
}

void on_progress(int percent, const char* stage, void* opaque) {
    auto* context = static_cast<JniProgress*>(opaque);
    if (context == nullptr || context->listener == nullptr || context->method == nullptr) return;
    jstring text = context->env->NewStringUTF(stage == nullptr ? "" : stage);
    context->env->CallVoidMethod(context->listener, context->method, percent, text);
    context->env->DeleteLocalRef(text);
}

jstring result(JNIEnv* env, const char* text) {
    return env->NewStringUTF(text == nullptr ? "{}" : text);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_codeclab_NativeCodecEngine_capabilities(JNIEnv* env, jclass) {
    return result(env, codec_core_capabilities());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_codeclab_NativeCodecEngine_probe(JNIEnv* env, jclass, jstring path) {
    const std::string input = chars(env, path);
    return result(env, codec_core_probe(input.c_str()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_codeclab_NativeCodecEngine_transcode(
        JNIEnv* env, jclass, jstring request, jobject listener) {
    const std::string json = chars(env, request);
    JniProgress context{env, listener, nullptr};
    if (listener != nullptr) {
        jclass type = env->GetObjectClass(listener);
        context.method = env->GetMethodID(type, "onProgress", "(ILjava/lang/String;)V");
        env->DeleteLocalRef(type);
    }
    return result(env, codec_core_transcode(json.c_str(), on_progress, &context));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_codeclab_NativeCodecEngine_decodeYuv(
        JNIEnv* env, jclass, jstring request, jobject listener) {
    const std::string json = chars(env, request);
    JniProgress context{env, listener, nullptr};
    if (listener != nullptr) {
        jclass type = env->GetObjectClass(listener);
        context.method = env->GetMethodID(type, "onProgress", "(ILjava/lang/String;)V");
        env->DeleteLocalRef(type);
    }
    codec_core::Progress callback = [&](int percent, const std::string& stage) {
        on_progress(percent, stage.c_str(), &context);
    };
    return result(env, codec_core::decode_yuv_with_ffmpeg(json, callback).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_codeclab_NativeCodecEngine_encodeYuv(
        JNIEnv* env, jclass, jstring request, jobject listener) {
    const std::string json = chars(env, request);
    JniProgress context{env, listener, nullptr};
    if (listener != nullptr) {
        jclass type = env->GetObjectClass(listener);
        context.method = env->GetMethodID(type, "onProgress", "(ILjava/lang/String;)V");
        env->DeleteLocalRef(type);
    }
    codec_core::Progress callback = [&](int percent, const std::string& stage) {
        on_progress(percent, stage.c_str(), &context);
    };
    return result(env, codec_core::encode_yuv_with_x26x(json, callback).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_codeclab_NativeCodecEngine_metrics(
        JNIEnv* env, jclass, jstring input, jstring preview,
        jint width, jint height, jint fps, jstring report) {
    const std::string input_path = chars(env, input);
    const std::string preview_path = chars(env, preview);
    const std::string report_path = chars(env, report);
    return result(env, codec_core::metrics_with_ffmpeg(
            input_path, preview_path, width, height, fps, report_path, nullptr).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_codeclab_NativeCodecEngine_metricsYuv(
        JNIEnv* env, jclass, jstring reference, jstring pixel_format, jstring preview,
        jint width, jint height, jint fps, jstring report) {
    const std::string reference_path = chars(env, reference);
    const std::string format = chars(env, pixel_format);
    const std::string preview_path = chars(env, preview);
    const std::string report_path = chars(env, report);
    return result(env, codec_core::metrics_yuv_with_ffmpeg(
            reference_path, format, preview_path, width, height, fps,
            report_path, nullptr).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_codeclab_NativeCodecEngine_cancel(JNIEnv*, jclass) {
    codec_core_cancel();
}
