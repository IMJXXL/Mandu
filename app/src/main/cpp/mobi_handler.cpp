#include "mobi_handler.h"
#include <cstring>
#include <algorithm>
#include <android/log.h>

#define MOBI_TAG "MobiHandler"
#define MOBI_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MOBI_TAG, __VA_ARGS__)
#define MOBI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MOBI_TAG, __VA_ARGS__)

MobiHandler& MobiHandler::instance() {
    static MobiHandler handler;
    return handler;
}

bool MobiHandler::isImageData(const uint8_t* data, uint32_t size) {
    if (size < 4) return false;
    if (data[0] == 0xFF && data[1] == 0xD8) return true;
    if (data[0] == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) return true;
    if (size >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
        data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return true;
    if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') return true;
    if (data[0] == 'B' && data[1] == 'M') return true;
    return false;
}

bool MobiHandler::parsePDBHeader(const std::string& path, MobiCache& cache) {
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return false;

    fseek(fp, 0, SEEK_END);
    long fileLen = ftell(fp);
    if (fileLen < 76) { fclose(fp); return false; }

    uint8_t header[76];
    fseek(fp, 0, SEEK_SET);
    if (fread(header, 1, 76, fp) != 76) { fclose(fp); return false; }

    uint16_t numRecords = (uint16_t)header[76 - 2] | ((uint16_t)header[76 - 1] << 8);
    uint32_t recordListOffset = ((uint32_t)header[72] << 24) | ((uint32_t)header[73] << 16) |
                                ((uint32_t)header[74] << 8) | header[75];

    if (numRecords == 0 || recordListOffset == 0) { fclose(fp); return false; }

    cache.records.resize(numRecords);
    fseek(fp, recordListOffset, SEEK_SET);

    for (int i = 0; i < numRecords; i++) {
        uint8_t recInfo[8];
        if (fread(recInfo, 1, 8, fp) != 8) { fclose(fp); return false; }
        cache.records[i].offset = ((uint32_t)recInfo[0] << 24) | ((uint32_t)recInfo[1] << 16) |
                                   ((uint32_t)recInfo[2] << 8) | recInfo[3];
        cache.records[i].attributes = recInfo[4];
        cache.records[i].uniqueId = ((uint32_t)recInfo[5] << 16) | ((uint32_t)recInfo[6] << 8) | recInfo[7];
        cache.records[i].isImage = false;
    }

    cache.firstImageRecord = 0;
    cache.imageCount = 0;
    cache.recordSize = (fileLen > 0 && numRecords > 0) ? (uint32_t)fileLen / numRecords : 0;

    uint8_t buf[8];
    for (int i = 1; i < numRecords; i++) {
        uint32_t offset = cache.records[i].offset;
        if (offset + 8 > (uint32_t)fileLen) continue;

        fseek(fp, offset, SEEK_SET);
        if (fread(buf, 1, 8, fp) != 8) continue;

        if (isImageData(buf, 8)) {
            cache.records[i].isImage = true;
            cache.imageIndices.push_back(i);
            if (cache.firstImageRecord == 0) cache.firstImageRecord = i;
            cache.imageCount++;
        }
    }

    cache.parsed = true;
    fclose(fp);
    MOBI_LOGI("Parsed MOBI: %d records, %d images, firstImage=%d, path=%s",
              numRecords, cache.imageCount, cache.firstImageRecord, path.c_str());
    return true;
}

bool MobiHandler::open(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex);
    if (caches.find(path) != caches.end() && caches[path].parsed) return true;
    return parsePDBHeader(path, caches[path]);
}

void MobiHandler::close(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex);
    caches.erase(path);
}

int MobiHandler::getPageCount(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = caches.find(path);
    if (it == caches.end() || !it->second.parsed) {
        if (!parsePDBHeader(path, caches[path])) return 0;
        return caches[path].imageIndices.size();
    }
    return it->second.imageIndices.size();
}

int MobiHandler::getRecordCount(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = caches.find(path);
    if (it == caches.end() || !it->second.parsed) {
        if (!parsePDBHeader(path, caches[path])) return 0;
        return caches[path].records.size();
    }
    return it->second.records.size();
}

uint8_t* MobiHandler::readRecord(const std::string& path, int index, uint32_t* outSize) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = caches.find(path);
    if (it == caches.end() || !it->second.parsed) {
        if (!parsePDBHeader(path, caches[path])) return nullptr;
        it = caches.find(path);
    }

    auto& cache = it->second;
    if (index < 0 || index >= (int)cache.records.size()) return nullptr;

    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return nullptr;

    uint32_t offset = cache.records[index].offset;
    fseek(fp, 0, SEEK_END);
    uint32_t fileEnd = (uint32_t)ftell(fp);
    uint32_t nextOffset = (index + 1 < (int)cache.records.size()) ?
                          cache.records[index + 1].offset : fileEnd;
    if (nextOffset > fileEnd) nextOffset = fileEnd;

    uint32_t size = nextOffset - offset;
    if (size == 0 || size > 50 * 1024 * 1024) { fclose(fp); return nullptr; }

    uint8_t* data = (uint8_t*)malloc(size);
    if (!data) { fclose(fp); return nullptr; }

    fseek(fp, offset, SEEK_SET);
    size_t read = fread(data, 1, size, fp);
    fclose(fp);

    if (read != size) { free(data); return nullptr; }

    *outSize = size;
    return data;
}

uint8_t* MobiHandler::readCover(const std::string& path, uint32_t* outSize) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = caches.find(path);
    if (it == caches.end() || !it->second.parsed) {
        if (!parsePDBHeader(path, caches[path])) return nullptr;
        it = caches.find(path);
    }

    auto& cache = it->second;
    if (cache.imageIndices.empty()) return nullptr;

    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return nullptr;

    fseek(fp, 0, SEEK_END);
    uint32_t fileEnd = (uint32_t)ftell(fp);

    for (int idx : cache.imageIndices) {
        if (idx > 10) break;
        uint32_t offset = cache.records[idx].offset;
        uint32_t nextOffset = (idx + 1 < (int)cache.records.size()) ?
                              cache.records[idx + 1].offset : fileEnd;
        if (nextOffset > fileEnd) nextOffset = fileEnd;

        uint32_t recSize = nextOffset - offset;
        if (recSize < 8 || recSize > 10 * 1024 * 1024) continue;

        fseek(fp, offset, SEEK_SET);
        uint8_t buf[8];
        if (fread(buf, 1, 8, fp) != 8) continue;

        bool isJpeg = (buf[0] == 0xFF && buf[1] == 0xD8);
        bool isPng = (buf[0] == 0x89 && buf[1] == 0x50 && buf[2] == 0x4E && buf[3] == 0x47);

        if (!isJpeg && !isPng) continue;

        uint8_t* data = (uint8_t*)malloc(recSize);
        if (!data) continue;

        fseek(fp, offset, SEEK_SET);
        if (fread(data, 1, recSize, fp) != recSize) { free(data); continue; }

        uint32_t end = recSize;
        if (isJpeg) {
            for (uint32_t i = 4; i < recSize - 1; i++) {
                if (data[i] == 0xFF && data[i + 1] == 0xD9) { end = i + 2; break; }
            }
        } else {
            for (uint32_t i = 8; i < recSize - 8; i++) {
                if (data[i] == 0x49 && data[i+1] == 0x45 && data[i+2] == 0x44 && data[i+3] == 0x44 &&
                    data[i+4] == 0x00 && data[i+5] == 0x00 && data[i+6] == 0x00 && data[i+7] == 0x00) {
                    end = i + 8; break;
                }
            }
        }

        if (end < recSize) {
            uint8_t* trimmed = (uint8_t*)realloc(data, end);
            if (trimmed) data = trimmed;
        }
        *outSize = end;
        fclose(fp);
        return data;
    }

    fclose(fp);
    return nullptr;
}

void MobiHandler::clearCache() {
    std::lock_guard<std::mutex> lock(mutex);
    caches.clear();
}
