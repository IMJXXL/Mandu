#include "pdfium_wrapper.h"
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstring>
#include <mutex>

PdfiumWrapper& PdfiumWrapper::instance() {
    static PdfiumWrapper inst;
    return inst;
}

bool PdfiumWrapper::load() {
    if (initialized) return true;
    mDlHandle = dlopen("libpdfium.so", RTLD_NOW);
    if (!mDlHandle) {
        return false;
    }
    #define BIND(var, sym) var = (decltype(var))dlsym(mDlHandle, sym)
    BIND(fn_InitLibrary, "FPDF_InitLibrary"); BIND(fn_DestroyLibrary, "FPDF_DestroyLibrary");
    BIND(fn_LoadDocument, "FPDF_LoadDocument"); BIND(fn_LoadMemDocument, "FPDF_LoadMemDocument");
    BIND(fn_CloseDocument, "FPDF_CloseDocument"); BIND(fn_GetPageCount, "FPDF_GetPageCount");
    BIND(fn_LoadPage, "FPDF_LoadPage"); BIND(fn_ClosePage, "FPDF_ClosePage");
    BIND(fn_GetPageWidth, "FPDF_GetPageWidth"); BIND(fn_GetPageHeight, "FPDF_GetPageHeight");
    BIND(fn_BitmapCreate, "FPDFBitmap_Create"); BIND(fn_BitmapFillRect, "FPDFBitmap_FillRect");
    BIND(fn_RenderPageBitmap, "FPDF_RenderPageBitmap"); BIND(fn_BitmapDestroy, "FPDFBitmap_Destroy");
    BIND(fn_BitmapGetBuffer, "FPDFBitmap_GetBuffer"); BIND(fn_BitmapGetStride, "FPDFBitmap_GetStride");
    #undef BIND
    if (!fn_InitLibrary || !fn_LoadMemDocument || !fn_RenderPageBitmap) {
        return false;
    }
    fn_InitLibrary();
    initialized = true;
    return true;
}

bool PdfiumWrapper::openDocument(const std::string& key, int fd) {
    return openDocumentWithPassword(key, fd, nullptr);
}

bool PdfiumWrapper::openDocumentWithPassword(const std::string& key, int fd, const char* password) {
    std::lock_guard<std::mutex> lock(mutex);
    if (handles.find(key) != handles.end()) return true;
    if (!load()) return false;

    off_t fileSize = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    if (fileSize <= 0) {
        return false;
    }

    void* mapped = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapped == MAP_FAILED) {
        return false;
    }

    FPDF_DOCUMENT doc = fn_LoadMemDocument(mapped, fileSize, password);

    if (!doc) {
        munmap(mapped, fileSize);
        return false;
    }

    if (handles.size() >= 5) {
        auto oldest = handles.begin();
        if (oldest->second.currentPage) fn_ClosePage(oldest->second.currentPage);
        if (oldest->second.doc) fn_CloseDocument(oldest->second.doc);
        if (oldest->second.mappedData) munmap(oldest->second.mappedData, oldest->second.mappedSize);
        handles.erase(oldest);
    }

    handles[key] = DocInfo{doc, nullptr, -1, fd, mapped, (size_t)fileSize, 0};
    return true;
}

void PdfiumWrapper::closeDocument(const std::string& key) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(key);
    if (it != handles.end()) {
        if (it->second.currentPage) fn_ClosePage(it->second.currentPage);
        if (it->second.doc) fn_CloseDocument(it->second.doc);
        if (it->second.mappedData) munmap(it->second.mappedData, it->second.mappedSize);
        if (it->second.fd >= 0) close(it->second.fd);
        handles.erase(it);
    }
}

bool PdfiumWrapper::isOpen(const std::string& key) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(key);
    return it != handles.end() && it->second.doc != nullptr;
}

int PdfiumWrapper::getPageCount(const std::string& key) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(key);
    if (it == handles.end() || !it->second.doc) return 0;
    return fn_GetPageCount(it->second.doc);
}

bool PdfiumWrapper::openPage(const std::string& key, int pageIndex) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(key);
    if (it == handles.end() || !it->second.doc) return false;

    if (it->second.currentPage && it->second.currentPageIdx != pageIndex) {
        fn_ClosePage(it->second.currentPage);
        it->second.currentPage = nullptr;
        it->second.currentPageIdx = -1;
    }

    if (!it->second.currentPage) {
        it->second.currentPage = fn_LoadPage(it->second.doc, pageIndex);
        it->second.currentPageIdx = pageIndex;
    }
    return it->second.currentPage != nullptr;
}

void PdfiumWrapper::closePage(const std::string& key) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(key);
    if (it != handles.end() && it->second.currentPage) {
        fn_ClosePage(it->second.currentPage);
        it->second.currentPage = nullptr;
        it->second.currentPageIdx = -1;
    }
}

bool PdfiumWrapper::getPageSize(const std::string& key, int pageIndex, double* outWidth, double* outHeight) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(key);
    if (it == handles.end() || !it->second.doc) return false;
    if (!load()) return false;

    FPDF_PAGE page = fn_LoadPage(it->second.doc, pageIndex);
    if (!page) return false;
    *outWidth = fn_GetPageWidth(page);
    *outHeight = fn_GetPageHeight(page);
    fn_ClosePage(page);
    return true;
}

void* PdfiumWrapper::renderPage(const std::string& key, int pageIndex, int width, int height, int dpi, bool isArgb, int* outStride) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = handles.find(key);
    if (it == handles.end() || !it->second.doc) return nullptr;
    if (!load()) return nullptr;

    if (width <= 0 || height <= 0 || dpi <= 0) {
        return nullptr;
    }

    if (it->second.currentPage && it->second.currentPageIdx != pageIndex) {
        fn_ClosePage(it->second.currentPage);
        it->second.currentPage = nullptr;
        it->second.currentPageIdx = -1;
    }

    if (!it->second.currentPage) {
        it->second.currentPage = fn_LoadPage(it->second.doc, pageIndex);
        it->second.currentPageIdx = pageIndex;
    }

    if (!it->second.currentPage) {
        return nullptr;
    }

    FPDF_BITMAP bitmap = fn_BitmapCreate(width, height, 1);
    if (!bitmap) {
        return nullptr;
    }

    fn_BitmapFillRect(bitmap, 0, 0, width, height, 0xFFFFFFFF);
    fn_RenderPageBitmap(bitmap, it->second.currentPage, 0, 0, width, height, 0, FPDF_PRINTING | FPDF_REVERSE_Y);

    unsigned char* buffer = fn_BitmapGetBuffer(bitmap);
    int stride = fn_BitmapGetStride(bitmap);
    size_t bufSize = (size_t)stride * (size_t)height;

    if (stride <= 0 || bufSize == 0) {
        fn_BitmapDestroy(bitmap);
        return nullptr;
    }

    if (outStride) *outStride = stride;
    return (void*)bitmap;
}

unsigned char* PdfiumWrapper::getBitmapBuffer(FPDF_BITMAP bitmap) {
    if (!bitmap || !load()) return nullptr;
    return fn_BitmapGetBuffer(bitmap);
}

void PdfiumWrapper::destroyBitmap(FPDF_BITMAP bitmap) {
    if (bitmap && load()) fn_BitmapDestroy(bitmap);
}

void PdfiumWrapper::closeAll() {
    std::lock_guard<std::mutex> lock(mutex);
    for (auto& kv : handles) {
        if (kv.second.currentPage) fn_ClosePage(kv.second.currentPage);
        if (kv.second.doc) fn_CloseDocument(kv.second.doc);
        if (kv.second.mappedData) munmap(kv.second.mappedData, kv.second.mappedSize);
    }
    handles.clear();
    if (mDlHandle) {
        dlclose(mDlHandle);
        mDlHandle = nullptr;
    }
}
