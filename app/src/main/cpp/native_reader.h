#pragma once
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <mutex>
#include <atomic>
#include <cstring>
#include <cstdlib>

#define TAG "NativeReader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct ReaderPage{uint8_t*pixels;int width,height,stride;bool isLoaded;uint64_t lastAccess;};
struct ReaderState{int viewWidth,viewHeight,currentPage,totalPages;float scale,offsetX,offsetY,minScale,maxScale,scrollY;bool isDragging;float pinchStartDist,pinchStartScale,lastTouchX,lastTouchY;int pointerCount;bool isVertical;int clickFlipType;};

class NativeReader{
public:
    static NativeReader&instance();
    void init(int vw,int vh,bool vert);
    void destroy(JNIEnv*env);
    void reset(JNIEnv*env);
    void setPagePool(JNIEnv*env,jint startIdx,jobjectArray bitmaps);
    void setCurrentPage(int p);
    int getCurrentPage()const;
    int getTotalPages()const;
    void setTotalPages(int t);
    int onTouchEvent(int action,float x1,float y1,float x2,float y2,int pc);
    void render(JNIEnv*env,jobject canvas);
    void scrollBy(float dy);
    void setClickFlipType(int t);
    void setVertical(bool v);
    void setViewSize(int w,int h);
    int getPageCount()const;
    const ReaderState& getState()const{return state;}
private:
    NativeReader();
    ~NativeReader()=default;
    NativeReader(const NativeReader&)=delete;
    NativeReader& operator=(const NativeReader&)=delete;
    void initJNICache(JNIEnv*env);
    void freeJNICache(JNIEnv*env);
    void renderVertical(JNIEnv*env,jobject canvas);
    void renderHorizontal(JNIEnv*env,jobject canvas);
    void clampOffset();
    int findPage(int idx);//must hold renderMutex
    void evictLRU();//must hold renderMutex
    void cleanupPool();//must hold renderMutex
    ReaderState state;
    mutable std::mutex renderMutex;
    static const int POOL_SIZE=10;
    ReaderPage pagePool[POOL_SIZE];
    int poolStart;
    std::atomic<uint64_t>accessCounter{0};
    jclass canvasClass;jmethodID drawBitmapMethod;
    jclass bmpClass;jmethodID createBitmapMethod;
    jclass configClass;jobject argbConfig;
    jclass rectClass;jmethodID rectCtor;
    jclass rectFClass;jmethodID rectFCtor;
    jfieldID rectRightField,rectBottomField;
    bool jniInitialized;
};
