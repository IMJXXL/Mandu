// 快速优化档（1x 算法清理）：双边滤波去噪 + 锐化掩模拉线条，纯 CPU，原图尺寸毫秒级。
// 自研实现（算法为教科书标准件：bilateral filter + unsharp mask），不依赖 ncnn/Vulkan。
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <vector>

#ifdef _OPENMP
#include <omp.h>
#endif

#define LOG_TAG "SrCleanup"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 强度参数（Kotlin 侧 0=中 1=轻 2=强）：半径/范围σ/锐化量
struct Params { int radius; float sigmaRange; float amount; };

Params paramsFor(int strength) {
    switch (strength) {
        case 1:  return {2, 15.f, 0.4f};  // 轻
        case 2:  return {4, 40.f, 0.8f};  // 强
        default: return {3, 25.f, 0.6f};  // 中
    }
}

inline unsigned char clamp255(float v) {
    return v < 0.f ? 0 : (v > 255.f ? 255 : (unsigned char)v);
}

// 双边滤波：范围权重用亮度差（避免彩色边缘渗色），空间核/范围核均预计算
void bilateral(const std::vector<unsigned char>& in, std::vector<unsigned char>& out,
               int w, int h, const Params& p) {
    const int r = p.radius;
    const float inv2sr2 = 1.f / (2.f * p.sigmaRange * p.sigmaRange);
    const int win = 2 * r + 1;
    std::vector<float> spatial((size_t)win * win);
    const float inv2ss2 = 1.f / (2.f * r * r * 0.5f);
    for (int dy = -r; dy <= r; dy++)
        for (int dx = -r; dx <= r; dx++)
            spatial[(size_t)(dy + r) * win + (dx + r)] =
                std::exp(-(float)(dx * dx + dy * dy) * inv2ss2);
    std::vector<float> rangeLut(256);
    for (int i = 0; i < 256; i++) rangeLut[i] = std::exp(-(float)(i * i) * inv2sr2);

    const size_t stride = (size_t)w * 4;
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const unsigned char* c = &in[y * stride + (size_t)x * 4];
            const float cl = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2];
            float wsum = 0.f, sr = 0.f, sg = 0.f, sb = 0.f;
            for (int dy = -r; dy <= r; dy++) {
                int yy = y + dy; if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
                const unsigned char* row = &in[yy * stride];
                for (int dx = -r; dx <= r; dx++) {
                    int xx = x + dx; if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
                    const unsigned char* s = &row[(size_t)xx * 4];
                    const float sl = 0.299f * s[0] + 0.587f * s[1] + 0.114f * s[2];
                    const int dl = (int)std::fabs(sl - cl);
                    const float wt = spatial[(size_t)(dy + r) * win + (dx + r)] * rangeLut[dl];
                    wsum += wt; sr += wt * s[0]; sg += wt * s[1]; sb += wt * s[2];
                }
            }
            unsigned char* d = &out[y * stride + (size_t)x * 4];
            d[0] = clamp255(sr / wsum); d[1] = clamp255(sg / wsum);
            d[2] = clamp255(sb / wsum); d[3] = c[3];
        }
    }
}

// 盒式模糊（水平+垂直两趟，近似高斯）——锐化掩模的底图
void boxBlur(const std::vector<unsigned char>& in, std::vector<unsigned char>& tmp,
             std::vector<unsigned char>& out, int w, int h, int r) {
    const size_t stride = (size_t)w * 4;
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float acc[3] = {0.f, 0.f, 0.f}; int n = 0;
            for (int dx = -r; dx <= r; dx++) {
                int xx = x + dx; if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
                const unsigned char* s = &in[y * stride + (size_t)xx * 4];
                acc[0] += s[0]; acc[1] += s[1]; acc[2] += s[2]; n++;
            }
            unsigned char* d = &tmp[y * stride + (size_t)x * 4];
            d[0] = (unsigned char)(acc[0] / n); d[1] = (unsigned char)(acc[1] / n);
            d[2] = (unsigned char)(acc[2] / n); d[3] = in[y * stride + (size_t)x * 4 + 3];
        }
    }
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float acc[3] = {0.f, 0.f, 0.f}; int n = 0;
            for (int dy = -r; dy <= r; dy++) {
                int yy = y + dy; if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
                const unsigned char* s = &tmp[yy * stride + (size_t)x * 4];
                acc[0] += s[0]; acc[1] += s[1]; acc[2] += s[2]; n++;
            }
            unsigned char* d = &out[y * stride + (size_t)x * 4];
            d[0] = (unsigned char)(acc[0] / n); d[1] = (unsigned char)(acc[1] / n);
            d[2] = (unsigned char)(acc[2] / n); d[3] = tmp[y * stride + (size_t)x * 4 + 3];
        }
    }
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_mangareader_sr_SrCleanupProcessor_nativeCleanup(
        JNIEnv* env, jclass, jobject input_bitmap, jint strength) {

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, input_bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;
    const int w = (int)info.width, h = (int)info.height;
    if (w <= 0 || h <= 0 || (long long)w * h > 4096LL * 4096LL) return nullptr;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, input_bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;

    const size_t bufBytes = (size_t)w * h * 4;
    std::vector<unsigned char> src(bufBytes);
    memcpy(src.data(), pixels, bufBytes);
    AndroidBitmap_unlockPixels(env, input_bitmap);

    const Params p = paramsFor(strength);
    try {
        // ① 双边滤波去噪
        std::vector<unsigned char> base(bufBytes);
        bilateral(src, base, w, h, p);

        // ② 锐化掩模：out = base + amount * (base - blur(base))
        std::vector<unsigned char> blurTmp(bufBytes), blur(bufBytes);
        boxBlur(base, blurTmp, blur, w, h, 1);
        const float amt = p.amount;
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                unsigned char* d = &base[y * (size_t)w * 4 + (size_t)x * 4];
                const unsigned char* b = &blur[y * (size_t)w * 4 + (size_t)x * 4];
                for (int c = 0; c < 3; c++)
                    d[c] = clamp255((float)d[c] + amt * ((float)d[c] - (float)b[c]));
            }
        }

        // ③ 产出新 Bitmap（与 realesrgan_jni 相同的创建模式）
        jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
        jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
        jstring configName = env->NewStringUTF("ARGB_8888");
        jmethodID valueOf = env->GetStaticMethodID(configClass, "valueOf",
            "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
        jobject config = env->CallStaticObjectMethod(configClass, valueOf, configName);
        jmethodID createBitmap = env->GetStaticMethodID(bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        jobject output = env->CallStaticObjectMethod(bitmapClass, createBitmap, w, h, config);
        if (!output) return nullptr;

        void* outPixels = nullptr;
        if (AndroidBitmap_lockPixels(env, output, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            env->DeleteLocalRef(output);
            return nullptr;
        }
        memcpy(outPixels, base.data(), bufBytes);
        AndroidBitmap_unlockPixels(env, output);
        return output;
    } catch (const std::bad_alloc&) {
        LOGE("cleanup alloc failed (%dx%d)", w, h);
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}
