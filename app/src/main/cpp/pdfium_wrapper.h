#ifndef PDFIUM_WRAPPER_H
#define PDFIUM_WRAPPER_H

#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <unordered_map>
#include <mutex>
#include <vector>

// pdfium types
typedef void* FPDF_DOCUMENT;
typedef void* FPDF_PAGE;
typedef void* FPDF_BITMAP;
typedef unsigned long FPDF_DWORD;

// pdfium function pointers
typedef void (*FPDF_InitLibrary)();
typedef void (*FPDF_DestroyLibrary)();
typedef FPDF_DOCUMENT (*FPDF_LoadDocument)(const char* file_path, const char* password);
typedef FPDF_DOCUMENT (*FPDF_LoadMemDocument)(const void* data, size_t size, const char* password);
typedef void (*FPDF_CloseDocument)(FPDF_DOCUMENT document);
typedef int (*FPDF_GetPageCount)(FPDF_DOCUMENT document);
typedef FPDF_PAGE (*FPDF_LoadPage)(FPDF_DOCUMENT document, int page_index);
typedef void (*FPDF_ClosePage)(FPDF_PAGE page);
typedef double (*FPDF_GetPageWidth)(FPDF_PAGE page);
typedef double (*FPDF_GetPageHeight)(FPDF_PAGE page);
typedef FPDF_BITMAP (*FPDFBitmap_Create)(int width, int height, int alpha);
typedef void (*FPDFBitmap_FillRect)(FPDF_BITMAP bitmap, int left, int top, int width, int height, FPDF_DWORD color);
typedef void (*FPDF_RenderPageBitmap)(FPDF_BITMAP bitmap, FPDF_PAGE page, int start_x, int start_y, int size_x, int size_y, int rotate, int flags);
typedef void (*FPDFBitmap_Destroy)(FPDF_BITMAP bitmap);
typedef unsigned char* (*FPDFBitmap_GetBuffer)(FPDF_BITMAP bitmap);
typedef int (*FPDFBitmap_GetStride)(FPDF_BITMAP bitmap);

// pdfium constants
#define FPDF_PRINTING 0x02
#define FPDF_REVERSE_Y 0x20
#define FPDFBitmap_BGRA 3
#define FPDFBitmap_BGR 2

class PdfiumWrapper {
public:
    static PdfiumWrapper& instance();

    bool load();
    bool openDocument(const std::string& key, int fd);
    bool openDocumentWithPassword(const std::string& key, int fd, const char* password);
    void closeDocument(const std::string& key);
    bool isOpen(const std::string& key);
    int getPageCount(const std::string& key);
    bool openPage(const std::string& key, int pageIndex);
    void closePage(const std::string& key);
    bool getPageSize(const std::string& key, int pageIndex, double* outWidth, double* outHeight);
    void* renderPage(const std::string& key, int pageIndex, int width, int height, int dpi, bool isArgb, int* outStride);
    unsigned char* getBitmapBuffer(FPDF_BITMAP bitmap);
    void destroyBitmap(FPDF_BITMAP bitmap);
    void closeAll();

private:
    PdfiumWrapper() = default;

    struct DocInfo {
        FPDF_DOCUMENT doc = nullptr;
        FPDF_PAGE currentPage = nullptr;
        int currentPageIdx = -1;
        int fd = -1;
        void* mappedData = nullptr;
        size_t mappedSize = 0;
        int fdRefCount = 0;
    };

    std::unordered_map<std::string, DocInfo> handles;
    std::mutex mutex;
    bool initialized = false;
    void* mDlHandle = nullptr;

    // Function pointers
    FPDF_InitLibrary fn_InitLibrary = nullptr;
    FPDF_DestroyLibrary fn_DestroyLibrary = nullptr;
    FPDF_LoadDocument fn_LoadDocument = nullptr;
    FPDF_LoadMemDocument fn_LoadMemDocument = nullptr;
    FPDF_CloseDocument fn_CloseDocument = nullptr;
    FPDF_GetPageCount fn_GetPageCount = nullptr;
    FPDF_LoadPage fn_LoadPage = nullptr;
    FPDF_ClosePage fn_ClosePage = nullptr;
    FPDF_GetPageWidth fn_GetPageWidth = nullptr;
    FPDF_GetPageHeight fn_GetPageHeight = nullptr;
    FPDFBitmap_Create fn_BitmapCreate = nullptr;
    FPDFBitmap_FillRect fn_BitmapFillRect = nullptr;
    FPDF_RenderPageBitmap fn_RenderPageBitmap = nullptr;
    FPDFBitmap_Destroy fn_BitmapDestroy = nullptr;
    FPDFBitmap_GetBuffer fn_BitmapGetBuffer = nullptr;
    FPDFBitmap_GetStride fn_BitmapGetStride = nullptr;
};

#endif // PDFIUM_WRAPPER_H
