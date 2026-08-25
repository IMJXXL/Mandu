#include "native_engine.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_JPEG
#define STBI_ONLY_PNG
#define STBI_ONLY_BMP
#define STBI_ONLY_GIF
#include "stb_image.h"

NativeEngine& NativeEngine::instance() {
    static NativeEngine eng;
    return eng;
}

NativeEngine::NativeEngine() : nextHandle(1), maxCachePages(60), accessCounter(0), pendingJobs(0), shutdown(false) {
    int threadCount = std::max(2, (int)std::thread::hardware_concurrency() - 1);
    threadCount = std::min(threadCount, 4);
    for (int i = 0; i < threadCount; i++) {
        workers.emplace_back(&NativeEngine::predecodeWorker, this);
    }
    LOGI("NativeEngine: %d worker threads, maxCache=%d", threadCount, maxCachePages);
}

NativeEngine::~NativeEngine() {
    cancelPredecodes();
}

void NativeEngine::predecodeWorker() {
    while (true) {
        std::function<void()> task;
        {
            std::unique_lock<std::mutex> lock(queueMutex);
            queueCV.wait(lock, [this] { return shutdown || !predecodeQueue.empty(); });
            if (shutdown && predecodeQueue.empty()) return;
            task = std::move(predecodeQueue.front());
            predecodeQueue.pop();
        }
        task();
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            pendingJobs--;
        }
        doneCV.notify_all();
    }
}

bool NativeEngine::isImageFile(const std::string& name) {
    if (name.empty() || name.back() == '/') return false;
    size_t dot = name.rfind('.');
    if (dot == std::string::npos) return false;
    std::string ext = name.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" ||
           ext == "bmp" || ext == "gif";
}

bool NativeEngine::parseZipCentralDirectory(ArchiveHandle& ah) {
    const uint8_t* data = ah.mappedData;
    size_t fileSize = ah.mappedSize;
    if (!data || fileSize < 22) return false;

    long searchStart = (fileSize > 65576) ? fileSize - 65576 : 0;
    long eocdPos = -1;
    for (long i = (long)fileSize - 22; i >= searchStart; i--) {
        if (data[i] == 0x50 && data[i+1] == 0x4B && data[i+2] == 0x05 && data[i+3] == 0x06) {
            eocdPos = i;
            break;
        }
    }
    if (eocdPos < 0) return false;

    const uint8_t* eocd = data + eocdPos;
    uint32_t cdOffset = (uint32_t)eocd[16] | ((uint32_t)eocd[17] << 8) |
                         ((uint32_t)eocd[18] << 16) | ((uint32_t)eocd[19] << 24);
    uint16_t numEntries = (uint16_t)eocd[10] | ((uint16_t)eocd[11] << 8);

    ah.entries.clear();
    ah.entries.reserve(numEntries);

    const uint8_t* cd = data + cdOffset;
    for (uint16_t i = 0; i < numEntries; i++) {
        if (cd[0] != 0x50 || cd[1] != 0x4B || cd[2] != 0x01 || cd[3] != 0x02) break;

        uint16_t compMethod = (uint16_t)cd[10] | ((uint16_t)cd[11] << 8);
        uint32_t compSize = (uint32_t)cd[20] | ((uint32_t)cd[21] << 8) |
                             ((uint32_t)cd[22] << 16) | ((uint32_t)cd[23] << 24);
        uint32_t uncompSize = (uint32_t)cd[24] | ((uint32_t)cd[25] << 8) |
                               ((uint32_t)cd[26] << 16) | ((uint32_t)cd[27] << 24);
        uint16_t nameLen = (uint16_t)cd[28] | ((uint16_t)cd[29] << 8);
        uint16_t extraLen = (uint16_t)cd[30] | ((uint16_t)cd[31] << 8);
        uint16_t commentLen = (uint16_t)cd[32] | ((uint16_t)cd[33] << 8);
        uint32_t localOffset = (uint32_t)cd[42] | ((uint32_t)cd[43] << 8) |
                                ((uint32_t)cd[44] << 16) | ((uint32_t)cd[45] << 24);

        std::string name((const char*)(cd + 46), nameLen);
        cd += 46 + nameLen + extraLen + commentLen;

        ZipEntry entry;
        entry.name = name;
        entry.offset = localOffset;
        entry.compressedSize = compSize;
        entry.uncompressedSize = uncompSize;
        entry.compressionMethod = compMethod;
        entry.isImage = isImageFile(name);
        ah.entries.push_back(entry);
    }

    // Sort image entries by path depth + natural sort (unified with Kotlin/ libzip)
    std::vector<std::pair<std::string, int>> imageEntries;
    for (int i = 0; i < (int)ah.entries.size(); i++) {
        if (ah.entries[i].isImage) {
            imageEntries.push_back({ah.entries[i].name, i});
        }
    }
    std::sort(imageEntries.begin(), imageEntries.end(),
        [](const std::pair<std::string, int>& a, const std::pair<std::string, int>& b) {
            // Count path depth
            int depthA = 0, depthB = 0;
            for (char c : a.first) if (c == '/' || c == '\\') depthA++;
            for (char c : b.first) if (c == '/' || c == '\\') depthB++;
            if (depthA != depthB) return depthA < depthB;
            // Natural sort: numbers compared numerically
            const char* pa = a.first.c_str();
            const char* pb = b.first.c_str();
            while (*pa && *pb) {
                if (isdigit((unsigned char)*pa) && isdigit((unsigned char)*pb)) {
                    long numA = 0, numB = 0;
                    while (isdigit((unsigned char)*pa)) { numA = numA * 10 + (*pa - '0'); pa++; }
                    while (isdigit((unsigned char)*pb)) { numB = numB * 10 + (*pb - '0'); pb++; }
                    if (numA != numB) return numA < numB;
                } else {
                    unsigned char ca = tolower(*pa), cb = tolower(*pb);
                    if (ca != cb) return ca < cb;
                    pa++; pb++;
                }
            }
            return *pa == '\0';
        });
    ah.imageIndices.clear();
    ah.imageIndices.reserve(imageEntries.size());
    for (auto& entry : imageEntries) {
        ah.imageIndices.push_back(entry.second);
    }

    LOGI("ZIP parsed (mmap): %zu entries, %zu images", ah.entries.size(), ah.imageIndices.size());
    return !ah.entries.empty();
}

uint8_t* NativeEngine::inflateData(const uint8_t* compData, uint32_t compSize, uint32_t uncompSize) {
    if (compSize == 0) return nullptr;

    z_stream strm;
    memset(&strm, 0, sizeof(strm));
    if (inflateInit2(&strm, -15) != Z_OK) return nullptr;

    strm.next_in = (Bytef*)compData;
    strm.avail_in = compSize;

    uint8_t* outBuf = (uint8_t*)malloc(uncompSize);
    if (!outBuf) { inflateEnd(&strm); return nullptr; }

    strm.next_out = outBuf;
    strm.avail_out = uncompSize;

    int ret = inflate(&strm, Z_FINISH);
    inflateEnd(&strm);

    if (ret != Z_STREAM_END && ret != Z_OK) {
        free(outBuf);
        return nullptr;
    }
    return outBuf;
}

uint8_t* NativeEngine::readZipEntry(jlong handle, int entryIndex, uint32_t* outSize) {
    std::lock_guard<std::mutex> lock(archiveMutex);
    auto it = archives.find(handle);
    if (it == archives.end() || !it->second.isOpen) return nullptr;

    ArchiveHandle& ah = it->second;
    if (entryIndex < 0 || entryIndex >= (int)ah.entries.size()) return nullptr;

    ZipEntry& entry = ah.entries[entryIndex];

    if (ah.useMmap && ah.mappedData) {
        const uint8_t* lfh = ah.mappedData + entry.offset;
        if (lfh[0] != 0x50 || lfh[1] != 0x4B || lfh[2] != 0x03 || lfh[3] != 0x04) return nullptr;

        uint16_t lfhNameLen = (uint16_t)lfh[26] | ((uint16_t)lfh[27] << 8);
        uint16_t lfhExtraLen = (uint16_t)lfh[28] | ((uint16_t)lfh[29] << 8);
        uint32_t dataOffset = entry.offset + 30 + lfhNameLen + lfhExtraLen;

        const uint8_t* compData = ah.mappedData + dataOffset;

        if (entry.compressionMethod == 0) {
            *outSize = entry.compressedSize;
            uint8_t* result = (uint8_t*)malloc(entry.compressedSize);
            if (!result) return nullptr;
            memcpy(result, compData, entry.compressedSize);
            return result;
        } else if (entry.compressionMethod == 8) {
            uint8_t* result = inflateData(compData, entry.compressedSize, entry.uncompressedSize);
            *outSize = entry.uncompressedSize;
            return result;
        }
        return nullptr;
    }

    // Fallback: fread-based reading
    fseek(ah.fp, entry.offset, SEEK_SET);
    uint8_t lfh[30];
    if (fread(lfh, 1, 30, ah.fp) != 30) return nullptr;
    if (lfh[0] != 0x50 || lfh[1] != 0x4B || lfh[2] != 0x03 || lfh[3] != 0x04) return nullptr;

    uint16_t lfhNameLen = (uint16_t)lfh[26] | ((uint16_t)lfh[27] << 8);
    uint16_t lfhExtraLen = (uint16_t)lfh[28] | ((uint16_t)lfh[29] << 8);
    uint32_t dataOffset = entry.offset + 30 + lfhNameLen + lfhExtraLen;

    fseek(ah.fp, dataOffset, SEEK_SET);
    uint8_t* compData = (uint8_t*)malloc(entry.compressedSize);
    if (!compData) return nullptr;
    if (fread(compData, 1, entry.compressedSize, ah.fp) != entry.compressedSize) {
        free(compData);
        return nullptr;
    }

    uint8_t* result = nullptr;
    if (entry.compressionMethod == 0) {
        *outSize = entry.compressedSize;
        result = compData;
    } else if (entry.compressionMethod == 8) {
        result = inflateData(compData, entry.compressedSize, entry.uncompressedSize);
        free(compData);
        *outSize = entry.uncompressedSize;
    } else {
        free(compData);
        return nullptr;
    }
    return result;
}

jlong NativeEngine::openArchive(const char* path) {
    std::lock_guard<std::mutex> lock(archiveMutex);

    ArchiveHandle ah;
    ah.fp = nullptr;
    ah.mappedData = nullptr;
    ah.mappedSize = 0;
    ah.fd = -1;
    ah.isOpen = false;
    ah.useMmap = false;

    ah.fd = open(path, O_RDONLY);
    if (ah.fd >= 0) {
        struct stat st;
        if (fstat(ah.fd, &st) == 0 && st.st_size > 0) {
            ah.mappedSize = st.st_size;
            ah.mappedData = (uint8_t*)mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, ah.fd, 0);
            if (ah.mappedData != MAP_FAILED) {
                ah.useMmap = true;
                LOGI("mmap OK: %s (%zu bytes)", path, st.st_size);
            } else {
                ah.mappedData = nullptr;
                close(ah.fd);
                ah.fd = -1;
            }
        }
    }

    if (!ah.useMmap) {
        ah.fp = fopen(path, "rb");
        if (!ah.fp) {
            LOGE("Failed to open: %s", path);
            return -1;
        }
    }

    ah.filePath = path;
    if (!parseZipCentralDirectory(ah)) {
        if (ah.useMmap) { munmap(ah.mappedData, ah.mappedSize); close(ah.fd); }
        else if (ah.fp) fclose(ah.fp);
        return -1;
    }

    ah.isOpen = true;
    jlong handle = nextHandle++;
    archives[handle] = ah;
    LOGI("Archive opened: %s (%d pages, mmap=%d)", path, (int)ah.imageIndices.size(), ah.useMmap);
    return handle;
}

void NativeEngine::closeArchive(jlong handle) {
    std::lock_guard<std::mutex> lock(archiveMutex);
    auto it = archives.find(handle);
    if (it != archives.end()) {
        if (it->second.useMmap) {
            if (it->second.mappedData) munmap(it->second.mappedData, it->second.mappedSize);
            if (it->second.fd >= 0) close(it->second.fd);
        } else if (it->second.fp) {
            fclose(it->second.fp);
        }
        archives.erase(it);
    }
}

int NativeEngine::getPageCount(jlong handle) {
    std::lock_guard<std::mutex> lock(archiveMutex);
    auto it = archives.find(handle);
    if (it == archives.end()) return 0;
    return (int)it->second.imageIndices.size();
}

int NativeEngine::getImageIndex(jlong handle, int pageIndex) {
    std::lock_guard<std::mutex> lock(archiveMutex);
    auto it = archives.find(handle);
    if (it == archives.end()) return -1;
    if (pageIndex < 0 || pageIndex >= (int)it->second.imageIndices.size()) return -1;
    return it->second.imageIndices[pageIndex];
}

std::string NativeEngine::cacheKey(jlong handle, int pageIndex) {
    return std::to_string(handle) + ":" + std::to_string(pageIndex);
}

NativeBitmap* NativeEngine::getCachedBitmap(jlong handle, int pageIndex) {
    std::lock_guard<std::mutex> lock(cacheMutex);
    std::string key = cacheKey(handle, pageIndex);
    auto it = bitmapCache.find(key);
    if (it != bitmapCache.end()) {
        it->second->lastAccess = ++accessCounter;
        return it->second;
    }
    return nullptr;
}

uint8_t* NativeEngine::getCachedRawData(jlong handle, int pageIndex, uint32_t* outSize) {
    std::lock_guard<std::mutex> lock(cacheMutex);
    std::string key = cacheKey(handle, pageIndex);
    auto it = bitmapCache.find(key);
    if (it != bitmapCache.end() && it->second->rawData && it->second->rawSize > 0) {
        it->second->lastAccess = ++accessCounter;
        *outSize = it->second->rawSize;
        return it->second->rawData;
    }
    return nullptr;
}

void NativeEngine::cacheRawData(jlong handle, int pageIndex, uint8_t* data, uint32_t size) {
    std::lock_guard<std::mutex> lock(cacheMutex);
    std::string key = cacheKey(handle, pageIndex);
    auto it = bitmapCache.find(key);
    if (it != bitmapCache.end()) {
        if (it->second->pixels) free(it->second->pixels);
        if (it->second->rawData) free(it->second->rawData);
        delete it->second;
    }
    while ((int)bitmapCache.size() >= maxCachePages) evictLeastRecent();
    NativeBitmap* bmp = new NativeBitmap();
    bmp->pixels = nullptr;
    bmp->rawData = data;
    bmp->rawSize = size;
    bmp->width = 0; bmp->height = 0; bmp->stride = 0;
    bmp->lastAccess = ++accessCounter;
    bitmapCache[key] = bmp;
}

void NativeEngine::cacheDecodedBitmap(jlong handle, int pageIndex, void* pixels, int w, int h) {
    std::lock_guard<std::mutex> lock(cacheMutex);
    std::string key = cacheKey(handle, pageIndex);
    auto it = bitmapCache.find(key);
    if (it != bitmapCache.end()) {
        if (it->second->pixels) free(it->second->pixels);
        if (it->second->rawData) free(it->second->rawData);
        delete it->second;
    }
    while ((int)bitmapCache.size() >= maxCachePages) evictLeastRecent();
    NativeBitmap* bmp = new NativeBitmap();
    bmp->pixels = (uint8_t*)pixels;
    bmp->rawData = nullptr;
    bmp->rawSize = 0;
    bmp->width = w; bmp->height = h; bmp->stride = w * 4;
    bmp->lastAccess = ++accessCounter;
    bitmapCache[key] = bmp;
}

void* NativeEngine::getCachedDecodedPixels(jlong handle, int pageIndex, int* outW, int* outH) {
    std::lock_guard<std::mutex> lock(cacheMutex);
    std::string key = cacheKey(handle, pageIndex);
    auto it = bitmapCache.find(key);
    if (it != bitmapCache.end() && it->second->pixels && it->second->width > 0) {
        it->second->lastAccess = ++accessCounter;
        *outW = it->second->width;
        *outH = it->second->height;
        return it->second->pixels;
    }
    return nullptr;
}

void NativeEngine::cacheBitmapDirect(jlong handle, int pageIndex, uint8_t* pixels, int w, int h, int stride) {
    std::lock_guard<std::mutex> lock(cacheMutex);
    std::string key = cacheKey(handle, pageIndex);
    auto it = bitmapCache.find(key);
    if (it != bitmapCache.end()) {
        if (it->second->pixels) free(it->second->pixels);
        if (it->second->rawData) free(it->second->rawData);
        delete it->second;
    }
    while ((int)bitmapCache.size() >= maxCachePages) evictLeastRecent();
    NativeBitmap* bmp = new NativeBitmap();
    bmp->pixels = pixels;
    bmp->rawData = nullptr;
    bmp->rawSize = 0;
    bmp->width = w; bmp->height = h; bmp->stride = stride;
    bmp->lastAccess = ++accessCounter;
    bitmapCache[key] = bmp;
}

void NativeEngine::cacheBitmap(jlong handle, int pageIndex, NativeBitmap* bmp) {
    cacheBitmapDirect(handle, pageIndex, bmp->pixels, bmp->width, bmp->height, bmp->stride);
    bmp->pixels = nullptr;
    delete bmp;
}

void NativeEngine::setCacheSize(int maxPages) {
    std::lock_guard<std::mutex> lock(cacheMutex);
    maxCachePages = maxPages;
    while ((int)bitmapCache.size() > maxCachePages) evictLeastRecent();
}

void NativeEngine::clearCache() {
    std::lock_guard<std::mutex> lock(cacheMutex);
    for (auto& kv : bitmapCache) {
        if (kv.second->pixels) free(kv.second->pixels);
        if (kv.second->rawData) free(kv.second->rawData);
        delete kv.second;
    }
    bitmapCache.clear();
}

void NativeEngine::evictLeastRecent() {
    if (bitmapCache.empty()) return;
    auto oldest = bitmapCache.begin();
    for (auto it = bitmapCache.begin(); it != bitmapCache.end(); ++it) {
        if (it->second->lastAccess < oldest->second->lastAccess) oldest = it;
    }
    if (oldest->second->pixels) free(oldest->second->pixels);
    if (oldest->second->rawData) free(oldest->second->rawData);
    delete oldest->second;
    bitmapCache.erase(oldest);
}

void NativeEngine::submitPredecode(jlong handle, int pageIndex, int maxW, int maxH) {
    if (getCachedBitmap(handle, pageIndex)) return;
    { uint32_t sz = 0; if (getCachedRawData(handle, pageIndex, &sz)) return; }
    { std::lock_guard<std::mutex> lock(queueMutex); if (pendingJobs > 8) return; }

    auto& eng = instance();
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        pendingJobs++;
        predecodeQueue.push([&eng, handle, pageIndex, maxW, maxH]() {
            int imgIdx = eng.getImageIndex(handle, pageIndex);
            if (imgIdx < 0) return;
            uint32_t rawSize = 0;
            uint8_t* rawData = eng.readZipEntry(handle, imgIdx, &rawSize);
            if (!rawData || rawSize == 0) return;
            eng.cacheRawData(handle, pageIndex, rawData, rawSize);
        });
    }
    queueCV.notify_one();
}

void NativeEngine::waitForPendingPredecodes() {
    std::unique_lock<std::mutex> lock(queueMutex);
    doneCV.wait(lock, [this] { return pendingJobs == 0 && predecodeQueue.empty(); });
}

void NativeEngine::cancelPredecodes() {
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        while (!predecodeQueue.empty()) predecodeQueue.pop();
    }
    queueCV.notify_all();
}

// MOBI support via libmobi - fd-based, all records in memory
// mobi_load_file reads all records into memory, then we iterate m->rec to find images

#include <atomic>
#include <unistd.h>

static const int MOBI_PREFETCH_PAGES = 3;

// RAII wrapper for file descriptor
class FdGuard {
    int fd_;
public:
    explicit FdGuard(int fd) : fd_(fd) {}
    ~FdGuard() { if (fd_ >= 0) close(fd_); }
    int get() const { return fd_; }
    int release() { int fd = fd_; fd_ = -1; return fd; }
    FdGuard(const FdGuard&) = delete;
    FdGuard& operator=(const FdGuard&) = delete;
};

// Simple page cache
struct PageCacheEntry {
    uint32_t pageIndex;
    uint8_t* data;
    uint32_t size;
    PageCacheEntry() : pageIndex(UINT32_MAX), data(nullptr), size(0) {}
    ~PageCacheEntry() { if (data) free(data); }
};

struct MobiPageCache {
    static const int CAPACITY = 7;
    PageCacheEntry entries[CAPACITY];
    int head;
    MobiPageCache() : head(0) {}
    void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            if (entries[i].data) { free(entries[i].data); entries[i].data = nullptr; }
            entries[i].pageIndex = UINT32_MAX;
            entries[i].size = 0;
        }
        head = 0;
    }
    uint8_t* find(uint32_t pageIndex, uint32_t* outSize) {
        for (int i = 0; i < CAPACITY; i++) {
            if (entries[i].pageIndex == pageIndex && entries[i].data) {
                *outSize = entries[i].size;
                return entries[i].data;
            }
        }
        return nullptr;
    }
    void insert(uint32_t pageIndex, uint8_t* data, uint32_t size) {
        if (entries[head].data) free(entries[head].data);
        entries[head].pageIndex = pageIndex;
        entries[head].data = data;
        entries[head].size = size;
        head = (head + 1) % CAPACITY;
    }
};

struct MobiCache {
    MOBIData* data;                              // libmobi handle (all records in memory)
    std::atomic<int> refCount{0};
    std::vector<MOBIPdbRecord*> imageRecords;    // pre-computed image record pointers
    MobiPageCache pageCache;                     // prefetch cache

    MobiCache() : data(nullptr) {}
};

static std::unordered_map<std::string, MobiCache*> g_mobiCache;
static std::mutex g_mobiMutex;

// Pre-compute image record list from libmobi's in-memory records
// iterate m->rec linked list, find image records
static void buildImageRecordList(MobiCache* cache) {
    cache->imageRecords.clear();
    MOBIData* m = cache->data;
    if (!m || !m->ph || !m->rec) return;

    size_t firstResource = mobi_get_first_resource_record(m);
    if (firstResource == MOBI_NOTSET) return;

    MOBIPdbRecord* rec = m->rec;
    size_t idx = 0;
    while (rec) {
        if (idx >= firstResource && rec->size > 0 && rec->data && rec->size >= 4) {
            uint8_t* d = rec->data;
            bool isImage = (d[0] == 0xFF && d[1] == 0xD8) ||
                           (d[0] == 0x89 && d[1] == 0x50 && d[2] == 0x4E && d[3] == 0x47) ||
                           (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F') ||
                           (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') ||
                           (d[0] == 'B' && d[1] == 'M');
            if (isImage) cache->imageRecords.push_back(rec);
        }
        rec = rec->next;
        idx++;
    }
    LOGI("MOBI: built image list, found %d images from %zu records", (int)cache->imageRecords.size(), idx);
}

static MobiCache* getMobiCache(const char* path) {
    std::lock_guard<std::mutex> lock(g_mobiMutex);
    auto it = g_mobiCache.find(std::string(path));
    if (it != g_mobiCache.end() && it->second && it->second->data) {
        it->second->refCount.fetch_add(1, std::memory_order_relaxed);
        return it->second;
    }
    return nullptr;
}

static void releaseMobiCache(const char* path) {
    MobiCache* toDelete = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mobiMutex);
        auto it = g_mobiCache.find(std::string(path));
        if (it == g_mobiCache.end()) return;
        if (it->second->refCount.fetch_sub(1, std::memory_order_acq_rel) == 1) {
            toDelete = it->second;
            g_mobiCache.erase(it);
        }
    }
    if (toDelete) {
        toDelete->pageCache.clear();
        if (toDelete->data) mobi_free(toDelete->data);
        delete toDelete;
    }
}

// fdopen + mobi_load_file → all records in memory
void NativeEngine::openMobiWithFd(const char* path, int fd) {
    std::lock_guard<std::mutex> lock(g_mobiMutex);
    std::string key(path);

    auto it = g_mobiCache.find(key);
    if (it != g_mobiCache.end() && it->second && it->second->data) {
        it->second->refCount.fetch_add(1, std::memory_order_relaxed);
        close(fd);
        LOGI("MOBI openMobiWithFd: cache hit for %s", path);
        return;
    }

    MOBIData* m = mobi_init();
    if (!m) { LOGI("MOBI openMobiWithFd: mobi_init failed"); close(fd); return; }

    FILE* fp = fdopen(fd, "rb");
    if (!fp) { LOGI("MOBI openMobiWithFd: fdopen failed"); mobi_free(m); close(fd); return; }

    MOBI_RET ret = mobi_load_file(m, fp);
    fclose(fp);
    if (ret != MOBI_SUCCESS) { LOGI("MOBI openMobiWithFd: mobi_load_file failed ret=%d", ret); mobi_free(m); return; }

    MobiCache* cache = new MobiCache();
    cache->data = m;
    cache->refCount.store(1, std::memory_order_relaxed);

    buildImageRecordList(cache);

    g_mobiCache[key] = cache;
    LOGI("MOBI: opened %s (images: %d)", path, (int)cache->imageRecords.size());
}

void NativeEngine::closeMobiHandle(const char* path) {
    releaseMobiCache(path);
}

int NativeEngine::countMobiPages(const char* path) {
    MobiCache* cache = getMobiCache(path);
    if (!cache) return 0;
    int count = (int)cache->imageRecords.size();
    releaseMobiCache(path);
    return count;
}

uint8_t* NativeEngine::readMobiCover(const char* path, uint32_t* outSize) {
    MobiCache* cache = getMobiCache(path);
    if (!cache || cache->imageRecords.empty()) {
        if (cache) releaseMobiCache(path);
        *outSize = 0;
        return nullptr;
    }
    MOBIPdbRecord* rec = cache->imageRecords[0];
    uint8_t* data = (uint8_t*)malloc(rec->size);
    if (data) memcpy(data, rec->data, rec->size);
    *outSize = (uint32_t)rec->size;
    releaseMobiCache(path);
    return data;
}

uint8_t* NativeEngine::readMobiPage(const char* path, int pageIndex, uint32_t* outSize) {
    MobiCache* cache = getMobiCache(path);
    if (!cache || pageIndex < 0 || pageIndex >= (int)cache->imageRecords.size()) {
        LOGI("MOBI readMobiPage: FAIL path=%s pageIndex=%d cache=%p images=%zu", path, pageIndex, cache, cache ? cache->imageRecords.size() : 0);
        if (cache) releaseMobiCache(path);
        *outSize = 0;
        return nullptr;
    }
    MOBIPdbRecord* rec = cache->imageRecords[pageIndex];
    uint8_t* data = (uint8_t*)malloc(rec->size);
    if (data) memcpy(data, rec->data, rec->size);
    *outSize = (uint32_t)rec->size;
    LOGI("MOBI readMobiPage: OK page=%d size=%u", pageIndex, *outSize);
    releaseMobiCache(path);
    return data;
}

void NativeEngine::clearMobiCache() {
    std::lock_guard<std::mutex> lock(g_mobiMutex);
    for (auto& kv : g_mobiCache) {
        if (kv.second) {
            kv.second->pageCache.clear();
            if (kv.second->data) mobi_free(kv.second->data);
            delete kv.second;
        }
    }
    g_mobiCache.clear();
    LOGI("MOBI: cache cleared");
}

// Combined info: returns [4 bytes pageCount | coverData bytes]
uint8_t* NativeEngine::getMobiInfo(const char* path, uint32_t* outSize) {
    MobiCache* cache = getMobiCache(path);
    if (!cache || cache->imageRecords.empty()) {
        if (cache) releaseMobiCache(path);
        *outSize = 0;
        return nullptr;
    }

    int pageCount = (int)cache->imageRecords.size();
    MOBIPdbRecord* rec = cache->imageRecords[0];
    uint32_t coverSize = (uint32_t)rec->size;

    uint32_t totalSize = 4 + coverSize;
    uint8_t* result = (uint8_t*)malloc(totalSize);
    if (!result) { releaseMobiCache(path); *outSize = 0; return nullptr; }

    result[0] = pageCount & 0xFF;
    result[1] = (pageCount >> 8) & 0xFF;
    result[2] = (pageCount >> 16) & 0xFF;
    result[3] = (pageCount >> 24) & 0xFF;
    memcpy(result + 4, rec->data, coverSize);

    *outSize = totalSize;
    releaseMobiCache(path);
    return result;
}

// Native image decode using stb_image + system libjpeg-turbo + system libwebp
uint8_t* NativeEngine::decodeImageNative(const uint8_t* data, uint32_t size, int* outW, int* outH, int* outChannels) {
    // 1. WEBP: Use system libwebp (native, fastest)
    if (size >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
        data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
        void* webpLib = dlopen("libwebp.so", RTLD_NOW);
        if (!webpLib) webpLib = dlopen("libwebp-decode.so", RTLD_NOW);
        if (webpLib) {
            typedef int (*WebPGetInfo_fn)(const uint8_t*, size_t, int*, int*);
            typedef uint8_t* (*WebPDecodeRGBA_fn)(const uint8_t*, size_t, int*, int*);
            WebPGetInfo_fn getInfo = (WebPGetInfo_fn)dlsym(webpLib, "WebPGetInfo");
            WebPDecodeRGBA_fn decodeRGBA = (WebPDecodeRGBA_fn)dlsym(webpLib, "WebPDecodeRGBA");
            if (getInfo && decodeRGBA) {
                int w = 0, h = 0;
                if (getInfo(data, size, &w, &h) && w > 0 && h > 0) {
                    uint8_t* pixels = decodeRGBA(data, size, &w, &h);
                    if (pixels) {
                        *outW = w; *outH = h; *outChannels = 4;
                        LOGI("libwebp decoded: %dx%d", w, h);
                        dlclose(webpLib);
                        return pixels;
                    }
                }
            }
            dlclose(webpLib);
        }
    }

    // 2. JPEG: Use system libjpeg-turbo (2-3x faster than stb_image)
    if (size >= 2 && data[0] == 0xFF && data[1] == 0xD8) {
        void* jpegLib = dlopen("libjpeg.so", RTLD_NOW);
        if (!jpegLib) jpegLib = dlopen("libjpeg-turbo.so", RTLD_NOW);
        if (jpegLib) {
            // libjpeg-turbo JPEG decode via decompress API
            typedef void* (*jpeg_std_error_fn)(void*);
            typedef void* (*jpeg_CreateDecompress_fn)(int, size_t, void*);
            typedef void (*jpeg_mem_src_fn)(void*, const unsigned char*, unsigned long);
            typedef int (*jpeg_read_header_fn)(void*, int);
            typedef int (*jpeg_start_decompress_fn)(void*);
            typedef int (*jpeg_read_scanlines_fn)(void*, unsigned char**, int);
            typedef int (*jpeg_finish_decompress_fn)(void*);
            typedef void (*jpeg_destroy_decompress_fn)(void*);

            jpeg_std_error_fn stdError = (jpeg_std_error_fn)dlsym(jpegLib, "jpeg_std_error");
            jpeg_CreateDecompress_fn createDecompress = (jpeg_CreateDecompress_fn)dlsym(jpegLib, "jpeg_CreateDecompress");
            jpeg_mem_src_fn memSrc = (jpeg_mem_src_fn)dlsym(jpegLib, "jpeg_mem_src");
            jpeg_read_header_fn readHeader = (jpeg_read_header_fn)dlsym(jpegLib, "jpeg_read_header");
            jpeg_start_decompress_fn startDecompress = (jpeg_start_decompress_fn)dlsym(jpegLib, "jpeg_start_decompress");
            jpeg_read_scanlines_fn readScanlines = (jpeg_read_scanlines_fn)dlsym(jpegLib, "jpeg_read_scanlines");
            jpeg_finish_decompress_fn finishDecompress = (jpeg_finish_decompress_fn)dlsym(jpegLib, "jpeg_finish_decompress");
            jpeg_destroy_decompress_fn destroyDecompress = (jpeg_destroy_decompress_fn)dlsym(jpegLib, "jpeg_destroy_decompress");

            if (stdError && createDecompress && memSrc && readHeader && startDecompress && readScanlines && finishDecompress && destroyDecompress) {
                void* errMgr = stdError(malloc(256));
                void* cinfo = createDecompress(62, 256, errMgr);
                memSrc(cinfo, data, size);

                if (readHeader(cinfo, 1) == 1 && startDecompress(cinfo)) {
                    // Get dimensions from cinfo (offsets are standard libjpeg layout)
                    int w = *(int*)((char*)cinfo + 24);
                    int h = *(int*)((char*)cinfo + 28);
                    int numComponents = *(int*)((char*)cinfo + 38);

                    // Allocate RGBA output
                    uint8_t* pixels = (uint8_t*)malloc(w * h * 4);
                    if (pixels) {
                        uint8_t* rowBuffer = (uint8_t*)malloc(w * numComponents);
                        uint8_t* rows[1] = { rowBuffer };

                        for (int y = 0; y < h; y++) {
                            if (readScanlines(cinfo, rows, 1) != 1) break;
                            uint8_t* dst = pixels + y * w * 4;
                            uint8_t* src = rowBuffer;
                            for (int x = 0; x < w; x++) {
                                if (numComponents >= 3) {
                                    dst[x*4] = src[x*3];
                                    dst[x*4+1] = src[x*3+1];
                                    dst[x*4+2] = src[x*3+2];
                                    dst[x*4+3] = 255;
                                } else {
                                    dst[x*4] = dst[x*4+1] = dst[x*4+2] = src[x];
                                    dst[x*4+3] = 255;
                                }
                            }
                        }
                        free(rowBuffer);
                        finishDecompress(cinfo);
                        destroyDecompress(cinfo);
                        free(errMgr);

                        *outW = w; *outH = h; *outChannels = 4;
                        LOGI("libjpeg-turbo decoded: %dx%d", w, h);
                        dlclose(jpegLib);
                        return pixels;
                    }
                }
                destroyDecompress(cinfo);
                free(errMgr);
            }
            dlclose(jpegLib);
        }
    }

    // 3. PNG/BMP/GIF: Use stb_image (fallback)
    int w = 0, h = 0, channels = 0;
    stbi_uc* pixels = stbi_load_from_memory(data, size, &w, &h, &channels, 4);
    if (!pixels) {
        LOGE("stbi_load_from_memory failed: %s", stbi_failure_reason());
        return nullptr;
    }
    *outW = w; *outH = h; *outChannels = 4;
    LOGI("stb_image decoded: %dx%d %dch", w, h, channels);
    return pixels;
}

// Zero-copy: read ZIP entry + decode to Bitmap in one step
jobject NativeEngine::readAndDecodeToBitmap(JNIEnv* env, jlong handle, int pageIndex, int maxWidth, int maxHeight) {
    // Check decoded cache first
    int cachedW = 0, cachedH = 0;
    void* cachedPixels = instance().getCachedDecodedPixels(handle, pageIndex, &cachedW, &cachedH);
    if (cachedPixels && cachedW > 0 && cachedH > 0) {
        // Create Bitmap from cached pixels
        jclass bmpClass = env->FindClass("android/graphics/Bitmap");
        jmethodID createBitmap = env->GetStaticMethodID(bmpClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
        jfieldID rgbaField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        jobject config = env->GetStaticObjectField(configClass, rgbaField);
        jobject bitmap = env->CallStaticObjectMethod(bmpClass, createBitmap, cachedW, cachedH, config);
        if (!bitmap) return nullptr;
        void* bmpPixels = nullptr;
        AndroidBitmapInfo info;
        AndroidBitmap_getInfo(env, bitmap, &info);
        if (AndroidBitmap_lockPixels(env, bitmap, &bmpPixels) == ANDROID_BITMAP_RESULT_SUCCESS && bmpPixels) {
            // Convert RGBA to ARGB directly
            uint32_t* dst = (uint32_t*)bmpPixels;
            uint8_t* src = (uint8_t*)cachedPixels;
            for (int y = 0; y < cachedH; y++) {
                uint8_t* row = src + y * cachedW * 4;
                for (int x = 0; x < cachedW; x++) {
                    dst[y * cachedW + x] = (row[x*4+3] << 24) | (row[x*4] << 16) | (row[x*4+1] << 8) | row[x*4+2];
                }
            }
            AndroidBitmap_unlockPixels(env, bitmap);
        }
        return bitmap;
    }

    // Read ZIP entry to native memory
    int imgIdx = instance().getImageIndex(handle, pageIndex);
    if (imgIdx < 0) return nullptr;
    uint32_t rawSize = 0;
    uint8_t* rawData = instance().readZipEntry(handle, imgIdx, &rawSize);
    if (!rawData || rawSize == 0) return nullptr;

    // Decode directly from native memory (no Java byte[] copy!)
    int w = 0, h = 0, channels = 0;
    uint8_t* pixels = instance().decodeImageNative(rawData, rawSize, &w, &h, &channels);
    free(rawData);
    if (!pixels || w <= 0 || h <= 0) return nullptr;

    // Cache decoded pixels in native memory
    instance().cacheDecodedBitmap(handle, pageIndex, pixels, w, h);
    // Note: pixels are now owned by cacheDecodedBitmap, don't free

    // Create Bitmap from decoded pixels
    jclass bmpClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(bmpClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID rgbaField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, rgbaField);
    jobject bitmap = env->CallStaticObjectMethod(bmpClass, createBitmap, w, h, config);
    if (!bitmap) return nullptr;

    // Convert RGBA to ARGB and write to Bitmap
    void* bmpPixels = nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    if (AndroidBitmap_lockPixels(env, bitmap, &bmpPixels) == ANDROID_BITMAP_RESULT_SUCCESS && bmpPixels) {
        uint32_t* dst = (uint32_t*)bmpPixels;
        uint8_t* src = pixels;
        for (int y = 0; y < h; y++) {
            uint8_t* row = src + y * w * 4;
            for (int x = 0; x < w; x++) {
                dst[y * w + x] = (row[x*4+3] << 24) | (row[x*4] << 16) | (row[x*4+1] << 8) | row[x*4+2];
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    return bitmap;
}
