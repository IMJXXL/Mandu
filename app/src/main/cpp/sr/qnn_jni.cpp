// QNN/HTP（高通 NPU）JNI 胶水层。
// 后端实现移植自 Komiho / HaoweiLi97 mihon_img_upscale（Apache-2.0，见 qnn_backend.cpp）。
// MANGA_ENABLE_QNN=0（非 arm64-v8a 或未 vendor 头文件）时全部编成桩：
// 探测返回不可用、process 原样返回 null（调用方回退原图），绝不会缺符号崩溃。
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "qnn_backend.h"

#define TAG "QnnJni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if !defined(MIHON_ENABLE_QNN)
#define MIHON_ENABLE_QNN 0
#endif

namespace {
std::mutex g_lock;
std::atomic<int> g_progress{0};
std::atomic<bool> g_abort{false};
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_mangareader_sr_QnnEngine_nativeEnsureDspPath(JNIEnv *env, jobject /*thiz*/,
                                                      jstring libDir) {
#if MIHON_ENABLE_QNN
  if (libDir == nullptr) {
    qnn_backend::ensure_dsp_path(nullptr);
    return;
  }
  const char *chars = env->GetStringUTFChars(libDir, nullptr);
  if (chars == nullptr) return;
  qnn_backend::ensure_dsp_path(chars);
  env->ReleaseStringUTFChars(libDir, chars);
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mangareader_sr_QnnEngine_nativeIsRuntimeAvailable(JNIEnv * /*env*/, jobject /*thiz*/) {
#if MIHON_ENABLE_QNN
  return qnn_backend::is_runtime_loadable() ? JNI_TRUE : JNI_FALSE;
#else
  return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mangareader_sr_QnnEngine_nativeGetArchitecture(JNIEnv * /*env*/, jobject /*thiz*/) {
#if MIHON_ENABLE_QNN
  return static_cast<jint>(qnn_backend::architecture());
#else
  return 0;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mangareader_sr_QnnEngine_nativeGetLastInitStage(JNIEnv * /*env*/, jobject /*thiz*/) {
#if MIHON_ENABLE_QNN
  return static_cast<jint>(qnn_backend::last_init_stage());
#else
  return 0;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mangareader_sr_QnnEngine_nativeInitQnn(JNIEnv *env, jobject /*thiz*/,
                                                jstring contextPath, jstring libDir,
                                                jint padding) {
#if MIHON_ENABLE_QNN
  if (contextPath == nullptr) return JNI_FALSE;
  const char *ctxChars = env->GetStringUTFChars(contextPath, nullptr);
  if (ctxChars == nullptr) return JNI_FALSE;
  std::string ctx(ctxChars);
  env->ReleaseStringUTFChars(contextPath, ctxChars);

  std::string libDirStr;
  if (libDir != nullptr) {
    const char *libChars = env->GetStringUTFChars(libDir, nullptr);
    if (libChars != nullptr) {
      libDirStr = libChars;
      env->ReleaseStringUTFChars(libDir, libChars);
    }
  }

  std::lock_guard<std::mutex> lock(g_lock);
  g_abort.store(false);
  // ensure_dsp_path 必须先于任何 dlopen("libQnnHtp.so")；探测阶段已调过一次，
  // 这里再调一次保证 initialize 路径自洽（幂等）。
  qnn_backend::ensure_dsp_path(libDirStr.empty() ? nullptr : libDirStr.c_str());
  const bool ok = qnn_backend::initialize(ctx, static_cast<int>(padding));
  LOGD("QNN init %s (padding=%d)", ok ? "ok" : "FAILED", static_cast<int>(padding));
  return ok ? JNI_TRUE : JNI_FALSE;
#else
  (void)env; (void)contextPath; (void)libDir; (void)padding;
  return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mangareader_sr_QnnEngine_nativeIsQnnInitialized(JNIEnv * /*env*/, jobject /*thiz*/) {
#if MIHON_ENABLE_QNN
  return qnn_backend::is_initialized() ? JNI_TRUE : JNI_FALSE;
#else
  return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mangareader_sr_QnnEngine_nativeGetQnnScale(JNIEnv * /*env*/, jobject /*thiz*/) {
#if MIHON_ENABLE_QNN
  return static_cast<jint>(qnn_backend::scale());
#else
  return 0;
#endif
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_mangareader_sr_QnnEngine_nativeProcessQnn(JNIEnv *env, jobject /*thiz*/,
                                                   jobject bitmap) {
#if MIHON_ENABLE_QNN
  if (bitmap == nullptr) return nullptr;
  if (!qnn_backend::is_initialized()) return nullptr;

  AndroidBitmapInfo info{};
  if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
  // 仅接受 RGBA_8888（QnnProcessor 在 Kotlin 侧已保证；防御性再查一次）
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;

  const int w = static_cast<int>(info.width);
  const int h = static_cast<int>(info.height);
  const int out_scale = qnn_backend::scale();
  if (out_scale <= 0) return nullptr;

  // 拷出打包 RGBA（释放原位图锁，避免推理期间长时间钉住输入缓冲）
  std::vector<uint8_t> packed(static_cast<size_t>(w) * h * 4);
  {
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return nullptr;
    for (int y = 0; y < h; y++) {
      memcpy(packed.data() + static_cast<size_t>(y) * w * 4,
             static_cast<const uint8_t *>(pixels) + static_cast<size_t>(y) * info.stride,
             static_cast<size_t>(w) * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
  }

  // 产出位图
  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  jmethodID createBitmap = env->GetStaticMethodID(
      bitmapClass, "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
  jfieldID configField = env->GetStaticFieldID(configClass, "ARGB_8888",
                                               "Landroid/graphics/Bitmap$Config;");
  jobject config = env->GetStaticObjectField(configClass, configField);
  jobject outBitmap = env->CallStaticObjectMethod(
      bitmapClass, createBitmap,
      static_cast<jint>(w * out_scale), static_cast<jint>(h * out_scale), config);
  if (outBitmap == nullptr) return nullptr;

  AndroidBitmapInfo outInfo{};
  if (AndroidBitmap_getInfo(env, outBitmap, &outInfo) < 0) {
    env->DeleteLocalRef(outBitmap);
    return nullptr;
  }
  void *outPixels = nullptr;
  if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != 0) {
    env->DeleteLocalRef(outBitmap);
    return nullptr;
  }

  g_progress.store(0);
  const auto start = std::chrono::steady_clock::now();
  const int ret = qnn_backend::process_rgba(
      packed.data(), w, h, w * 4,
      static_cast<uint8_t *>(outPixels), static_cast<int>(outInfo.stride),
      &g_progress, &g_abort);
  AndroidBitmap_unlockPixels(env, outBitmap);
  const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::steady_clock::now() - start).count();
  LOGD("QNN process %s in %lld ms (%dx%d -> %dx%d)",
       ret == 0 ? "ok" : (g_abort.load() ? "aborted" : "FAILED"),
       static_cast<long long>(ms), w, h, w * out_scale, h * out_scale);
  if (ret != 0) {
    env->DeleteLocalRef(outBitmap);
    return nullptr;
  }
  return outBitmap;
#else
  (void)env; (void)bitmap;
  return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_mangareader_sr_QnnEngine_nativeAbortQnn(JNIEnv * /*env*/, jobject /*thiz*/) {
  g_abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mangareader_sr_QnnEngine_nativeClearAbortQnn(JNIEnv * /*env*/, jobject /*thiz*/) {
  g_abort.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mangareader_sr_QnnEngine_nativeShutdownQnn(JNIEnv * /*env*/, jobject /*thiz*/) {
#if MIHON_ENABLE_QNN
  std::lock_guard<std::mutex> lock(g_lock);
  qnn_backend::shutdown();
  g_abort.store(false);
  g_progress.store(0);
#endif
}
