#include "native_reader.h"
#include <jni.h>
extern "C" {
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeInit(JNIEnv*env,jobject thiz,jint vw,jint vh,jboolean v){NativeReader::instance().init(vw,vh,v);}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeDestroy(JNIEnv*env,jobject thiz){NativeReader::instance().destroy(env);}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeSetPagePool(JNIEnv*env,jobject thiz,jint s,jobjectArray b){NativeReader::instance().setPagePool(env,s,b);}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeSetCurrentPage(JNIEnv*env,jobject thiz,jint p){NativeReader::instance().setCurrentPage(p);}
JNIEXPORT jint JNICALL Java_com_mangareader_native_NativeReader_nativeGetCurrentPage(JNIEnv*env,jobject thiz){return NativeReader::instance().getCurrentPage();}
JNIEXPORT jint JNICALL Java_com_mangareader_native_NativeReader_nativeGetTotalPages(JNIEnv*env,jobject thiz){return NativeReader::instance().getTotalPages();}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeSetTotalPages(JNIEnv*env,jobject thiz,jint t){NativeReader::instance().setTotalPages(t);}
JNIEXPORT jint JNICALL Java_com_mangareader_native_NativeReader_nativeOnTouchEvent(JNIEnv*env,jobject thiz,jint a,jfloat x1,jfloat y1,jfloat x2,jfloat y2,jint pc){return NativeReader::instance().onTouchEvent(a,x1,y1,x2,y2,pc);}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeRender(JNIEnv*env,jobject thiz,jobject c){NativeReader::instance().render(env,c);}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeScrollBy(JNIEnv*env,jobject thiz,jfloat dy){NativeReader::instance().scrollBy(dy);}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeSetClickFlipType(JNIEnv*env,jobject thiz,jint t){NativeReader::instance().setClickFlipType(t);}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeSetVertical(JNIEnv*env,jobject thiz,jboolean v){NativeReader::instance().setVertical(v);}
JNIEXPORT void JNICALL Java_com_mangareader_native_NativeReader_nativeSetViewSize(JNIEnv*env,jobject thiz,jint w,jint h){NativeReader::instance().setViewSize(w,h);}
JNIEXPORT jfloat JNICALL Java_com_mangareader_native_NativeReader_nativeGetScale(JNIEnv*env,jobject thiz){return NativeReader::instance().getState().scale;}
JNIEXPORT jfloat JNICALL Java_com_mangareader_native_NativeReader_nativeGetScrollY(JNIEnv*env,jobject thiz){return NativeReader::instance().getState().scrollY;}
} // extern "C"
