#include "native_engine.h"
#include "libzip_wrapper.h"
#include "pdfium_wrapper.h"
#include <jni.h>
#include <android/bitmap.h>
#include <dlfcn.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <algorithm>

// stb_image functions
extern "C" {
    extern void stbi_image_free(void* retval_from_stbi_load);
}

// libarchive function pointers
typedef struct archive* (*archive_read_new_fn)();
typedef int (*archive_read_support_filter_all_fn)(struct archive*);
typedef int (*archive_read_support_format_all_fn)(struct archive*);
typedef int (*archive_read_open_filename_fn)(struct archive*, const char*, size_t);
typedef int (*archive_read_open_fd_fn)(struct archive*, int fd, size_t block_size);
typedef int (*archive_read_next_header_fn)(struct archive*, struct archive_entry**);
typedef ssize_t (*archive_read_data_fn)(struct archive*, void*, size_t);
typedef int (*archive_read_free_fn)(struct archive*);
typedef const char* (*archive_entry_pathname_fn)(struct archive_entry*);
typedef int (*archive_entry_is_file_fn)(struct archive_entry*);

static archive_read_new_fn fn_archive_read_new = nullptr;
static archive_read_support_filter_all_fn fn_archive_read_support_filter_all = nullptr;
static archive_read_support_format_all_fn fn_archive_read_support_format_all = nullptr;
static archive_read_open_filename_fn fn_archive_read_open_filename = nullptr;
static archive_read_open_fd_fn fn_archive_read_open_fd = nullptr;
static archive_read_next_header_fn fn_archive_read_next_header = nullptr;
static archive_read_data_fn fn_archive_read_data = nullptr;
static archive_read_free_fn fn_archive_read_free = nullptr;
static archive_entry_pathname_fn fn_archive_entry_pathname = nullptr;
static archive_entry_is_file_fn fn_archive_entry_is_file = nullptr;
static bool libarchiveLoaded = false;

static bool loadLibarchive() {
    if (libarchiveLoaded) return true;
    void* handle = dlopen("libarchive-jni.so", RTLD_NOW);
    if (!handle) handle = dlopen("libarchive.so", RTLD_NOW);
    if (!handle) handle = dlopen("libvfs.libarchive.so", RTLD_NOW);
    if (!handle) { return false; }
    fn_archive_read_new = (archive_read_new_fn)dlsym(handle, "archive_read_new");
    fn_archive_read_support_filter_all = (archive_read_support_filter_all_fn)dlsym(handle, "archive_read_support_filter_all");
    fn_archive_read_support_format_all = (archive_read_support_format_all_fn)dlsym(handle, "archive_read_support_format_all");
    fn_archive_read_open_filename = (archive_read_open_filename_fn)dlsym(handle, "archive_read_open_filename");
    fn_archive_read_open_fd = (archive_read_open_fd_fn)dlsym(handle, "archive_read_open_fd");
    fn_archive_read_next_header = (archive_read_next_header_fn)dlsym(handle, "archive_read_next_header");
    fn_archive_read_data = (archive_read_data_fn)dlsym(handle, "archive_read_data");
    fn_archive_read_free = (archive_read_free_fn)dlsym(handle, "archive_read_free");
    fn_archive_entry_pathname = (archive_entry_pathname_fn)dlsym(handle, "archive_entry_pathname");
    fn_archive_entry_is_file = (archive_entry_is_file_fn)dlsym(handle, "archive_entry_is_file");
    if (!fn_archive_read_new || !fn_archive_read_open_filename || !fn_archive_read_next_header ||
        !fn_archive_read_data || !fn_archive_read_free || !fn_archive_entry_pathname || !fn_archive_entry_is_file) {
        return false;
    }
    libarchiveLoaded = true;
    return true;
}

static bool endsWith(const std::string& str, const std::string& suffix) {
    if (suffix.size() > str.size()) return false;
    return str.compare(str.size() - suffix.size(), suffix.size(), suffix) == 0;
}

static bool isImageFile(const std::string& name) {
    std::string lower = name;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    return endsWith(lower, ".jpg") || endsWith(lower, ".jpeg") || endsWith(lower, ".png") ||
           endsWith(lower, ".webp") || endsWith(lower, ".bmp") || endsWith(lower, ".gif");
}

// stb_image functions (declared in native_engine.cpp via header)
extern "C" {
    extern void stbi_image_free(void* retval_from_stbi_load);
}

typedef struct archive* (*archive_read_new_fn)();
typedef int (*archive_read_support_filter_all_fn)(struct archive*);
typedef int (*archive_read_support_format_all_fn)(struct archive*);
typedef int (*archive_read_open_filename_fn)(struct archive*, const char*, size_t);
typedef int (*archive_read_open_fd_fn)(struct archive*, int fd, size_t block_size);
typedef int (*archive_read_next_header_fn)(struct archive*, struct archive_entry**);
typedef ssize_t (*archive_read_data_fn)(struct archive*, void*, size_t);
typedef int (*archive_read_free_fn)(struct archive*);
typedef const char* (*archive_entry_pathname_fn)(struct archive_entry*);
typedef int (*archive_entry_is_file_fn)(struct archive_entry*);

// endsWith 和 isImageFile 已在文件开头定义

static JavaVM* g_jvm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mangareader_native_NativeEngine_nativeOpenArchive(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    jlong handle = NativeEngine::instance().openArchive(cPath);
    env->ReleaseStringUTFChars(path, cPath);
    return handle;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeCloseArchive(JNIEnv* env, jobject thiz, jlong handle) {
    NativeEngine::instance().closeArchive(handle);
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeEngine_nativeGetPageCount(JNIEnv* env, jobject thiz, jlong handle) {
    return NativeEngine::instance().getPageCount(handle);
}

// ZIP/Archive/Decode 方法已包含在上方

JNIEXPORT jintArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeGetPageInfo(JNIEnv* env, jobject thiz, jlong handle, jint pageIndex) {
    int imgIdx = NativeEngine::instance().getImageIndex(handle, pageIndex);
    if (imgIdx < 0) return nullptr;
    uint32_t rawSize = 0;
    uint8_t* rawData = NativeEngine::instance().readZipEntry(handle, imgIdx, &rawSize);
    if (!rawData || rawSize == 0) return nullptr;
    int w = 0, h = 0;
    if (rawSize >= 4 && rawData[0] == 0xFF && rawData[1] == 0xD8) {
        size_t i = 2;
        while (i < rawSize - 9) {
            if (rawData[i] != 0xFF) { i++; continue; }
            if (rawData[i+1] == 0xD9) break;
            if (rawData[i+1] == 0xC0 || rawData[i+1] == 0xC2) {
                h = (rawData[i+5] << 8) | rawData[i+6];
                w = (rawData[i+7] << 8) | rawData[i+8];
                break;
            }
            int segLen = (rawData[i+2] << 8) | rawData[i+3];
            i += 2 + segLen;
        }
    } else if (rawSize >= 24 && rawData[0] == 0x89 && rawData[1] == 0x50) {
        w = (rawData[16] << 24) | (rawData[17] << 16) | (rawData[18] << 8) | rawData[19];
        h = (rawData[20] << 24) | (rawData[21] << 16) | (rawData[22] << 8) | rawData[23];
    }
    free(rawData);
    if (w <= 0 || h <= 0) return nullptr;
    jintArray result = env->NewIntArray(2);
    jint info[2] = {w, h};
    env->SetIntArrayRegion(result, 0, 2, info);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeReadPageRaw(JNIEnv* env, jobject thiz, jlong handle, jint pageIndex) {
    uint32_t cachedSize = 0;
    uint8_t* cachedData = NativeEngine::instance().getCachedRawData(handle, pageIndex, &cachedSize);
    if (cachedData && cachedSize > 0) {
        jbyteArray result = env->NewByteArray(cachedSize);
        env->SetByteArrayRegion(result, 0, cachedSize, (jbyte*)cachedData);
        return result;
    }
    int imgIdx = NativeEngine::instance().getImageIndex(handle, pageIndex);
    if (imgIdx < 0) return nullptr;
    uint32_t rawSize = 0;
    uint8_t* rawData = NativeEngine::instance().readZipEntry(handle, imgIdx, &rawSize);
    if (!rawData || rawSize == 0) return nullptr;
    jbyteArray result = env->NewByteArray(rawSize);
    env->SetByteArrayRegion(result, 0, rawSize, (jbyte*)rawData);
    free(rawData);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_mangareader_native_NativeEngine_nativeHasCachedPage(JNIEnv* env, jobject thiz, jlong handle, jint pageIndex) {
    return NativeEngine::instance().getCachedBitmap(handle, pageIndex) != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeSubmitPredecode(JNIEnv* env, jobject thiz, jlong handle, jint pageIndex, jint maxW, jint maxH) {
    NativeEngine::instance().submitPredecode(handle, pageIndex, maxW, maxH);
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeWaitForPredecodes(JNIEnv* env, jobject thiz) {
    NativeEngine::instance().waitForPendingPredecodes();
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeCancelPredecodes(JNIEnv* env, jobject thiz) {
    NativeEngine::instance().cancelPredecodes();
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeSetCacheSize(JNIEnv* env, jobject thiz, jint maxPages) {
    NativeEngine::instance().setCacheSize(maxPages);
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeClearCache(JNIEnv* env, jobject thiz) {
    NativeEngine::instance().clearCache();
}

JNIEXPORT jobject JNICALL
Java_com_mangareader_native_NativeEngine_nativeCreateBitmapFromCache(JNIEnv* env, jobject thiz, jlong handle, jint pageIndex) {
    int w = 0, h = 0;
    void* pixels = NativeEngine::instance().getCachedDecodedPixels(handle, pageIndex, &w, &h);
    if (!pixels || w <= 0 || h <= 0) return nullptr;
    jclass bmpClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(bmpClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID rgbaField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, rgbaField);
    jobject bitmap = env->CallStaticObjectMethod(bmpClass, createBitmap, w, h, config);
    if (!bitmap) return nullptr;
    void* bmpPixels = nullptr;
    AndroidBitmapInfo bmpInfo;
    AndroidBitmap_getInfo(env, bitmap, &bmpInfo);
    if (AndroidBitmap_lockPixels(env, bitmap, &bmpPixels) == ANDROID_BITMAP_RESULT_SUCCESS && bmpPixels) {
        uint32_t* dst = (uint32_t*)bmpPixels;
        uint8_t* src = (uint8_t*)pixels;
        for (int i = 0; i < w * h; i++) {
            dst[i] = (src[i*4+3] << 24) | (src[i*4] << 16) | (src[i*4+1] << 8) | src[i*4+2];
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    return bitmap;
}

JNIEXPORT jobject JNICALL
Java_com_mangareader_native_NativeEngine_nativeDecodeImageNative(JNIEnv* env, jobject thiz, jbyteArray data, jint size) {
    if (!data || size <= 0) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return nullptr;
    int w = 0, h = 0, channels = 0;
    uint8_t* pixels = NativeEngine::decodeImageNative((const uint8_t*)bytes, (uint32_t)size, &w, &h, &channels);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    if (!pixels || w <= 0 || h <= 0) return nullptr;
    jclass bmpClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(bmpClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID rgbaField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, rgbaField);
    jobject bitmap = env->CallStaticObjectMethod(bmpClass, createBitmap, w, h, config);
    if (!bitmap) { free(pixels); return nullptr; }
    void* bmpPixels = nullptr;
    AndroidBitmapInfo bmpInfo;
    AndroidBitmap_getInfo(env, bitmap, &bmpInfo);
    if (AndroidBitmap_lockPixels(env, bitmap, &bmpPixels) == ANDROID_BITMAP_RESULT_SUCCESS && bmpPixels) {
        uint32_t* dst = (uint32_t*)bmpPixels;
        uint8_t* src = (uint8_t*)pixels;
        for (int i = 0; i < w * h; i++) {
            dst[i] = (src[i*4+3] << 24) | (src[i*4] << 16) | (src[i*4+1] << 8) | src[i*4+2];
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    free(pixels);
    return bitmap;
}

JNIEXPORT jobject JNICALL
Java_com_mangareader_native_NativeEngine_nativeReadAndDecodeToBitmap(JNIEnv* env, jobject thiz, jlong handle, jint pageIndex, jint maxWidth, jint maxHeight) {
    return NativeEngine::readAndDecodeToBitmap(env, handle, pageIndex, maxWidth, maxHeight);
}

JNIEXPORT jbyteArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeReadArchiveEntry(JNIEnv* env, jobject thiz, jstring path, jint targetIndex, jstring extension) {
    if (!loadLibarchive()) return nullptr;
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    const char* cExt = env->GetStringUTFChars(extension, nullptr);
    struct archive* a = fn_archive_read_new();
    if (!a) { env->ReleaseStringUTFChars(path, cPath); env->ReleaseStringUTFChars(extension, cExt); return nullptr; }
    fn_archive_read_support_filter_all(a);
    fn_archive_read_support_format_all(a);
    int ret = fn_archive_read_open_filename(a, cPath, 10240);
    if (ret != 0) { fn_archive_read_free(a); env->ReleaseStringUTFChars(path, cPath); env->ReleaseStringUTFChars(extension, cExt); return nullptr; }
    struct archive_entry* entry;
    int idx = 0;
    jbyteArray result = nullptr;
    while (fn_archive_read_next_header(a, &entry) == 0) {
        const char* name = fn_archive_entry_pathname(entry);
        if (name && fn_archive_entry_is_file(entry)) {
            std::string nameStr(name);
            if (isImageFile(nameStr)) {
                if (idx == targetIndex) {
                    char buf[65536];
                    std::vector<uint8_t> dataVec;
                    ssize_t bytesRead;
                    while ((bytesRead = fn_archive_read_data(a, buf, sizeof(buf))) > 0) {
                        dataVec.insert(dataVec.end(), buf, buf + bytesRead);
                    }
                    if (!dataVec.empty()) {
                        result = env->NewByteArray(dataVec.size());
                        env->SetByteArrayRegion(result, 0, dataVec.size(), (jbyte*)dataVec.data());
                    }
                    break;
                }
                idx++;
            }
        }
    }
    fn_archive_read_free(a);
    env->ReleaseStringUTFChars(path, cPath);
    env->ReleaseStringUTFChars(extension, cExt);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeReadArchiveEntryFd(JNIEnv* env, jobject thiz, jint fd, jint targetIndex) {
    if (!loadLibarchive()) return nullptr;
    struct archive* a = fn_archive_read_new();
    if (!a) return nullptr;
    fn_archive_read_support_filter_all(a);
    fn_archive_read_support_format_all(a);
    int ret = fn_archive_read_open_fd(a, fd, 10240);
    if (ret != 0) { fn_archive_read_free(a); return nullptr; }
    struct archive_entry* entry;
    int idx = 0;
    jbyteArray result = nullptr;
    while (fn_archive_read_next_header(a, &entry) == 0) {
        const char* name = fn_archive_entry_pathname(entry);
        if (name && fn_archive_entry_is_file(entry)) {
            std::string nameStr(name);
            if (isImageFile(nameStr)) {
                if (idx == targetIndex) {
                    char buf[65536];
                    std::vector<uint8_t> dataVec;
                    ssize_t bytesRead;
                    while ((bytesRead = fn_archive_read_data(a, buf, sizeof(buf))) > 0) {
                        dataVec.insert(dataVec.end(), buf, buf + bytesRead);
                    }
                    if (!dataVec.empty()) {
                        result = env->NewByteArray(dataVec.size());
                        env->SetByteArrayRegion(result, 0, dataVec.size(), (jbyte*)dataVec.data());
                    }
                    break;
                }
                idx++;
            }
        }
    }
    fn_archive_read_free(a);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeEngine_nativeCountArchiveEntriesFd(JNIEnv* env, jobject thiz, jint fd) {
    if (!loadLibarchive()) return 0;
    struct archive* a = fn_archive_read_new();
    if (!a) return 0;
    fn_archive_read_support_filter_all(a);
    fn_archive_read_support_format_all(a);
    int ret = fn_archive_read_open_fd(a, fd, 10240);
    if (ret != 0) { fn_archive_read_free(a); return 0; }
    struct archive_entry* entry;
    int count = 0;
    while (fn_archive_read_next_header(a, &entry) == 0) {
        const char* name = fn_archive_entry_pathname(entry);
        if (name && fn_archive_entry_is_file(entry)) {
            std::string nameStr(name);
            if (isImageFile(nameStr)) count++;
        }
    }
    fn_archive_read_free(a);
    return count;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeEngine_nativeCountArchiveEntries(JNIEnv* env, jobject thiz, jstring path) {
    if (!loadLibarchive()) return 0;
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    struct archive* a = fn_archive_read_new();
    if (!a) { env->ReleaseStringUTFChars(path, cPath); return 0; }
    fn_archive_read_support_filter_all(a);
    fn_archive_read_support_format_all(a);
    int ret = fn_archive_read_open_filename(a, cPath, 10240);
    if (ret != 0) { fn_archive_read_free(a); env->ReleaseStringUTFChars(path, cPath); return 0; }
    struct archive_entry* entry;
    int count = 0;
    while (fn_archive_read_next_header(a, &entry) == 0) {
        const char* name = fn_archive_entry_pathname(entry);
        if (name && fn_archive_entry_is_file(entry)) {
            std::string nameStr(name);
            if (isImageFile(nameStr)) count++;
        }
    }
    fn_archive_read_free(a);
    env->ReleaseStringUTFChars(path, cPath);
    return count;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeOpenZipWithFd(JNIEnv* env, jobject thiz, jstring path, jint fd, jstring realPath) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    const char* cReal = realPath ? env->GetStringUTFChars(realPath, nullptr) : "";
    LibzipWrapper::instance().openWithFd(std::string(cPath), fd, std::string(cReal));
    env->ReleaseStringUTFChars(path, cPath);
    if (realPath) env->ReleaseStringUTFChars(realPath, cReal);
}

JNIEXPORT jbyteArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeReadZipEntry(JNIEnv* env, jobject thiz, jstring path, jint index) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    uint32_t size = 0;
    uint8_t* data = LibzipWrapper::instance().readEntry(std::string(cPath), index, &size);
    env->ReleaseStringUTFChars(path, cPath);
    if (!data || size == 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (jbyte*)data);
    free(data);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeEngine_nativeGetZipEntryCount(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    int count = LibzipWrapper::instance().getEntryCount(std::string(cPath));
    env->ReleaseStringUTFChars(path, cPath);
    return count;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeEngine_nativeFindZipImageEntry(JNIEnv* env, jobject thiz, jstring path, jint targetIndex) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    int result = LibzipWrapper::instance().findImageEntry(std::string(cPath), targetIndex);
    env->ReleaseStringUTFChars(path, cPath);
    return result;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeCloseZip(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    LibzipWrapper::instance().close(std::string(cPath));
    env->ReleaseStringUTFChars(path, cPath);
}

JNIEXPORT jboolean JNICALL
Java_com_mangareader_native_NativeEngine_nativeIsOpenZip(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    bool result = LibzipWrapper::instance().isOpen(std::string(cPath));
    env->ReleaseStringUTFChars(path, cPath);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeGetZipEntryNames(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    auto names = LibzipWrapper::instance().getAllEntryNames(std::string(cPath));
    env->ReleaseStringUTFChars(path, cPath);
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(names.size(), strClass, nullptr);
    for (size_t i = 0; i < names.size(); i++) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(names[i].c_str()));
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeEngine_nativeFindZipEntryByName(JNIEnv* env, jobject thiz, jstring path, jstring name) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    const char* cName = env->GetStringUTFChars(name, nullptr);
    int result = LibzipWrapper::instance().findEntryByName(std::string(cPath), std::string(cName));
    env->ReleaseStringUTFChars(path, cPath);
    env->ReleaseStringUTFChars(name, cName);
    return result;
}

// ==================== MOBI 方法 ====================

JNIEXPORT jbyteArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeReadMobiCover(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    uint32_t size = 0;
    uint8_t* data = NativeEngine::readMobiCover(cPath, &size);
    env->ReleaseStringUTFChars(path, cPath);
    if (!data || size == 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (jbyte*)data);
    free(data);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeEngine_nativeCountMobiPages(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    int count = NativeEngine::countMobiPages(cPath);
    env->ReleaseStringUTFChars(path, cPath);
    return count;
}

JNIEXPORT jbyteArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeReadMobiPage(JNIEnv* env, jobject thiz, jstring path, jint pageIndex) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    uint32_t size = 0;
    uint8_t* data = NativeEngine::readMobiPage(cPath, pageIndex, &size);
    env->ReleaseStringUTFChars(path, cPath);
    if (!data || size == 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (jbyte*)data);
    free(data);
    return result;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeOpenMobiWithFd(JNIEnv* env, jobject thiz, jstring path, jint fd) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    NativeEngine::openMobiWithFd(cPath, fd);
    env->ReleaseStringUTFChars(path, cPath);
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeCloseMobiHandle(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    NativeEngine::closeMobiHandle(cPath);
    env->ReleaseStringUTFChars(path, cPath);
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeClearMobiCache(JNIEnv* env, jobject thiz) {
    NativeEngine::clearMobiCache();
}

JNIEXPORT jbyteArray JNICALL
Java_com_mangareader_native_NativeEngine_nativeGetMobiInfo(JNIEnv* env, jobject thiz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    uint32_t size = 0;
    uint8_t* data = NativeEngine::getMobiInfo(cPath, &size);
    env->ReleaseStringUTFChars(path, cPath);
    if (!data || size == 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (jbyte*)data);
    free(data);
    return result;
}

// ==================== pdfium 方法 ====================

JNIEXPORT jboolean JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfOpen(JNIEnv* env, jobject thiz, jstring key, jint fd) {
    if (key == nullptr) return JNI_FALSE;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    bool result = PdfiumWrapper::instance().openDocument(std::string(cKey), fd);
    env->ReleaseStringUTFChars(key, cKey);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfClose(JNIEnv* env, jobject thiz, jstring key) {
    if (key == nullptr) return;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    PdfiumWrapper::instance().closeDocument(std::string(cKey));
    env->ReleaseStringUTFChars(key, cKey);
}

JNIEXPORT jboolean JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfIsOpen(JNIEnv* env, jobject thiz, jstring key) {
    if (key == nullptr) return JNI_FALSE;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    bool result = PdfiumWrapper::instance().isOpen(std::string(cKey));
    env->ReleaseStringUTFChars(key, cKey);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfGetPageCount(JNIEnv* env, jobject thiz, jstring key) {
    if (key == nullptr) return 0;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    int result = PdfiumWrapper::instance().getPageCount(std::string(cKey));
    env->ReleaseStringUTFChars(key, cKey);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfOpenPage(JNIEnv* env, jobject thiz, jstring key, jint pageIndex) {
    if (key == nullptr) return JNI_FALSE;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    bool result = PdfiumWrapper::instance().openPage(std::string(cKey), pageIndex);
    env->ReleaseStringUTFChars(key, cKey);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfClosePage(JNIEnv* env, jobject thiz, jstring key) {
    if (key == nullptr) return;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    PdfiumWrapper::instance().closePage(std::string(cKey));
    env->ReleaseStringUTFChars(key, cKey);
}

JNIEXPORT jintArray JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfGetPageSize(JNIEnv* env, jobject thiz, jstring key, jint pageIndex) {
    if (key == nullptr) return nullptr;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    double width = 0, height = 0;
    PdfiumWrapper::instance().getPageSize(std::string(cKey), pageIndex, &width, &height);
    env->ReleaseStringUTFChars(key, cKey);
    if (width <= 0 || height <= 0) return nullptr;
    jintArray result = env->NewIntArray(2);
    jint size[2] = { (jint)width, (jint)height };
    env->SetIntArrayRegion(result, 0, 2, size);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfUnlockDoc(JNIEnv* env, jobject thiz, jstring key, jstring pwd) {
    if (key == nullptr) return JNI_FALSE;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    const char* cPwd = (pwd != nullptr) ? env->GetStringUTFChars(pwd, nullptr) : nullptr;
    bool result = PdfiumWrapper::instance().openDocumentWithPassword(std::string(cKey), -1, cPwd);
    env->ReleaseStringUTFChars(key, cKey);
    if (cPwd != nullptr) env->ReleaseStringUTFChars(pwd, cPwd);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfRenderPage(JNIEnv* env, jobject thiz, jstring key, jint pageIndex, jint width, jint height, jint dpi, jboolean isArgb) {
    if (key == nullptr) return nullptr;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    int stride = 0;
    void* buffer = PdfiumWrapper::instance().renderPage(std::string(cKey), pageIndex, width, height, dpi, isArgb, &stride);
    env->ReleaseStringUTFChars(key, cKey);
    if (!buffer) return nullptr;
    int bufSize = stride * height;
    if (bufSize <= 0) { free(buffer); return nullptr; }
    jbyteArray result = env->NewByteArray(bufSize);
    env->SetByteArrayRegion(result, 0, bufSize, (jbyte*)buffer);
    free(buffer);
    return result;
}

// 直接返回 Bitmap（零拷贝，@FastNative 模式）
JNIEXPORT jobject JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfRenderPageBitmap(JNIEnv* env, jobject thiz, jstring key, jint pageIndex, jint width, jint height, jint dpi) {
    if (key == nullptr) return nullptr;
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    int stride = 0;
    FPDF_BITMAP fpdfBitmap = (FPDF_BITMAP)PdfiumWrapper::instance().renderPage(std::string(cKey), pageIndex, width, height, dpi, true, &stride);
    env->ReleaseStringUTFChars(key, cKey);
    if (!fpdfBitmap) return nullptr;

    // 获取pdfium内部buffer
    unsigned char* src = (unsigned char*)PdfiumWrapper::instance().getBitmapBuffer(fpdfBitmap);
    if (!src) { PdfiumWrapper::instance().destroyBitmap(fpdfBitmap); return nullptr; }

    // 创建 Bitmap
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, argb8888Field);
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmap, width, height, config);
    if (bitmap == nullptr) { PdfiumWrapper::instance().destroyBitmap(fpdfBitmap); return nullptr; }

    // 直接从pdfium buffer转换BGRA→ARGB写入Bitmap
    void* bmpPixels = nullptr;
    AndroidBitmapInfo bmpInfo;
    AndroidBitmap_getInfo(env, bitmap, &bmpInfo);
    if (AndroidBitmap_lockPixels(env, bitmap, &bmpPixels) == ANDROID_BITMAP_RESULT_SUCCESS && bmpPixels) {
        jint* dst = (jint*)bmpPixels;
        int pixelCount = width * height;
        for (int i = 0; i < pixelCount; i++) {
            int offset = i * 4;
            jint r = src[offset] & 0xFF;
            jint g = src[offset + 1] & 0xFF;
            jint b = src[offset + 2] & 0xFF;
            jint a = src[offset + 3] & 0xFF;
            dst[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    PdfiumWrapper::instance().destroyBitmap(fpdfBitmap);
    env->DeleteLocalRef(bitmapClass);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(config);
    return bitmap;
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativePdfCloseAll(JNIEnv* env, jobject thiz) {
    PdfiumWrapper::instance().closeAll();
}

JNIEXPORT void JNICALL
Java_com_mangareader_native_NativeEngine_nativeForceExit(JNIEnv* env, jobject thiz, jint code) {
    _exit(code);
}

} // extern "C"
