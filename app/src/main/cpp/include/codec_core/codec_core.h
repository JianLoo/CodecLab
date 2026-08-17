#pragma once

#include <stddef.h>

#if defined(_WIN32)
#define CODEC_CORE_API __declspec(dllexport)
#else
#define CODEC_CORE_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*codec_core_progress_cb)(int percent, const char* stage, void* opaque);

// Returned strings are owned by codec-core and remain valid until the next call
// on the same thread. Requests and responses use UTF-8 JSON to keep the ABI
// callable from JNI, Swift/Objective-C and other FFI clients.
CODEC_CORE_API const char* codec_core_version(void);
CODEC_CORE_API const char* codec_core_capabilities(void);
CODEC_CORE_API const char* codec_core_probe(const char* input_path);
CODEC_CORE_API const char* codec_core_transcode(
        const char* request_json,
        codec_core_progress_cb progress,
        void* opaque);
CODEC_CORE_API void codec_core_cancel(void);

#ifdef __cplusplus
}
#endif

