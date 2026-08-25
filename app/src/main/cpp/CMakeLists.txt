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
