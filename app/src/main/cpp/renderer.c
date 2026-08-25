#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define TAG "NativeRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    float x, y;
    float scale;
    float targetScale;
    float offsetX, offsetY;
    int animating;
    float animProgress;
    int pageWidth, pageHeight;
    int viewWidth, viewHeight;
    int currentPage;
    int totalPages;
    int flipDirection; // -1=left, 0=none, 1=right
    float flipProgress;
    unsigned char* currentPageData;
    unsigned char* nextPageData;
    int currentPageSize;
    int nextPageSize;
    int imageWidth, imageHeight;
} RendererContext;

static RendererContext g_ctx = {0};

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeInit(JNIEnv* env, jobject thiz, int viewWidth, int viewHeight) {
    g_ctx.viewWidth = viewWidth;
    g_ctx.viewHeight = viewHeight;
    g_ctx.scale = 1.0f;
    g_ctx.targetScale = 1.0f;
    g_ctx.offsetX = 0;
    g_ctx.offsetY = 0;
    g_ctx.currentPage = 0;
    g_ctx.totalPages = 0;
    g_ctx.flipDirection = 0;
    g_ctx.flipProgress = 0;
    g_ctx.currentPageData = NULL;
    g_ctx.nextPageData = NULL;
    LOGI("NativeRenderer initialized: %dx%d", viewWidth, viewHeight);
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeDestroy(JNIEnv* env, jobject thiz) {
    if (g_ctx.currentPageData) { free(g_ctx.currentPageData); g_ctx.currentPageData = NULL; }
    if (g_ctx.nextPageData) { free(g_ctx.nextPageData); g_ctx.nextPageData = NULL; }
    g_ctx.currentPageSize = 0;
    g_ctx.nextPageSize = 0;
    LOGI("NativeRenderer destroyed");
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeSetViewSize(JNIEnv* env, jobject thiz, int w, int h) {
    g_ctx.viewWidth = w;
    g_ctx.viewHeight = h;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeRenderer_nativeLoadImage(JNIEnv* env, jobject thiz, jbyteArray data, int len) {
    if (!data || len <= 0) return -1;

    unsigned char* buf = (unsigned char*)malloc(len);
    if (!buf) return -1;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte*)buf);

    // Detect JPEG/PNG dimensions from headers
    int w = 0, h = 0;
    if (len >= 4 && buf[0] == 0xFF && buf[1] == 0xD8) {
        // JPEG - parse SOF marker
        int i = 2;
        while (i < len - 9) {
            if (buf[i] != 0xFF) { i++; continue; }
            if (buf[i+1] == 0xD9) break; // EOI
            if (buf[i+1] == 0xC0 || buf[i+1] == 0xC2) {
                h = (buf[i+5] << 8) | buf[i+6];
                w = (buf[i+7] << 8) | buf[i+8];
                break;
            }
            int segLen = (buf[i+2] << 8) | buf[i+3];
            i += 2 + segLen;
        }
    } else if (len >= 24 && buf[0] == 0x89 && buf[1] == 0x50) {
        // PNG
        w = (buf[16] << 24) | (buf[17] << 16) | (buf[18] << 8) | buf[19];
        h = (buf[20] << 24) | (buf[21] << 16) | (buf[22] << 8) | buf[23];
    }

    if (w <= 0 || h <= 0) { free(buf); return -1; }

    // Free old data
    if (g_ctx.currentPageData) free(g_ctx.currentPageData);

    g_ctx.currentPageData = buf;
    g_ctx.currentPageSize = len;
    g_ctx.imageWidth = w;
    g_ctx.imageHeight = h;

    // Calculate initial scale to fit
    float scaleX = (float)g_ctx.viewWidth / w;
    float scaleY = (float)g_ctx.viewHeight / h;
    g_ctx.scale = (scaleX < scaleY) ? scaleX : scaleY;
    g_ctx.targetScale = g_ctx.scale;
    g_ctx.offsetX = 0;
    g_ctx.offsetY = 0;

    LOGI("Image loaded: %dx%d, scale=%.3f", w, h, g_ctx.scale);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeSetPage(JNIEnv* env, jobject thiz, jint page) {
    g_ctx.currentPage = page;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeSetTotalPages(JNIEnv* env, jobject thiz, jint total) {
    g_ctx.totalPages = total;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeZoom(JNIEnv* env, jobject thiz, jfloat factor, jfloat cx, jfloat cy) {
    float newScale = g_ctx.scale * factor;
    newScale = fmaxf(0.5f, fminf(newScale, 5.0f));

    // Zoom toward center point
    float ratio = newScale / g_ctx.scale;
    g_ctx.offsetX = cx - (cx - g_ctx.offsetX) * ratio;
    g_ctx.offsetY = cy - (cy - g_ctx.offsetY) * ratio;
    g_ctx.scale = newScale;
    g_ctx.targetScale = newScale;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativePan(JNIEnv* env, jobject thiz, jfloat dx, jfloat dy) {
    g_ctx.offsetX += dx;
    g_ctx.offsetY += dy;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeResetZoom(JNIEnv* env, jobject thiz) {
    if (g_ctx.imageWidth <= 0 || g_ctx.imageHeight <= 0) return;
    float scaleX = (float)g_ctx.viewWidth / g_ctx.imageWidth;
    float scaleY = (float)g_ctx.viewHeight / g_ctx.imageHeight;
    g_ctx.scale = (scaleX < scaleY) ? scaleX : scaleY;
    g_ctx.targetScale = g_ctx.scale;
    g_ctx.offsetX = 0;
    g_ctx.offsetY = 0;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeStartFlip(JNIEnv* env, jobject thiz, jint direction) {
    g_ctx.flipDirection = direction;
    g_ctx.flipProgress = 0;
    g_ctx.animating = 1;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeUpdateFlip(JNIEnv* env, jobject thiz, jfloat progress) {
    g_ctx.flipProgress = fminf(progress, 1.0f);
    if (g_ctx.flipProgress >= 1.0f) {
        g_ctx.flipDirection = 0;
        g_ctx.flipProgress = 0;
        g_ctx.animating = 0;
    }
}

JNIEXPORT jfloat JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetScale(JNIEnv* env, jobject thiz) {
    return g_ctx.scale;
}

JNIEXPORT jfloat JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetOffsetX(JNIEnv* env, jobject thiz) {
    return g_ctx.offsetX;
}

JNIEXPORT jfloat JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetOffsetY(JNIEnv* env, jobject thiz) {
    return g_ctx.offsetY;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetCurrentPage(JNIEnv* env, jobject thiz) {
    return g_ctx.currentPage;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetTotalPages(JNIEnv* env, jobject thiz) {
    return g_ctx.totalPages;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetWidth(JNIEnv* env, jobject thiz) {
    return g_ctx.imageWidth;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetHeight(JNIEnv* env, jobject thiz) {
    return g_ctx.imageHeight;
}

JNIEXPORT jboolean JNICALL
Java_com_mangareader_native_NativeRenderer_nativeIsAnimating(JNIEnv* env, jobject thiz) {
    return g_ctx.animating ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetFlipProgress(JNIEnv* env, jobject thiz) {
    return g_ctx.flipProgress;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetFlipDirection(JNIEnv* env, jobject thiz) {
    return g_ctx.flipDirection;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeRenderer_nativeClampOffset(JNIEnv* env, jobject thiz) {
    if (g_ctx.imageWidth <= 0 || g_ctx.imageHeight <= 0) return;

    float scaledW = g_ctx.imageWidth * g_ctx.scale;
    float scaledH = g_ctx.imageHeight * g_ctx.scale;

    if (scaledW <= g_ctx.viewWidth) {
        g_ctx.offsetX = (g_ctx.viewWidth - scaledW) / 2.0f;
    } else {
        float maxOffX = (scaledW - g_ctx.viewWidth) / 2.0f;
        if (g_ctx.offsetX < -maxOffX) g_ctx.offsetX = -maxOffX;
        if (g_ctx.offsetX > maxOffX) g_ctx.offsetX = maxOffX;
    }

    if (scaledH <= g_ctx.viewHeight) {
        g_ctx.offsetY = (g_ctx.viewHeight - scaledH) / 2.0f;
    } else {
        float maxOffY = (scaledH - g_ctx.viewHeight) / 2.0f;
        if (g_ctx.offsetY < -maxOffY) g_ctx.offsetY = -maxOffY;
        if (g_ctx.offsetY > maxOffY) g_ctx.offsetY = maxOffY;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_mangareader_native_NativeRenderer_nativeHitTest(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jint viewW, jint viewH) {
    // Returns true if the tap is in the center region (for UI toggle)
    float centerX = viewW / 2.0f;
    float centerY = viewH / 2.0f;
    float dx = fabsf(x - centerX);
    float dy = fabsf(y - centerY);
    return (dx < viewW * 0.35f && dy < viewH * 0.35f) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeRenderer_nativeGetTapAction(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jint viewW, jint viewH, jboolean rtl, jint clickFlipType) {
    // clickFlipType: 0=no, 1=left_right, 2=diagonal_lb_rt, 3=diagonal_lt_rb
    if (clickFlipType == 0) return 0; // no click flip

    float fx = x / viewW;
    float fy = y / viewH;

    if (fy < 0.15f || fy > 0.85f) return 0; // top/bottom = toggle

    switch (clickFlipType) {
        case 1: // LEFT_RIGHT - simple left/right halves
            if (fx < 0.5f) return rtl ? 2 : 1;
            return rtl ? 1 : 2;

        case 2: // DIAGONAL_LB_RT - left-bottom to right-top diagonal
            if (fx + fy < 1.0f) return rtl ? 2 : 1;
            return rtl ? 1 : 2;

        case 3: // DIAGONAL_LT_RB - left-top to right-bottom diagonal
            if (fx < fy) return rtl ? 2 : 1;
            return rtl ? 1 : 2;

        default:
            if (fx < 0.35f) return rtl ? 2 : 1;
            if (fx > 0.65f) return rtl ? 1 : 2;
            return 0;
    }
}
