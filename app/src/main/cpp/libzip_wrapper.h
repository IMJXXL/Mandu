#ifndef LIBZIP_WRAPPER_H
#define LIBZIP_WRAPPER_H

#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <cstdint>
#include <unordered_map>
#include <mutex>
#include <sys/stat.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>

#include "zipconf.h"
#include "zip.h"

class LibzipWrapper {
public:
    static LibzipWrapper& instance();

    bool load();
    bool isOpen(const std::string& path);
    void openWithFd(const std::string& path, int fd, const std::string& realPath = "");
    void close(const std::string& path);
    int getEntryCount(const std::string& path);
    uint8_t* readEntry(const std::string& path, int index, uint32_t* outSize);
    int findImageEntry(const std::string& path, int targetImageIndex);
    void clearAll();
    std::vector<std::string> getAllEntryNames(const std::string& path);
    int findEntryByName(const std::string& path, const std::string& name);

private:
    LibzipWrapper() = default;

    struct ZipHandle {
        struct zip* za;
        zip_source_t* source;
        int fd;
        int entryCount;
        void* ctx;
        std::vector<int> sortedImageIndices;
        bool imageIndicesBuilt = false;
        bool ownsFd = false;
        std::unordered_map<std::string, int> nameToIndex; // name → ZIP entry index（O(1) 查找）
        bool nameIndexBuilt = false;
    };

    std::unordered_map<std::string, ZipHandle> handles;
    std::mutex mutex;
    bool loaded = false;
};

#endif // LIBZIP_WRAPPER_H
