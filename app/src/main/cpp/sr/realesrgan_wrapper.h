#pragma once

#include <string>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <ncnn/net.h>

class RealESRGANWrapper {
public:
    ncnn::Net net;
    int scale = 2;
    int tilesize = 0;
    int gpuid = -1;
    bool loaded = false;
    bool useFp16 = true;
    std::string modelType = "realesrgan";
    std::atomic<int> active_processes{0};
    std::mutex cv_mutex;
    std::condition_variable cv;

    bool load(const char* param_path, const char* model_path, int gpu_id, const char* model_type, int initial_scale);
    bool process(const ncnn::Mat& inimage, ncnn::Mat& outimage);
    void markInvalid();
    void waitForIdle();

private:
    int lastScale = 0;
    std::atomic<bool> is_valid{true};
    int calculateOptimalTileSize();
};
