#include "libzip_wrapper.h"
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>

struct PreadContext {
    int fd;
    zip_uint64_t offset;
    zip_uint64_t file_size;
};

static zip_int64_t pread_callback(void* _ctx, void* data, zip_uint64_t len, zip_source_cmd_t cmd) {
    auto* c = static_cast<PreadContext*>(_ctx);
    switch (cmd) {
        case ZIP_SOURCE_OPEN:
            c->offset = 0;
            return 0;
        case ZIP_SOURCE_READ: {
            ssize_t n = pread(c->fd, data, len, c->offset);
            if (n < 0) return -1;
            c->offset += n;
            return n;
        }
        case ZIP_SOURCE_SEEK: {
            auto* sa = (zip_source_args_seek_t*)data;
            zip_int64_t new_offset = zip_source_seek_compute_offset(
                c->offset, c->file_size, data, sizeof(zip_source_args_seek_t), nullptr);
            if (new_offset < 0) return -1;
            c->offset = static_cast<zip_uint64_t>(new_offset);
            return 0;
        }
        case ZIP_SOURCE_TELL:
            return c->offset;
        case ZIP_SOURCE_STAT: {
            auto* st = static_cast<zip_stat_t*>(data);
            zip_stat_init(st);
            st->size = c->file_size;
            st->valid = ZIP_STAT_SIZE;
            return 0;
        }
        case ZIP_SOURCE_FREE:
        case ZIP_SOURCE_CLOSE:
            return 0;
        case ZIP_SOURCE_SUPPORTS:
            return ZIP_SOURCE_SUPPORTS_SEEKABLE;
        default:
            return 0;
    }
}

LibzipWrapper& LibzipWrapper::instance() {
    static LibzipWrapper w;
    return w;
}

bool LibzipWrapper::load() {
    if (loaded) return true;
    loaded = true;
    return true;
}

bool LibzipWrapper::isOpen(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex);
    return handles.find(path) != handles.end();
}

void LibzipWrapper::openWithFd(const std::string& path, int fd, const std::string& realPath) {
    std::lock_guard<std::mutex> lock(mutex);
    if (handles.find(path) != handles.end()) return;
    if (!load()) return;

    if (!realPath.empty()) {
        int real_fd = open(realPath.c_str(), O_RDONLY);
        if (real_fd >= 0) {
            struct stat st;
            if (fstat(real_fd, &st) >= 0 && S_ISREG(st.st_mode)) {
                int zerr = 0;
                struct zip* za = zip_fdopen(real_fd, ZIP_RDONLY, &zerr);
                if (za) {
                    zip_int64_t numEntries = zip_get_num_entries(za, 0);
                    handles[path] = {za, nullptr, real_fd, (int)numEntries, nullptr, {}, false, true};
                    return;
                }
            }
            ::close(real_fd);
        }
    }

    struct stat st;
    if (fstat(fd, &st) < 0) {
        return;
    }

    auto* ctx = new PreadContext{fd, 0, static_cast<zip_uint64_t>(st.st_size)};

    zip_error_t error;
    zip_error_init(&error);
    zip_source_t* source = zip_source_function_create(pread_callback, ctx, &error);
    if (!source) {
        zip_error_fini(&error);
        delete ctx;
        return;
    }

    struct zip* za = zip_open_from_source(source, ZIP_RDONLY, &error);
    if (!za) {
        zip_error_fini(&error);
        zip_source_free(source);
        delete ctx;
        return;
    }
    zip_error_fini(&error);

    zip_int64_t numEntries = zip_get_num_entries(za, 0);
    handles[path] = {za, source, fd, (int)numEntries, ctx, {}, false, false};
}

void LibzipWrapper::close(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(path);
    if (it != handles.end()) {
        if (it->second.za) zip_discard(it->second.za);
        if (it->second.source) zip_source_free(it->second.source);
        if (it->second.ownsFd && it->second.fd >= 0) ::close(it->second.fd);
        delete static_cast<PreadContext*>(it->second.ctx);
        handles.erase(it);
    }
}

int LibzipWrapper::getEntryCount(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(path);
    if (it == handles.end()) return 0;
    return it->second.entryCount;
}

static bool isImageName(const char* name) {
    if (!name) return false;
    std::string s(name);
    std::string lower = s;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    size_t dot = lower.rfind('.');
    if (dot == std::string::npos) return false;
    std::string ext = lower.substr(dot + 1);
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" ||
           ext == "bmp" || ext == "gif";
}

static int countPathDepth(const char* name) {
    int depth = 0;
    for (const char* p = name; *p; p++) {
        if (*p == '/' || *p == '\\') depth++;
    }
    return depth;
}

static int naturalCompare(const char* a, const char* b) {
    while (*a && *b) {
        if (isdigit((unsigned char)*a) && isdigit((unsigned char)*b)) {
            long numA = 0, numB = 0;
            while (isdigit((unsigned char)*a)) { numA = numA * 10 + (*a - '0'); a++; }
            while (isdigit((unsigned char)*b)) { numB = numB * 10 + (*b - '0'); b++; }
            if (numA != numB) return (numA < numB) ? -1 : 1;
        } else {
            unsigned char ca = (unsigned char)tolower(*a);
            unsigned char cb = (unsigned char)tolower(*b);
            if (ca != cb) return (ca < cb) ? -1 : 1;
            a++; b++;
        }
    }
    if (*a == *b) return 0;
    return (*a == '\0') ? -1 : 1;
}

static bool compareImageEntries(const std::pair<std::string, int>& a, const std::pair<std::string, int>& b) {
    int depthA = countPathDepth(a.first.c_str());
    int depthB = countPathDepth(b.first.c_str());
    if (depthA != depthB) return depthA < depthB;
    return naturalCompare(a.first.c_str(), b.first.c_str()) < 0;
}

int LibzipWrapper::findImageEntry(const std::string& path, int targetImageIndex) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(path);
    if (it == handles.end()) {
        return -1;
    }

    if (it->second.imageIndicesBuilt) {
        if (targetImageIndex >= 0 && targetImageIndex < (int)it->second.sortedImageIndices.size()) {
            return it->second.sortedImageIndices[targetImageIndex];
        }
        return -1;
    }

    zip_int64_t numEntries = zip_get_num_entries(it->second.za, 0);
    std::vector<std::pair<std::string, int>> images;
    images.reserve(numEntries / 2);
    for (zip_int64_t i = 0; i < numEntries; i++) {
        const char* name = zip_get_name(it->second.za, i, 0);
        if (name && isImageName(name)) {
            images.push_back({name, (int)i});
        }
    }
    std::sort(images.begin(), images.end(), compareImageEntries);

    it->second.sortedImageIndices.clear();
    it->second.sortedImageIndices.reserve(images.size());
    for (auto& img : images) {
        it->second.sortedImageIndices.push_back(img.second);
    }
    it->second.imageIndicesBuilt = true;

    if (targetImageIndex >= 0 && targetImageIndex < (int)it->second.sortedImageIndices.size()) {
        return it->second.sortedImageIndices[targetImageIndex];
    }
    return -1;
}

uint8_t* LibzipWrapper::readEntry(const std::string& path, int index, uint32_t* outSize) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(path);
    if (it == handles.end()) return nullptr;

    struct zip_file* zf = zip_fopen_index(it->second.za, (zip_uint64_t)index, 0);
    if (!zf) return nullptr;

    std::vector<uint8_t> data;
    char buf[65536];
    zip_int64_t n;
    while ((n = zip_fread(zf, buf, sizeof(buf))) > 0) {
        data.insert(data.end(), buf, buf + n);
    }
    zip_fclose(zf);

    if (data.empty()) return nullptr;

    uint8_t* result = (uint8_t*)malloc(data.size());
    if (!result) return nullptr;
    memcpy(result, data.data(), data.size());
    *outSize = (uint32_t)data.size();
    return result;
}

std::vector<std::string> LibzipWrapper::getAllEntryNames(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(path);
    if (it == handles.end()) return {};
    std::vector<std::string> names;
    zip_int64_t numEntries = zip_get_num_entries(it->second.za, 0);
    names.reserve(numEntries);
    for (zip_int64_t i = 0; i < numEntries; i++) {
        const char* name = zip_get_name(it->second.za, i, 0);
        if (name) names.push_back(name);
    }
    return names;
}

int LibzipWrapper::findEntryByName(const std::string& path, const std::string& name) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(path);
    if (it == handles.end()) return -1;

    auto& handle = it->second;
    // 首次调用时构建 name → index 缓存，后续 O(1) 查找
    if (!handle.nameIndexBuilt) {
        zip_int64_t numEntries = zip_get_num_entries(handle.za, 0);
        handle.nameToIndex.reserve(numEntries);
        for (zip_int64_t i = 0; i < numEntries; i++) {
            const char* n = zip_get_name(handle.za, i, 0);
            if (n) handle.nameToIndex[n] = (int)i;
        }
        handle.nameIndexBuilt = true;
    }

    auto nit = handle.nameToIndex.find(name);
    return (nit != handle.nameToIndex.end()) ? nit->second : -1;
}

void LibzipWrapper::clearAll() {
    std::lock_guard<std::mutex> lock(mutex);
    for (auto& kv : handles) {
        if (kv.second.za) zip_discard(kv.second.za);
        if (kv.second.source) zip_source_free(kv.second.source);
        if (kv.second.ownsFd && kv.second.fd >= 0) ::close(kv.second.fd);
        delete static_cast<PreadContext*>(kv.second.ctx);
    }
    handles.clear();
}
