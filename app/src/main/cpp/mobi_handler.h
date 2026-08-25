#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>

struct MobiRecord {
    uint32_t offset;
    uint32_t attributes;
    uint32_t uniqueId;
    bool isImage;
};

struct MobiCache {
    std::vector<MobiRecord> records;
    std::vector<int> imageIndices;
    uint32_t firstImageRecord;
    uint32_t imageCount;
    uint32_t recordSize;
    bool parsed;
};

class MobiHandler {
public:
    static MobiHandler& instance();

    bool open(const std::string& path);
    void close(const std::string& path);
    int getPageCount(const std::string& path);
    int getRecordCount(const std::string& path);
    uint8_t* readRecord(const std::string& path, int index, uint32_t* outSize);
    uint8_t* readCover(const std::string& path, uint32_t* outSize);
    void clearCache();

private:
    MobiHandler() = default;
    bool parsePDBHeader(const std::string& path, MobiCache& cache);
    bool isImageData(const uint8_t* data, uint32_t size);

    std::unordered_map<std::string, MobiCache> caches;
    std::mutex mutex;
};
