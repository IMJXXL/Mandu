cmake_minimum_required(VERSION 3.22.1)
project("mangareader")

# 隐藏符号，反编译时看不到函数名
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# ── Rust Corrosion 集成（AnimatedWebP Rust Decoder，可选，需网络） ──
option(ENABLE_RUST_WEBP "Enable Rust WebP animated decoder (requires network for Corrosion + crates)" OFF)
if(ENABLE_RUST_WEBP)
    include(FetchContent)
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ../../../../rust-toolchain.toml)
    unset(Rust_TOOLCHAIN CACHE)
    FetchContent_Declare(
            Corrosion
            GIT_REPOSITORY https://github.com/FooIbar/corrosion.git
            GIT_TAG v0.6.1
            GIT_SHALLOW 1
    )
    FetchContent_MakeAvailable(Corrosion)
    corrosion_import_crate(
            MANIFEST_PATH ../rust/Cargo.toml
            LOCKED
            FEATURES $<IF:$<VERSION_GREATER_EQUAL:${ANDROID_NATIVE_API_LEVEL},26>,android-26,android>
            FLAGS $<$<NOT:$<CONFIG:Debug>>:-Z build-std -Z build-std-features=>
    )
    corrosion_add_target_rustflags(mangareader_rust $<$<NOT:$<CONFIG:Debug>>:-Z unstable-options -C panic=immediate-abort -Z location-detail=none -Z fmt-debug=none>)
    corrosion_link_libraries(mangareader_rust android jnigraphics log webp webpdemux)

    # ★ Rust WebP animated decoder shared library
    add_library(webp_decoder SHARED webp_jni_dummy.c)
    target_link_libraries(webp_decoder mangareader_rust android log)
endif()

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

# 超分引擎：NCNN Vulkan（NaModelsmi超分项目移植，Apache-2.0）
add_library(sr_engine SHARED
    sr/realesrgan_jni.cpp
    sr/realesrgan_wrapper.cpp
    sr/manga_bw_postprocessor.cpp
    sr/manga_cleanup.cpp
)
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

# ── QNN/HTP（高通 NPU）后端 ──
# 头文件 vendor 在 include/QNN（QAIRT 2.49 官方 API 头，MIT 许可）；运行时 .so 一律
# 运行期 dlopen（Gradle 从 Maven 拉 com.qualcomm.qti:qnn-runtime 打进 APK），绝不链接。
# 仅 arm64-v8a 编入（QNN 运行时只有 arm64）；其余 ABI 走桩：探测不可用、process 返回 null。
# JNI 胶水始终编入（桩版函数齐全），Kotlin 侧无需感知差异。
target_sources(sr_engine PRIVATE sr/qnn_jni.cpp)
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/include/QNN/QnnInterface.h")
    target_compile_definitions(sr_engine PRIVATE MIHON_ENABLE_QNN=1)
    target_include_directories(sr_engine PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include/QNN")
    target_sources(sr_engine PRIVATE sr/qnn_backend.cpp)
else()
    target_compile_definitions(sr_engine PRIVATE MIHON_ENABLE_QNN=0)
endif()

# ── libarchive stream (SMB archive reading, from reference project LocalViewer 1.19) ──
# archive.c provides JNI bridge for stream-based archive extraction (openArchiveStream/extractToByteBuffer etc.)
# Links to libarchive-jni.so (from zhanghai libarchive-android.aar) which contains libarchive C API symbols.
add_library(libarchive_jni SHARED IMPORTED)
set_target_properties(libarchive_jni PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libarchive_libs/${ANDROID_ABI}/libarchive-jni.so
)

add_library(archive_stream SHARED archive.c natsort/strnatcmp.c)
target_include_directories(archive_stream PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libarchive_include
)
target_link_libraries(archive_stream
    android log z
    libarchive_jni
)

add_library(lanczos_engine SHARED sr/lanczos3.cpp)
target_compile_features(lanczos_engine PRIVATE cxx_std_17)
target_link_libraries(lanczos_engine android log jnigraphics)
add_library(eink_dither SHARED eink_dither.cpp)
target_link_libraries(eink_dither android log jnigraphics)
