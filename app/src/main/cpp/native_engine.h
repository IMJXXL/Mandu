#pragma once

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <zlib.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <thread>
#include <queue>
#include <condition_variable>
#include <functional>
#include <cstring>
#include <cstdlib>
#include <algorithm>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>

extern "C" {
#include "libmobi/mobi.h"
}

#define TAG "NativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct ZipEntry {
    std::string name;
    uint32_t offset;
    uint32_t compressedSize;
    uint32_t uncompressedSize;
    uint16_t compressionMethod;
    bool isImage;
};

struct NativeBitmap {
    uint8_t* pixels;
    uint8_t* rawData;
    uint32_t rawSize;
    int width;
    int height;
    int stride;
    uint64_t lastAccess;
};

struct ArchiveHandle {
    int fd;
    uint8_t* mappedData;
    size_t mappedSize;
    FILE* fp;
    std::vector<ZipEntry> entries;
    std::vector<int> imageIndices;
    std::string filePath;
    bool isOpen;
    bool useMmap;
};

class NativeEngine {
public:
    static NativeEngine& instance();

    jlong openArchive(const char* path);
    void closeArchive(jlong handle);
    int getPageCount(jlong handle);
    int getImageIndex(jlong handle, int pageIndex);

    uint8_t* readZipEntry(jlong handle, int entryIndex, uint32_t* outSize);
    uint8_t* inflateData(const uint8_t* compData, uint32_t compSize, uint32_t uncompSize);

    // Zero-copy: read ZIP entry + decode to Bitmap in one step (no Java byte[] middleman)
    static jobject readAndDecodeToBitmap(JNIEnv* env, jlong handle, int pageIndex, int maxWidth, int maxHeight);

    // MOBI operations (libmobi) - fd-based
    static void openMobiWithFd(const char* path, int fd);
    static void closeMobiHandle(const char* path);
    static uint8_t* readMobiCover(const char* path, uint32_t* outSize);
    static int countMobiPages(const char* path);
    static uint8_t* readMobiPage(const char* path, int pageIndex, uint32_t* outSize);
    // Combined info: returns buffer with [4 bytes pageCount | coverData bytes]
    static uint8_t* getMobiInfo(const char* path, uint32_t* outSize);
    static void clearMobiCache();
    // Native image decode using stb_image (JPEG/PNG/BMP/GIF) - returns RGBA pixels
    static uint8_t* decodeImageNative(const uint8_t* data, uint32_t size, int* outW, int* outH, int* outChannels);

    NativeBitmap* getCachedBitmap(jlong handle, int pageIndex);
    uint8_t* getCachedRawData(jlong handle, int pageIndex, uint32_t* outSize);
    void cacheBitmap(jlong handle, int pageIndex, NativeBitmap* bmp);
    void cacheBitmapDirect(jlong handle, int pageIndex, uint8_t* pixels, int w, int h, int stride);
    void cacheRawData(jlong handle, int pageIndex, uint8_t* data, uint32_t size);
    void cacheDecodedBitmap(jlong handle, int pageIndex, void* pixels, int w, int h);

    void* getCachedDecodedPixels(jlong handle, int pageIndex, int* outW, int* outH);

    void setCacheSize(int maxPages);
    void clearCache();
    void evictLeastRecent();

    void submitPredecode(jlong handle, int pageIndex, int maxW, int maxH);
    void waitForPendingPredecodes();
    void cancelPredecodes();

    bool isImageFile(const std::string& name);
    bool parseZipCentralDirectory(ArchiveHandle& ah);

private:
    NativeEngine();
    ~NativeEngine();
    std::string cacheKey(jlong handle, int pageIndex);
    void predecodeWorker();

    std::unordered_map<jlong, ArchiveHandle> archives;
    std::unordered_map<std::string, NativeBitmap*> bitmapCache;
    std::mutex cacheMutex;
    std::mutex archiveMutex;
    jlong nextHandle;
    int maxCachePages;
    uint64_t accessCounter;

    std::vector<std::thread> workers;
    std::queue<std::function<void()>> predecodeQueue;
    std::mutex queueMutex;
    std::condition_variable queueCV;
    std::condition_variable doneCV;
    int pendingJobs;
    bool shutdown;
};
