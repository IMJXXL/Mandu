#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <algorithm>
#include <thread>
#include <chrono>
#include <ncnn/net.h>
#include <ncnn/gpu.h>

#include "realesrgan_wrapper.h"
#include "manga_bw_postprocessor.h"

#define LOG_TAG "RealESRGANJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mangareader_sr_RealESRGANProcessor_nativeInit(
    JNIEnv *env, jobject thiz,
    jstring param_path, jstring bin_path, jint gpuid, jstring model_type, jboolean use_fp16, jint scale) {

    auto* wrapper = new RealESRGANWrapper();
    wrapper->useFp16 = (use_fp16 == JNI_TRUE);

    // 注意：GetStringUTFChars 拿到的指针，必须等所有用完再 Release，不能先放堆里
    const char *param = env->GetStringUTFChars(param_path, nullptr);
    const char *model = env->GetStringUTFChars(bin_path, nullptr);
    const char *type = env->GetStringUTFChars(model_type, nullptr);

    bool result = wrapper->load(param, model, gpuid, type, scale);

    // 如果 GPU 失败，留新模式重试（字符串此刻还在，存活期不会再穿了）
    if (!result && gpuid >= 0) {
        LOGW("GPU init failed, retrying with CPU");
        delete wrapper;
        wrapper = new RealESRGANWrapper();
        wrapper->useFp16 = (use_fp16 == JNI_TRUE);
        result = wrapper->load(param, model, -1, type, scale);
    }

    env->ReleaseStringUTFChars(param_path, param);
    env->ReleaseStringUTFChars(bin_path, model);
    env->ReleaseStringUTFChars(model_type, type);

    if (!result) {
        delete wrapper;
        return 0;
    }

    LOGI("RealESRGAN initialized: gpuid=%d, type=%s, fp16=%d", gpuid, wrapper->modelType.c_str(), wrapper->useFp16);
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jobject JNICALL
Java_com_mangareader_sr_RealESRGANProcessor_nativeProcess(
    JNIEnv *env, jobject thiz,
    jlong handle, jobject input_bitmap,
    jint scale, jfloat denoise_strength,
    jint gray_levels, jboolean density_correction) {

    if (handle == 0) {
        LOGE("Invalid handle");
        return nullptr;
    }

    auto* wrapper = reinterpret_cast<RealESRGANWrapper*>(handle);
    wrapper->scale = scale;

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, input_bitmap, &info);

    int in_w = info.width;
    int in_h = info.height;
    int out_w = in_w * scale;
    int out_h = in_h * scale;

    void *pixels;
    int lock_result = AndroidBitmap_lockPixels(env, input_bitmap, &pixels);
    if (lock_result != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock input bitmap pixels");
        return nullptr;
    }

    ncnn::Mat inimage(in_w, in_h, 3);
    const unsigned char* rgba_data = static_cast<const unsigned char*>(pixels);

    // 按实际 stride 取行（位图行对齐可能大于 width*4，否则整图扭曲错位）
    const size_t in_stride = info.stride > 0 ? (size_t)info.stride : (size_t)in_w * 4;

    const float mean_vals[3] = {0.0f, 0.0f, 0.0f};
    const float norm_vals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};

    for (int y = 0; y < in_h; y++) {
        const unsigned char* rgba_row = rgba_data + (size_t)y * in_stride;
        float* r_row = static_cast<float*>(inimage.channel(0).row(y));
        float* g_row = static_cast<float*>(inimage.channel(1).row(y));
        float* b_row = static_cast<float*>(inimage.channel(2).row(y));
        
        for (int x = 0; x < in_w; x++) {
            int idx = x * 4;
            r_row[x] = (rgba_row[idx + 0] - mean_vals[0]) * norm_vals[0];
            g_row[x] = (rgba_row[idx + 1] - mean_vals[1]) * norm_vals[1];
            b_row[x] = (rgba_row[idx + 2] - mean_vals[2]) * norm_vals[2];
        }
    }

    AndroidBitmap_unlockPixels(env, input_bitmap);

    ncnn::Mat outimage;
    bool success = wrapper->process(inimage, outimage);

    if (!success || outimage.empty()) {
        LOGE("RealESRGAN process failed");
        return nullptr;
    }

    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Failed to find Bitmap class");
        return nullptr;
    }

    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Failed to find Bitmap.Config class");
        return nullptr;
    }

    jstring config_name = env->NewStringUTF("ARGB_8888");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Failed to create config name string");
        return nullptr;
    }

    jmethodID value_of = env->GetStaticMethodID(config_class, "valueOf",
        "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
    if (env->ExceptionCheck() || value_of == nullptr) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(config_name);
        LOGE("Failed to find valueOf method");
        return nullptr;
    }

    jobject config = env->CallStaticObjectMethod(config_class, value_of, config_name);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(config_name);
        LOGE("Failed to call valueOf");
        return nullptr;
    }

    jmethodID create_bitmap = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (env->ExceptionCheck() || create_bitmap == nullptr) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(config_name);
        env->DeleteLocalRef(config);
        LOGE("Failed to find createBitmap method");
        return nullptr;
    }

    jobject output_bitmap = env->CallStaticObjectMethod(bitmap_class, create_bitmap,
        out_w, out_h, config);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(config_name);
        env->DeleteLocalRef(config);
        LOGE("Failed to create output bitmap");
        return nullptr;
    }

    void *out_pixels;
    lock_result = AndroidBitmap_lockPixels(env, output_bitmap, &out_pixels);
    if (lock_result != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock output bitmap pixels");
        env->DeleteLocalRef(config_name);
        env->DeleteLocalRef(config);
        env->DeleteLocalRef(bitmap_class);
        env->DeleteLocalRef(config_class);
        env->DeleteLocalRef(output_bitmap);
        return nullptr;
    }

    const float inv_norm_vals[3] = {255.0f, 255.0f, 255.0f};
    const float add_mean_vals[3] = {0.0f, 0.0f, 0.0f};

    AndroidBitmapInfo out_info;
    memset(&out_info, 0, sizeof(out_info));
    AndroidBitmap_getInfo(env, output_bitmap, &out_info);
    const size_t out_stride = out_info.stride > 0 ? (size_t)out_info.stride : (size_t)out_w * 4;

    unsigned char* out_rgba = static_cast<unsigned char*>(out_pixels);
    for (int y = 0; y < out_h; y++) {
        const float* r_row = static_cast<const float*>(outimage.channel(0).row(y));
        const float* g_row = static_cast<const float*>(outimage.channel(1).row(y));
        const float* b_row = static_cast<const float*>(outimage.channel(2).row(y));
        unsigned char* rgba_row = out_rgba + (size_t)y * out_stride;
        
        for (int x = 0; x < out_w; x++) {
            float r = r_row[x] * inv_norm_vals[0] + add_mean_vals[0];
            float g = g_row[x] * inv_norm_vals[1] + add_mean_vals[1];
            float b = b_row[x] * inv_norm_vals[2] + add_mean_vals[2];
            
            rgba_row[x * 4 + 0] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, r)));
            rgba_row[x * 4 + 1] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, g)));
            rgba_row[x * 4 + 2] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, b)));
            rgba_row[x * 4 + 3] = 255;
        }
    }

    if (gray_levels > 0) {
        processMangaBW(static_cast<unsigned char*>(out_pixels), out_w, out_h, gray_levels, density_correction == JNI_TRUE);
    }

    AndroidBitmap_unlockPixels(env, output_bitmap);

    env->DeleteLocalRef(config_name);
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(bitmap_class);
    env->DeleteLocalRef(config_class);

    LOGI("Process complete: %dx%d -> %dx%d", in_w, in_h, out_w, out_h);
    return output_bitmap;
}

JNIEXPORT void JNICALL
Java_com_mangareader_sr_RealESRGANProcessor_nativeRelease(
    JNIEnv *env, jobject thiz, jlong handle) {

    if (handle == 0) return;
    auto* wrapper = reinterpret_cast<RealESRGANWrapper*>(handle);
    wrapper->markInvalid();
    // Wait for in-flight process() calls to finish (up to 10s via condition variable)
    wrapper->waitForIdle();
    delete wrapper;
    LOGI("RealESRGAN released");
}

JNIEXPORT jint JNICALL
Java_com_mangareader_sr_VulkanHelper_nativeGetGpuCount(
    JNIEnv *env, jobject thiz) {
    return ncnn::get_gpu_count();
}

JNIEXPORT jstring JNICALL
Java_com_mangareader_sr_VulkanHelper_nativeGetDeviceInfo(
    JNIEnv *env, jobject thiz, jint gpuid) {
    if (gpuid < 0 || gpuid >= ncnn::get_gpu_count()) {
        return env->NewStringUTF("Unknown");
    }
    return env->NewStringUTF(ncnn::get_gpu_info(gpuid).device_name());
}

// 黑白优化专用入口：不做放大，仅运行漫画黑白后处理（灰阶量化+网点密度校正）。
// 原尺寸输入输出，纯 CPU 毫秒级，供"只开黑白优化、不开超分放大"的场景。
JNIEXPORT jobject JNICALL
Java_com_mangareader_sr_SrBwProcessor_nativeProcessBW(
    JNIEnv *env, jclass /*clazz*/, jobject input_bitmap, jint gray_levels, jboolean density_correction) {

    if (gray_levels <= 1) return nullptr;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, input_bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;

    void* src_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, input_bitmap, &src_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    const int w = (int) info.width;
    const int h = (int) info.height;
    std::vector<unsigned char> buf((size_t) w * h * 4);
    memcpy(buf.data(), src_pixels, buf.size());
    AndroidBitmap_unlockPixels(env, input_bitmap);

    processMangaBW(buf.data(), w, h, gray_levels, density_correction == JNI_TRUE);

    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    jstring config_name = env->NewStringUTF("ARGB_8888");
    jmethodID value_of = env->GetStaticMethodID(config_class, "valueOf",
        "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
    jobject config = env->CallStaticObjectMethod(config_class, value_of, config_name);
    jmethodID create_bitmap = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject output_bitmap = env->CallStaticObjectMethod(bitmap_class, create_bitmap, w, h, config);
    if (output_bitmap == nullptr) return nullptr;
    void* out_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, output_bitmap, &out_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    memcpy(out_pixels, buf.data(), buf.size());
    AndroidBitmap_unlockPixels(env, output_bitmap);

    env->DeleteLocalRef(config_name);
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(bitmap_class);
    env->DeleteLocalRef(config_class);
    return output_bitmap;
}

}
