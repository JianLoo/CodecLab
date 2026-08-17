cmake_minimum_required(VERSION 3.22.1)
project(codec_core LANGUAGES C CXX)

option(CODEC_CORE_ENABLE_FFMPEG "Link prebuilt FFmpeg libraries" ON)
option(CODEC_CORE_ENABLE_VMAF "Link the bundled libvmaf static library" ON)

add_library(codec_core SHARED
        codec_core.cpp
        jni_bridge.cpp)

target_include_directories(codec_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_features(codec_core PRIVATE cxx_std_20)
target_compile_options(codec_core PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(codec_core PRIVATE android log)

# Expected layout after running scripts/build_ffmpeg_android.sh:
# third_party/ffmpeg/android/<abi>/{include,lib/*.a}
set(FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ffmpeg/android/${ANDROID_ABI}")
set(VMAF_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/vmaf/android/${ANDROID_ABI}")
# The core directly uses demuxing, video decode, scaling and VMAF filtering.
# Published FFmpeg archives may still contain transitive references from their
# compiled codec/filter registry, so these two small support archives remain
# link dependencies even though codec-core never calls their APIs directly.
set(FFMPEG_REQUIRED_COMPONENTS avformat avcodec avfilter avutil swscale)
set(FFMPEG_OPTIONAL_COMPONENTS postproc swresample)
set(FFMPEG_COMPONENTS ${FFMPEG_REQUIRED_COMPONENTS})
set(FFMPEG_FOUND TRUE)
foreach(component IN LISTS FFMPEG_REQUIRED_COMPONENTS)
    if(NOT EXISTS "${FFMPEG_ROOT}/lib/lib${component}.a")
    set(FFMPEG_FOUND FALSE)
  endif()
endforeach()
foreach(component IN LISTS FFMPEG_OPTIONAL_COMPONENTS)
    if(EXISTS "${FFMPEG_ROOT}/lib/lib${component}.a")
        list(APPEND FFMPEG_COMPONENTS ${component})
    endif()
endforeach()

if(CODEC_CORE_ENABLE_FFMPEG AND FFMPEG_FOUND)
    target_sources(codec_core PRIVATE ffmpeg_backend.cpp)
    target_include_directories(codec_core PRIVATE "${FFMPEG_ROOT}/include")
    foreach(component IN LISTS FFMPEG_COMPONENTS)
        add_library(ffmpeg_${component} STATIC IMPORTED)
        set_target_properties(ffmpeg_${component} PROPERTIES
                IMPORTED_LOCATION "${FFMPEG_ROOT}/lib/lib${component}.a")
        target_link_libraries(codec_core PRIVATE ffmpeg_${component})
    endforeach()
    foreach(codec_library IN ITEMS x264 x265)
        if(EXISTS "${FFMPEG_ROOT}/lib/lib${codec_library}.a")
            add_library(${codec_library} STATIC IMPORTED)
            set_target_properties(${codec_library} PROPERTIES
                    IMPORTED_LOCATION "${FFMPEG_ROOT}/lib/lib${codec_library}.a")
            target_link_libraries(codec_core PRIVATE ${codec_library})
        endif()
    endforeach()
    target_compile_definitions(codec_core PRIVATE CODEC_CORE_HAS_FFMPEG=1)
    if(CODEC_CORE_ENABLE_VMAF AND EXISTS "${VMAF_ROOT}/lib/libvmaf.a")
        add_library(vmaf STATIC IMPORTED)
        set_target_properties(vmaf PROPERTIES
                IMPORTED_LOCATION "${VMAF_ROOT}/lib/libvmaf.a")
        target_include_directories(codec_core PRIVATE "${VMAF_ROOT}/include")
        target_compile_definitions(codec_core PRIVATE CODEC_CORE_HAS_VMAF=1)
        target_link_libraries(codec_core PRIVATE vmaf)
    else()
        message(STATUS "codec-core: bundled libvmaf not found; VMAF will be unavailable")
    endif()
    target_link_libraries(codec_core PRIVATE mediandk z m dl)
else()
    target_sources(codec_core PRIVATE ffmpeg_backend_stub.cpp)
    message(STATUS "codec-core: FFmpeg prebuilts not found; building diagnostic backend")
endif()
