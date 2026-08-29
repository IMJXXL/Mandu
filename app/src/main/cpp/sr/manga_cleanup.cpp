// 快速优化档（1x 算法清理）：引导滤波去噪 + 锐化掩模拉线条，纯 CPU，原图尺寸毫秒级。
// v2（2026-08-29）：双边滤波 → 引导滤波（He et al. 2010 标准件，滑动窗口盒滤波 O(1)/px），
//   分带处理控内存（每带 ~9MB）；双边保留为 OOM 兜底路径。算法均为教科书标准件，自研集成。
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#ifdef _OPENMP
#include <omp.h>
#endif

#define LOG_TAG "SrCleanup"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 强度参数（Kotlin 侧 0=中 1=轻 2=强）：引导滤波 半径/eps（I 归一化到 [0,1]）
struct GParams { int radius; float eps; };

GParams gparamsFor(int strength) {
    // ★ eps 是"局部对比度保留度"的倒数域，不是去噪量：取值必须在 ~0.002-0.02，
    //   大了会整体压缩局部对比度（整页发灰蒙雾）。平坦噪点 var≈(8/255)^2≈0.001，
    //   eps=0.008 时 a≈0.11 平坦区平滑；线条边缘 var≈0.15 → a≈0.95 对比保留。
    switch (strength) {
        case 1:  return {2, 0.002f};  // 轻
        case 2:  return {8, 0.02f};   // 强（大半径补偿去噪量）
        default: return {4, 0.008f};  // 中
    }
}

// 旧双边参数（OOM 兜底路径用）
struct Params { int radius; float sigmaRange; float amount; };
Params paramsFor(int strength) {
    switch (strength) {
        case 1:  return {2, 15.f, 0.4f};
        case 2:  return {4, 40.f, 0.8f};
        default: return {3, 25.f, 0.6f};
    }
}

inline unsigned char clamp255(float v) {
    return v < 0.f ? 0 : (v > 255.f ? 255 : (unsigned char)v);
}

// ---- 盒式滤波（半径 r，滑动窗口，O(1)/px）。in 与 out 不可混用；tmp 为调用方复用的暂存平面 ----
void boxFilterPlane(const float* in, float* tmp, float* out, int w, int h, int r) {
    // 水平：in → tmp
    for (int y = 0; y < h; y++) {
        const float* row = &in[(size_t)y * w];
        float* dst = &tmp[(size_t)y * w];
        const int initHi = std::min(r, w - 1);
        float s = 0.f;
        for (int k = 0; k <= initHi; k++) s += row[k];
        int lastLo = 0, lastHi = initHi;
        for (int x = 0; x < w; x++) {
            const int hi = std::min(x + r, w - 1);
            const int lo = std::max(0, x - r);
            while (lastHi < hi) s += row[++lastHi];
            while (lastLo < lo) s -= row[lastLo++];
            dst[x] = s / (hi - lo + 1);
        }
    }
    // 垂直：tmp → out
    std::vector<float> col((size_t)h);
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) col[y] = tmp[(size_t)y * w + x];
        const int initHi = std::min(r, h - 1);
        float s = 0.f;
        for (int k = 0; k <= initHi; k++) s += col[k];
        int lastLo = 0, lastHi = initHi;
        for (int y = 0; y < h; y++) {
            const int hi = std::min(y + r, h - 1);
            const int lo = std::max(0, y - r);
            while (lastHi < hi) s += col[++lastHi];
            while (lastLo < lo) s -= col[lastLo++];
            out[(size_t)y * w + x] = s / (hi - lo + 1);
        }
    }
}

// 引导滤波（亮度 I 引导，逐通道 p），带内 [y0,y1) 有效、[y0-r,y1+r) 参与，跨带结果与全页一致。
void guidedBand(const std::vector<unsigned char>& in, std::vector<unsigned char>& out,
                int w, int h, int y0, int y1, int r, float eps,
                std::vector<float>& scratch) {
    const int by0 = y0 - r < 0 ? 0 : y0 - r;
    const int by1 = y1 + r > h ? h : y1 + r;
    const int bh = by1 - by0;
    const size_t plane = (size_t)bh * w;
    if (scratch.size() < plane * 11) scratch.resize(plane * 11);

    float* I   = scratch.data();
    float* mI  = I + plane;
    float* mII = mI + plane;
    float* pP  = mII + plane;
    float* mIp = pP + plane;
    float* mp  = mIp + plane;
    float* aP  = mp + plane;
    float* bP  = aP + plane;
    float* mA  = bP + plane;
    float* mB  = mA + plane;
    std::vector<float> tmpPlane(plane);   // 盒滤波读写隔离暂存（每带重建，成本可忽略）

    // I = 亮度 [0,1]
    for (int y = 0; y < bh; y++) {
        const unsigned char* row = &in[(size_t)(by0 + y) * w * 4];
        float* irow = I + (size_t)y * w;
        for (int x = 0; x < w; x++) {
            irow[x] = (0.299f * row[x*4] + 0.587f * row[x*4+1] + 0.114f * row[x*4+2]) / 255.f;
        }
    }
    boxFilterPlane(I, tmpPlane.data(), mI, w, bh, r);                 // mean(I)
    for (size_t i = 0; i < plane; i++) mII[i] = I[i] * I[i];
    boxFilterPlane(mII, tmpPlane.data(), mII, w, bh, r);              // 暂存区隔离，防读写混叠
    for (size_t i = 0; i < plane; i++) mII[i] -= mI[i] * mI[i];  // var(I)

    for (int c = 0; c < 3; c++) {
        for (int y = 0; y < bh; y++) {
            const unsigned char* row = &in[(size_t)(by0 + y) * w * 4];
            float* prow = pP + (size_t)y * w;
            for (int x = 0; x < w; x++) prow[x] = row[x*4 + c] / 255.f;
        }
        boxFilterPlane(pP, tmpPlane.data(), mp, w, bh, r);            // mean(p)
        for (size_t i = 0; i < plane; i++) mIp[i] = I[i] * pP[i];
        boxFilterPlane(mIp, tmpPlane.data(), mIp, w, bh, r);          // mean(I*p)
        for (size_t i = 0; i < plane; i++) {
            const float cov = mIp[i] - mI[i] * mp[i];
            aP[i] = cov / (mII[i] + eps);
            bP[i] = mp[i] - aP[i] * mI[i];
        }
        boxFilterPlane(aP, tmpPlane.data(), mA, w, bh, r);            // mean(a)
        boxFilterPlane(bP, tmpPlane.data(), mB, w, bh, r);            // mean(b)
        for (int y = y0; y < y1; y++) {
            const int ly = y - by0;
            const float* irow = I + (size_t)ly * w;
            const float* arow = mA + (size_t)ly * w;
            const float* brow = mB + (size_t)ly * w;
            unsigned char* drow = &out[(size_t)y * w * 4];
            for (int x = 0; x < w; x++) {
                drow[x*4 + c] = clamp255((arow[x] * irow[x] + brow[x]) * 255.f);
            }
        }
        // ★ alpha 通道引导滤波不处理，必须从源图拷回——否则输出全透明（静默失效漏洞）
        for (int y = y0; y < y1; y++) {
            const unsigned char* srow = &in[(size_t)y * w * 4];
            unsigned char* drow = &out[(size_t)y * w * 4];
            for (int x = 0; x < w; x++) drow[x*4 + 3] = srow[x*4 + 3];
        }
    }
}

// 旧双边（OOM 兜底保留）
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

    try {
        std::vector<unsigned char> base(bufBytes);
        const GParams gp = gparamsFor(strength);
        bool guidedOk = true;
        try {
            std::vector<float> scratch;   // 带内工作平面，跨带复用
            const int BAND = 128;
            for (int y0 = 0; y0 < h && guidedOk; y0 += BAND) {
                const int y1 = y0 + BAND > h ? h : y0 + BAND;
                guidedBand(src, base, w, h, y0, y1, gp.radius, gp.eps, scratch);
            }
        } catch (const std::bad_alloc&) {
            guidedOk = false;
        }
        if (!guidedOk) {
            bilateral(src, base, w, h, paramsFor(strength));  // OOM 兜底：旧双边
        }

        // 锐化掩模
        std::vector<unsigned char> blurTmp(bufBytes), blur(bufBytes);
        boxBlur(base, blurTmp, blur, w, h, 1);
        const float amt = paramsFor(strength).amount;
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                unsigned char* d = &base[y * (size_t)w * 4 + (size_t)x * 4];
                const unsigned char* b = &blur[y * (size_t)w * 4 + (size_t)x * 4];
                for (int c = 0; c < 3; c++)
                    d[c] = clamp255((float)d[c] + amt * ((float)d[c] - (float)b[c]));
            }
        }

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
