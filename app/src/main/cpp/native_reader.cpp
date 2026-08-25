#include "native_reader.h"
NativeReader&NativeReader::instance(){static NativeReader r;return r;}
NativeReader::NativeReader():poolStart(0),jniInitialized(false){memset(&state,0,sizeof(ReaderState));state.scale=1.0f;state.minScale=0.5f;state.maxScale=5.0f;state.isVertical=true;state.clickFlipType=1;for(int i=0;i<POOL_SIZE;i++)pagePool[i]={nullptr,0,0,0,false,0};}
void NativeReader::cleanupPool(){for(int i=0;i<POOL_SIZE;i++){if(pagePool[i].pixels){free(pagePool[i].pixels);pagePool[i].pixels=nullptr;}pagePool[i].isLoaded=false;}}
void NativeReader::init(int vw,int vh,bool vert){std::lock_guard<std::mutex>l(renderMutex);state.viewWidth=vw;state.viewHeight=vh;state.isVertical=vert;state.currentPage=0;state.scale=1.0f;state.offsetX=0;state.offsetY=0;state.scrollY=0;state.isDragging=false;state.pointerCount=0;jniInitialized=false;}
void NativeReader::reset(JNIEnv*env){std::lock_guard<std::mutex>l(renderMutex);cleanupPool();memset(&state,0,sizeof(ReaderState));state.scale=1.0f;state.minScale=0.5f;state.maxScale=5.0f;state.isVertical=true;state.clickFlipType=1;accessCounter=0;poolStart=0;jniInitialized=false;freeJNICache(env);}
void NativeReader::destroy(JNIEnv*env){std::lock_guard<std::mutex>l(renderMutex);cleanupPool();freeJNICache(env);}
void NativeReader::initJNICache(JNIEnv*env){if(jniInitialized)return;
#define CHK(c)if(!c){freeJNICache(env);return;}
canvasClass=(jclass)env->NewGlobalRef(env->FindClass("android/graphics/Canvas"));CHK(canvasClass);
drawBitmapMethod=env->GetMethodID(canvasClass,"drawBitmap","(Landroid/graphics/Bitmap;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V");CHK(drawBitmapMethod);
bmpClass=(jclass)env->NewGlobalRef(env->FindClass("android/graphics/Bitmap"));CHK(bmpClass);
createBitmapMethod=env->GetStaticMethodID(bmpClass,"createBitmap","([IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");CHK(createBitmapMethod);
configClass=(jclass)env->NewGlobalRef(env->FindClass("android/graphics/Bitmap$Config"));CHK(configClass);
argbConfig=env->NewGlobalRef(env->GetStaticObjectField(configClass,env->GetStaticFieldID(configClass,"ARGB_8888","Landroid/graphics/Bitmap$Config;")));CHK(argbConfig);
rectClass=(jclass)env->NewGlobalRef(env->FindClass("android/graphics/Rect"));CHK(rectClass);
rectCtor=env->GetMethodID(rectClass,"<init>","(IIII)V");
rectRightField=env->GetFieldID(rectClass,"right","I");
rectBottomField=env->GetFieldID(rectClass,"bottom","I");
rectFClass=(jclass)env->NewGlobalRef(env->FindClass("android/graphics/RectF"));CHK(rectFClass);
rectFCtor=env->GetMethodID(rectFClass,"<init>","(FFFF)V");
jniInitialized=true;LOGI("JNI cache OK");
#undef CHK
}
void NativeReader::freeJNICache(JNIEnv*env){if(!env)return;std::lock_guard<std::mutex>l(renderMutex);
#define DR(r) if(r){env->DeleteGlobalRef(r);r=nullptr;}
DR(canvasClass);DR(bmpClass);DR(configClass);DR(argbConfig);DR(rectClass);DR(rectFClass);
#undef DR
jniInitialized=false;}
int NativeReader::findPage(int idx){for(int i=0;i<POOL_SIZE;i++){if(pagePool[i].isLoaded&&(poolStart+i)==idx)return i;}return -1;}
void NativeReader::evictLRU(){int o=0;uint64_t ot=~(uint64_t)0;for(int i=0;i<POOL_SIZE;i++){if(pagePool[i].isLoaded&&pagePool[i].lastAccess<ot){ot=pagePool[i].lastAccess;o=i;}}if(pagePool[o].pixels){free(pagePool[o].pixels);pagePool[o].pixels=nullptr;}pagePool[o].isLoaded=false;}
void NativeReader::setPagePool(JNIEnv*env,jint startIdx,jobjectArray bitmaps){std::lock_guard<std::mutex>l(renderMutex);cleanupPool();poolStart=startIdx;accessCounter=0;
int count=env->GetArrayLength(bitmaps);if(count>POOL_SIZE)count=POOL_SIZE;
for(int i=0;i<count;i++){jobject bmp=env->GetObjectArrayElement(bitmaps,i);if(!bmp)continue;AndroidBitmapInfo info;if(AndroidBitmap_getInfo(env,bmp,&info)!=ANDROID_BITMAP_RESULT_SUCCESS){env->DeleteLocalRef(bmp);continue;}
if(info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888){env->DeleteLocalRef(bmp);continue;}
void*pix=nullptr;if(AndroidBitmap_lockPixels(env,bmp,&pix)!=ANDROID_BITMAP_RESULT_SUCCESS){env->DeleteLocalRef(bmp);continue;}
int sz=info.stride*info.height;uint8_t*p=(uint8_t*)malloc(sz);if(!p){AndroidBitmap_unlockPixels(env,bmp);env->DeleteLocalRef(bmp);LOGE("malloc fail page %d",i);continue;}
memcpy(p,pix,sz);pagePool[i]={p,(int)info.width,(int)info.height,(int)info.stride,true,++accessCounter};
AndroidBitmap_unlockPixels(env,bmp);env->DeleteLocalRef(bmp);}}
void NativeReader::setCurrentPage(int p){std::lock_guard<std::mutex>l(renderMutex);state.currentPage=p;}
int NativeReader::getCurrentPage()const{std::lock_guard<std::mutex>l(renderMutex);return state.currentPage;}
int NativeReader::getTotalPages()const{std::lock_guard<std::mutex>l(renderMutex);return state.totalPages;}
int NativeReader::getPageCount()const{std::lock_guard<std::mutex>l(renderMutex);return state.totalPages;}
void NativeReader::setTotalPages(int t){std::lock_guard<std::mutex>l(renderMutex);state.totalPages=t;}
void NativeReader::setClickFlipType(int t){std::lock_guard<std::mutex>l(renderMutex);state.clickFlipType=t;}
void NativeReader::setVertical(bool v){std::lock_guard<std::mutex>l(renderMutex);state.isVertical=v;}
void NativeReader::scrollBy(float dy){std::lock_guard<std::mutex>l(renderMutex);state.scrollY+=dy;}
void NativeReader::setViewSize(int w,int h){std::lock_guard<std::mutex>l(renderMutex);state.viewWidth=w;state.viewHeight=h;}
int NativeReader::onTouchEvent(int action,float x1,float y1,float x2,float y2,int pc){std::lock_guard<std::mutex>l(renderMutex);
switch(action){case 0:state.lastTouchX=x1;state.lastTouchY=y1;state.isDragging=false;state.pointerCount=1;return 0;
case 1:if(!state.isDragging&&state.pointerCount==1){float fx=state.lastTouchX/state.viewWidth;if(state.isVertical)return 6;if(state.clickFlipType==1){if(fx<0.35f)return 4;if(fx>0.65f)return 5;}return 6;}state.pointerCount=0;state.isDragging=false;state.pinchStartDist=0;state.pinchStartScale=state.scale;return 0;
case 2:if(pc==1&&state.scale<=1.01f){float dy=y1-state.lastTouchY;if(state.isVertical)state.scrollY-=dy;else state.offsetX+=(x1-state.lastTouchX);state.lastTouchX=x1;state.lastTouchY=y1;state.isDragging=true;return 1;}else if(pc>=2){float d=sqrtf((x2-x1)*(x2-x1)+(y2-y1)*(y2-y1));if(state.pointerCount<2){state.pinchStartDist=d;state.pinchStartScale=state.scale;}float ns=state.pinchStartScale*(d/state.pinchStartDist);ns=std::max(state.minScale,std::min(ns,state.maxScale));float cx=(x1+x2)/2,cy=(y1+y2)/2,r=ns/state.scale;state.offsetX=cx-(cx-state.offsetX)*r;state.offsetY=cy-(cy-state.offsetY)*r;state.scale=ns;state.pointerCount=pc;state.isDragging=true;return 2;}state.pointerCount=pc;return 0;
case 3:case 4:state.pointerCount=pc;if(pc>=2){state.pinchStartDist=sqrtf((x2-x1)*(x2-x1)+(y2-y1)*(y2-y1));state.pinchStartScale=state.scale;}return 0;
case 5:state.pointerCount=0;state.isDragging=false;state.pinchStartDist=0;state.pinchStartScale=state.scale;return 0;}return 0;}
void NativeReader::clampOffset(){if(state.isVertical){float mx=state.viewHeight*state.totalPages*state.scale-state.viewHeight;if(mx<0)mx=0;state.scrollY=std::max(0.0f,std::min(state.scrollY,mx));}else{float sw=state.viewWidth*state.scale,sh=state.viewHeight*state.scale;if(sw<=state.viewWidth)state.offsetX=0;else{float m=(sw-state.viewWidth)/2;state.offsetX=std::max(-m,std::min(state.offsetX,m));}if(sh<=state.viewHeight)state.offsetY=0;else{float m=(sh-state.viewHeight)/2;state.offsetY=std::max(-m,std::min(state.offsetY,m));}}}
void NativeReader::render(JNIEnv*env,jobject canvas){std::lock_guard<std::mutex>l(renderMutex);if(!jniInitialized)initJNICache(env);if(!jniInitialized)return;clampOffset();state.isVertical?renderVertical(env,canvas):renderHorizontal(env,canvas);}
void NativeReader::renderVertical(JNIEnv*env,jobject canvas){if(!jniInitialized)return;
int ph=state.viewHeight;float sph=ph*state.scale;int sp=(int)(state.scrollY/sph);int ep=std::min(sp+3,state.totalPages-1);
jobject srcR=env->NewObject(rectClass,rectCtor,0,0,1,1);if(!srcR||env->ExceptionCheck()){if(srcR)env->DeleteLocalRef(srcR);if(env->ExceptionCheck())env->ExceptionClear();return;}
for(int i=sp;i<=ep;i++){if(i<0||i>=state.totalPages)continue;int pi=findPage(i);if(pi<0)continue;ReaderPage&pg=pagePool[pi];if(!pg.pixels||pg.width<=0||pg.height<=0)continue;pg.lastAccess=accessCounter.fetch_add(1);
float yo=i*sph-state.scrollY;float fs=std::min((float)state.viewWidth/pg.width,(float)ph/pg.height);float dw=pg.width*fs*state.scale,dh=pg.height*fs*state.scale;float dx=(state.viewWidth-dw)/2+state.offsetX;float dy=yo+(ph-dh)/2+state.offsetY;
jintArray pa=env->NewIntArray(pg.width*pg.height);if(!pa)continue;jint*dst=env->GetIntArrayElements(pa,nullptr);for(int y=0;y<pg.height;y++){uint8_t*row=pg.pixels+y*pg.stride;for(int x=0;x<pg.width;x++)dst[y*pg.width+x]=(row[x*4+3]<<24)|(row[x*4]<<16)|(row[x*4+1]<<8)|row[x*4+2];}env->ReleaseIntArrayElements(pa,dst,0);
jobject bmp=env->CallStaticObjectMethod(bmpClass,createBitmapMethod,pa,pg.width,argbConfig);env->DeleteLocalRef(pa);if(env->ExceptionCheck()){env->ExceptionClear();continue;}if(!bmp)continue;
env->SetIntField(srcR,rectRightField,pg.width);env->SetIntField(srcR,rectBottomField,pg.height);
jobject dstR=env->NewObject(rectFClass,rectFCtor,dx,dy,dx+dw,dy+dh);if(!dstR){env->DeleteLocalRef(bmp);continue;}
env->CallVoidMethod(canvas,drawBitmapMethod,bmp,srcR,dstR,nullptr);if(env->ExceptionCheck())env->ExceptionClear();
env->DeleteLocalRef(bmp);env->DeleteLocalRef(dstR);}env->DeleteLocalRef(srcR);}
void NativeReader::renderHorizontal(JNIEnv*env,jobject canvas){if(!jniInitialized)return;
int pi=findPage(state.currentPage);if(pi<0)return;ReaderPage&pg=pagePool[pi];if(!pg.pixels||pg.width<=0||pg.height<=0)return;pg.lastAccess=accessCounter.fetch_add(1);
float fs=std::min((float)state.viewWidth/pg.width,(float)state.viewHeight/pg.height);float dw=pg.width*fs*state.scale,dh=pg.height*fs*state.scale;float dx=(state.viewWidth-dw)/2+state.offsetX;float dy=(state.viewHeight-dh)/2+state.offsetY;
jintArray pa=env->NewIntArray(pg.width*pg.height);if(!pa)return;jint*dst=env->GetIntArrayElements(pa,nullptr);for(int y=0;y<pg.height;y++){uint8_t*row=pg.pixels+y*pg.stride;for(int x=0;x<pg.width;x++)dst[y*pg.width+x]=(row[x*4+3]<<24)|(row[x*4]<<16)|(row[x*4+1]<<8)|row[x*4+2];}env->ReleaseIntArrayElements(pa,dst,0);
jobject bmp=env->CallStaticObjectMethod(bmpClass,createBitmapMethod,pa,pg.width,argbConfig);env->DeleteLocalRef(pa);if(env->ExceptionCheck()){env->ExceptionClear();return;}if(!bmp)return;
jobject srcR=env->NewObject(rectClass,rectCtor,0,0,pg.width,pg.height);jobject dstR=env->NewObject(rectFClass,rectFCtor,dx,dy,dx+dw,dy+dh);
if(srcR&&dstR)env->CallVoidMethod(canvas,drawBitmapMethod,bmp,srcR,dstR,nullptr);if(env->ExceptionCheck())env->ExceptionClear();
if(bmp)env->DeleteLocalRef(bmp);if(srcR)env->DeleteLocalRef(srcR);if(dstR)env->DeleteLocalRef(dstR);}
