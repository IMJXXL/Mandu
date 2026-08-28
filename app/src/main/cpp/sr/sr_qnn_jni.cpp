#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <cstdlib>
#include <string>
#include <dlfcn.h>

#if MIHON_ENABLE_QNN

// QNN NPU 后端 JNI：QnnRuntime 从 SDK 的 so 加载。libQnn*.so 已在 jniLibs arm64 里
#include "qnn_backend.h"

#define LOG_TAG "SrQnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// ADSP_LIBRARY_PATH 必须先设（由 App 侧启动时通过 android_set_LD_LIBRARY_PATH 或 System.loadLibrary 好）
// 这里从 libQnnHtp.so 所在包里拿自己的路径
static std::string get_self_lib_dir() {
    // 通过 dladdr 找 libsr_engine.so 所在目录（即 jniLibs 解压目录）
    Dl_info info;
    if (dladdr((void*)&get_self_lib_dir, &info) && info.dli_fname) {
        std::string path = info.dli_fname;
        auto pos = path.find_last_of('/');
        if (pos != std::string::npos) return path.substr(0, pos);
    }
    return "";
}

// SoC 检测+NVPU 可读性探测（不初始化）
JNIEXPORT jboolean JNICALL
Java_com_mangareader_sr_SrNpuProcessor_nativeProbe(JNIEnv*, jclass) {
    // 这个探测只是试探性 dlopen
    return qnn_backend::is_runtime_loadable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mangareader_sr_SrNpuProcessor_nativeInit(JNIEnv* env, jclass, jstring context_path, jint padding, jstring j_lib_dir) {
    // ADSP_LIBRARY_PATH / LD_LIBRARY_PATH 指向我们解包出来的 lib 目录（QNN 用它们找 skel）
    const char* lib_dir = j_lib_dir ? env->GetStringUTFChars(j_lib_dir, nullptr) : nullptr;
    if (lib_dir && lib_dir[0]) {
        std::string env1 = std::string("ADSP_LIBRARY_PATH=") + lib_dir;
        std::string env2 = std::string("LD_LIBRARY_PATH=") + lib_dir;
        putenv(const_cast<char*>(env1.c_str()));
        putenv(const_cast<char*>(env2.c_str()));
        LOGI("ADSP/LD_LIBRARY_PATH = %s", lib_dir);
    }
    if (lib_dir) env->ReleaseStringUTFChars(j_lib_dir, lib_dir);
    const char* path = env->GetStringUTFChars(context_path, nullptr);
    bool ok = qnn_backend::initialize(std::string(path), padding);
    env->ReleaseStringUTFChars(context_path, path);
    LOGI("QNN init %s: %s", ok ? "OK" : "FAIL", path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_mangareader_sr_SrNpuProcessor_nativeProcess(JNIEnv* env, jclass, jobject input_bitmap) {
    if (!qnn_backend::is_initialized()) return nullptr;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, input_bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, input_bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;

    const int in_w = static_cast<int>(info.width);
    const int in_h = static_cast<int>(info.height);
    const int out_scale = 2;
    const int out_w = in_w * out_scale;
    const int out_h = in_h * out_scale;

    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    jstring config_name = env->NewStringUTF("ARGB_8888");
    jmethodID value_of = env->GetStaticMethodID(config_class, "valueOf", "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
    jobject config = env->CallStaticObjectMethod(config_class, value_of, config_name);
    jmethodID create_bitmap = env->GetStaticMethodID(bitmap_class, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject output_bitmap = env->CallStaticObjectMethod(bitmap_class, create_bitmap, out_w, out_h, config);
    if (!output_bitmap) {
        AndroidBitmap_unlockPixels(env, input_bitmap);
        return nullptr;
    }
    void* out_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, output_bitmap, &out_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, input_bitmap);
        return nullptr;
    }

    std::atomic<int> progress{0};
    std::atomic<bool> abort_flag{false};
    int res = qnn_backend::process_rgba(
        static_cast<const uint8_t*>(pixels), in_w, in_h, in_w * 4,
        static_cast<uint8_t*>(out_pixels), out_w * 4,
        &progress, &abort_flag);

    AndroidBitmap_unlockPixels(env, output_bitmap);
    AndroidBitmap_unlockPixels(env, input_bitmap);
    env->DeleteLocalRef(config_name);
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(config_class);
    env->DeleteLocalRef(bitmap_class);
    return (res == 0) ? output_bitmap : nullptr;
}

JNIEXPORT void JNICALL
Java_com_mangareader_sr_SrNpuProcessor_nativeRelease(JNIEnv*, jclass) {
    qnn_backend::shutdown();
}

} // extern "C"

#endif // MIHON_ENABLE_QNN
