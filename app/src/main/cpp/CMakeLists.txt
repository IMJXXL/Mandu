cmake_minimum_required(VERSION 3.22.1)
project("mangareader")

# 隐藏符号，反编译时看不到函数名
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# libmobi static library
add_library(mobi STATIC
    libmobi/buffer.c
    libmobi/compression.c
    libmobi/debug.c
    libmobi/encryption.c
    libmobi/index.c
    libmobi/memory.c
    libmobi/meta.c
    libmobi/miniz.c
    libmobi/opf.c
    libmobi/parse_rawml.c
    libmobi/randombytes.c
    libmobi/read.c
    libmobi/sha1.c
    libmobi/structure.c
    libmobi/util.c
    libmobi/write.c
    libmobi/xmlwriter.c
)
target_include_directories(mobi PRIVATE libmobi)
target_compile_definitions(mobi PRIVATE HAVE_MINIZ)
target_compile_options(mobi PRIVATE -O2 -ffunction-sections -fdata-sections)

add_library(native_renderer SHARED renderer.c)
target_link_libraries(native_renderer android log jnigraphics m)

add_library(native_engine SHARED native_engine.cpp jni_bridge.cpp libzip_wrapper.cpp pdfium_wrapper.cpp)
target_include_directories(native_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

add_library(libzip SHARED IMPORTED)
set_target_properties(libzip PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libzip.so)

target_link_libraries(native_engine android log jnigraphics z m mobi dl libzip)

add_library(native_reader SHARED native_reader.cpp jni_reader.cpp)
target_link_libraries(native_reader android log jnigraphics m)

# QNN NPU 后端（骁龙8+ 及以上）：头文件路径由 gradle 传入 -DQNN_SDK_DIR=...
set(QNN_SDK_DIR "" CACHE PATH "Path to Qualcomm QNN (QAIRT) SDK root")
set(QNN_ENABLED OFF)
if(QNN_SDK_DIR AND EXISTS "${QNN_SDK_DIR}/include/QNN/QnnInterface.h" AND ${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(QNN_ENABLED ON)
    message(STATUS "QNN enabled, headers at ${QNN_SDK_DIR}/include")
endif()

# 超分引擎：NCNN Vulkan + 可选 QNN NPU（移植自 mihon-super-resolution / mihon_img_upscale，Apache-2.0）
add_library(sr_engine SHARED
    sr/realesrgan_jni.cpp
    sr/realesrgan_wrapper.cpp
    sr/manga_bw_postprocessor.cpp
    sr/qnn_backend.cpp
    sr/sr_qnn_jni.cpp
)
if(QNN_ENABLED)
    target_compile_definitions(sr_engine PRIVATE MIHON_ENABLE_QNN=1)
    target_include_directories(sr_engine PRIVATE "${QNN_SDK_DIR}/include/QNN")
else()
    target_compile_definitions(sr_engine PRIVATE MIHON_ENABLE_QNN=0)
endif()
target_include_directories(sr_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/sr/ncnn/include)
target_compile_features(sr_engine PRIVATE cxx_std_17)
# ncnn 静态库带 OpenMP 引用，需要 -fopenmp 编译 + 静态链接 OpenMP 运行时
target_compile_options(sr_engine PRIVATE -fopenmp)
target_link_options(sr_engine PRIVATE -fopenmp -static-openmp)
target_link_libraries(sr_engine
    ${CMAKE_CURRENT_SOURCE_DIR}/sr/ncnn/${ANDROID_ABI}/lib/libncnn.a
    ${CMAKE_CURRENT_SOURCE_DIR}/sr/ncnn/${ANDROID_ABI}/lib/libglslang.a
    ${CMAKE_CURRENT_SOURCE_DIR}/sr/ncnn/${ANDROID_ABI}/lib/libOSDependent.a
    ${CMAKE_CURRENT_SOURCE_DIR}/sr/ncnn/${ANDROID_ABI}/lib/libSPIRV.a
    ${CMAKE_CURRENT_SOURCE_DIR}/sr/ncnn/${ANDROID_ABI}/lib/libGenericCodeGen.a
    ${CMAKE_CURRENT_SOURCE_DIR}/sr/ncnn/${ANDROID_ABI}/lib/libMachineIndependent.a
    ${CMAKE_CURRENT_SOURCE_DIR}/sr/ncnn/${ANDROID_ABI}/lib/libglslang-default-resource-limits.a
    android log jnigraphics vulkan
)
