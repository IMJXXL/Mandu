#include <jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <cstdlib>

static const int FS_K[] = {1,0,7,16, -1,1,3,16, 0,1,5,16, 1,1,1,16};
static const int ATKINSON_K[] = {1,0,1,8, 2,0,1,8, -1,1,1,8, 0,1,1,8, 1,1,1,8, 0,2,1,8};
static const int STUCKI_K[] = {
    1,0,8,42, 2,0,4,42,
    -2,1,2,42, -1,1,4,42, 0,1,8,42, 1,1,4,42, 2,1,2,42,
    -2,2,1,42, -1,2,2,42, 0,2,4,42, 1,2,2,42, 2,2,1,42
};
static const int BAYER_4[] = {0,8,2,10, 12,4,14,6, 3,11,1,9, 15,7,13,5};

static inline int grayscalize(uint32_t p) {
    return ((p >> 16 & 0xFF) * 77 + (p >> 8 & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mangareader_eink_EinkDitherNative_nativeDitherImpl(
    JNIEnv* env, jclass, jobject bitmap, jint algorithm, jint levels) {

    if (levels < 2) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;

    const int width = (int)info.width;
    const int height = (int)info.height;
    const int maxIdx = levels - 1;
    const int n = width * height;

    int quantTbl[256];
    for (int v = 0; v < 256; v++) {
        int idx = (v * maxIdx + 127) / 255;
        if (idx > maxIdx) idx = maxIdx;
        quantTbl[v] = idx * 255 / maxIdx;
    }

    uint32_t* px = (uint32_t*)pixels;
    int midtoneCount = 0;
    for (int i = 0; i < n; i++) {
        int g = grayscalize(px[i]);
        px[i] = (uint32_t)g;
        if (g >= 32 && g <= 223) midtoneCount++;
    }

    if (midtoneCount * 100 < n * 5) {
        for (int i = 0; i < n; i++) {
            int q = quantTbl[(int)px[i]];
            px[i] = 0xFF000000u | (uint32_t)(q << 16) | (uint32_t)(q << 8) | (uint32_t)q;
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_TRUE;
    }

    if (algorithm == 3) {
        for (int y = 0; y < height; y++) {
            int rowBase = (y & 3) << 2;
            int i = y * width;
            for (int x = 0; x < width; x++) {
                int g = (int)px[i];
                if (g >= 255) { px[i] = 255; }
                else if (g <= 0) { px[i] = 0; }
                else {
                    int b = BAYER_4[rowBase | (x & 3)];
                    int idx = (32 * g * maxIdx + (2 * b + 1) * 255) / (32 * 255);
                    if (idx < 0) idx = 0;
                    if (idx > maxIdx) idx = maxIdx;
                    px[i] = (uint32_t)(idx * 255 / maxIdx);
                }
                i++;
            }
        }
    } else {
        const int* kernel;
        int kernelSize;
        switch (algorithm) {
            case 1: kernel = ATKINSON_K; kernelSize = 6; break;
            case 2: kernel = STUCKI_K; kernelSize = 12; break;
            default: kernel = FS_K; kernelSize = 4; break;
        }

        const int errMin = -1024;
        const int errMax = 1024;
        const int errRange = errMax - errMin + 1;
        int* errTbl = (int*)malloc(sizeof(int) * (size_t)kernelSize * (size_t)errRange);
        for (int k = 0; k < kernelSize; k++) {
            int num = kernel[k * 4 + 2];
            int den = kernel[k * 4 + 3];
            for (int e = 0; e < errRange; e++) {
                errTbl[k * errRange + e] = (e + errMin) * num / den;
            }
        }

        int* cur = (int*)calloc((size_t)width, sizeof(int));
        int* next = (int*)calloc((size_t)width, sizeof(int));

        for (int y = 0; y < height; y++) {
            int ltr = (y & 1) == 0;
            int rowBase = y * width;
            int xStart = ltr ? 0 : width - 1;
            int xEnd = ltr ? width : -1;
            int step = ltr ? 1 : -1;

            for (int x = xStart; x != xEnd; x += step) {
                int v = (int)px[rowBase + x] + cur[x];
                int out, err;
                if (v >= 255) { out = 255; err = 0; }
                else if (v <= 0) { out = 0; err = 0; }
                else {
                    out = quantTbl[v];
                    err = v - out;
                }
                px[rowBase + x] = (uint32_t)out;

                if (err != 0) {
                    for (int k = 0; k < kernelSize; k++) {
                        int dx = kernel[k * 4];
                        int dy = kernel[k * 4 + 1];
                        int nx = x + (ltr ? dx : -dx);
                        if (nx < 0 || nx >= width) continue;
                        int ny = y + dy;
                        if (ny >= height) continue;
                        int e;
                        if (err >= errMin && err <= errMax) {
                            e = errTbl[k * errRange + (err - errMin)];
                        } else {
                            e = err * kernel[k * 4 + 2] / kernel[k * 4 + 3];
                        }
                        if (ny == y) cur[nx] += e;
                        else next[nx] += e;
                    }
                }
            }

            int* t = cur; cur = next; next = t;
            memset(next, 0, (size_t)width * sizeof(int));
        }

        free(errTbl);
        free(cur);
        free(next);
    }

    for (int i = 0; i < n; i++) {
        int g = (int)px[i];
        px[i] = 0xFF000000u | (uint32_t)(g << 16) | (uint32_t)(g << 8) | (uint32_t)g;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}