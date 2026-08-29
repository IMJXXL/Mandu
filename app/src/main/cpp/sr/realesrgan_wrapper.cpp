#include "realesrgan_wrapper.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <unistd.h>
#include <vector>
#include <cmath>

#define LOG_TAG "RealESRGANJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

int RealESRGANWrapper::calculateOptimalTileSize() {
    uint32_t available_mb = 0;

    long page_size = sysconf(_SC_PAGESIZE);
    long phys_pages = sysconf(_SC_PHYS_PAGES);
    uint32_t total_ram_mb = 0;
    if (page_size > 0 && phys_pages > 0) {
        total_ram_mb = static_cast<uint32_t>(
            (static_cast<long long>(phys_pages) * page_size) / (1024LL * 1024LL)
        );
    }
    LOGI("Total RAM: %u MB", total_ram_mb);

    uint32_t safe_ram_mb = total_ram_mb / 5;
    available_mb = safe_ram_mb;
    LOGI("Effective available (RAM/5): %u MB", available_mb);

    int optimal;
    if (available_mb >= 2500) {
        optimal = 400;
    } else if (available_mb >= 1200) {
        optimal = 200;
    } else if (available_mb >= 500) {
        optimal = 100;
    } else {
        optimal = 64;
    }

    float bytes_per_pixel = useFp16 ? 2.0f : 4.0f;
    float tile_input_mb = (optimal * optimal * 3 * bytes_per_pixel) / (1024.0f * 1024.0f);
    float intermediate_multiplier = (modelType == "realcugan") ? 20.0f : 40.0f;
    float tile_total_mb = tile_input_mb * (1.0f + scale * scale) * intermediate_multiplier;

    if (tile_total_mb > available_mb * 0.8f) {
        LOGI("tile_size=%d needs ~%.0f MB, exceeds 80%% of available %u MB, reducing",
             optimal, tile_total_mb, available_mb);
        while (optimal > 64) {
            optimal = (optimal == 400) ? 200 : (optimal == 200) ? 100 : 64;
            tile_input_mb = (optimal * optimal * 3 * bytes_per_pixel) / (1024.0f * 1024.0f);
            tile_total_mb = tile_input_mb * (1.0f + scale * scale) * intermediate_multiplier;
            if (tile_total_mb <= available_mb * 0.8f) break;
        }
    }

    LOGI("Optimal tile_size: %d (available=%u MB, model=%s, scale=%d, fp16=%d)",
         optimal, available_mb, modelType.c_str(), scale, useFp16);
    return optimal;
}

void RealESRGANWrapper::markInvalid() {
    is_valid.store(false, std::memory_order_release);
}

void RealESRGANWrapper::waitForIdle() {
    // 仅由 nativeRelease 调用（ tam 对象将在此销毁），绝不能将于超过的实际还停在层推理的中途中断
    std::unique_lock<std::mutex> lock(cv_mutex);
    cv.wait(lock, [this]() {
        return active_processes.load(std::memory_order_acquire) == 0;
    });
}

bool RealESRGANWrapper::load(const char* param_path, const char* model_path, int gpu_id, const char* model_type, int initial_scale) {
    gpuid = gpu_id;
    modelType = model_type ? model_type : "realesrgan";
    scale = initial_scale > 0 ? initial_scale : 2;
    lastScale = scale;

    if (gpuid >= 0) {
        net.opt.use_vulkan_compute = true;
        int device_id = (gpuid < ncnn::get_gpu_count()) ? gpuid : ncnn::get_default_gpu_index();
        net.set_vulkan_device(device_id);
        net.opt.num_threads = 4;
    }

    net.opt.use_fp16_storage = useFp16;
    // 稳定性优先：fp16 算术在部分 Adreno/Android12 驱动上有已知崩溃面，关掉；storage 仍省一半显存
    net.opt.use_fp16_arithmetic = false;

    int ret = net.load_param(param_path);
    if (ret != 0) {
        LOGE("Failed to load param (ret=%d): %s", ret, param_path);
        return false;
    }
    LOGI("Param loaded ok, loading model...");

    ret = net.load_model(model_path);
    if (ret != 0) {
        LOGE("Failed to load model (ret=%d, gpuid=%d, vulkan=%d): %s", ret, gpuid,
             net.opt.use_vulkan_compute ? 1 : 0, model_path);
        return false;
    }
    LOGI("Model loaded ok (gpuid=%d)", gpuid);

    // ★ waifu2x cunet 探针自检：固定 312×312 输入必须产出 256×256；
    //   失败（驱动不支持固定形状等）→ 直接标记加载失败，UI 层灰掉该档，绝不带病运行
    if (modelType == "waifu2x_cunet") {
        ncnn::Mat pin(312, 312, 3);
        pin.fill(0.5f);
        ncnn::Mat pout;
        ncnn::Extractor pex = net.create_extractor();
        pex.input("Input1", pin);
        int pret = pex.extract("Eltwise4", pout);
        if (pret != 0 || pout.empty() || pout.w != 256 || pout.h != 256) {
            LOGE("waifu2x cunet probe FAILED: ret=%d out=%dx%d (expect 256x256)", pret, pout.w, pout.h);
            return false;
        }
        LOGI("waifu2x cunet probe OK: 312 -> %dx%d", pout.w, pout.h);
    }

    tilesize = calculateOptimalTileSize();

    loaded = true;
    LOGI("Model loaded: type=%s, param=%s, model=%s, gpuid=%d, fp16=%d, scale=%d, tilesize=%d",
         modelType.c_str(), param_path, model_path, gpuid, useFp16, scale, tilesize);
    return true;
}

bool RealESRGANWrapper::process(const ncnn::Mat& inimage, ncnn::Mat& outimage) {
    active_processes.fetch_add(1, std::memory_order_acq_rel);
    if (!is_valid.load(std::memory_order_acquire) || abortFlag.exchange(false)) {
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        cv.notify_all();
        LOGE("Wrapper invalid, cannot process");
        return false;
    }

    if (!loaded) {
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        cv.notify_all();
        LOGE("Model not loaded");
        return false;
    }

    abortFlag.store(false);   // ★ 新一轮推理清除中止标志

    if (scale != lastScale) {
        LOGI("Scale changed from %d to %d, recalculating tile_size", lastScale, scale);
        tilesize = calculateOptimalTileSize();
        lastScale = scale;
    }

    int w = inimage.w;
    int h = inimage.h;
    int out_w = w * scale;
    int out_h = h * scale;
    int tile_size = tilesize > 0 ? tilesize : 200;

    int prepadding;
    if (modelType == "realcugan") {
        prepadding = (scale == 2) ? 18 : (scale == 4) ? 19 : 10;
    } else {
        prepadding = 10;
    }

    // ===== waifu2x cunet 1x：固定 312 输入 / 256 业务区 / 28 贴边（blob: Input1→Eltwise4）=====
    // 每个输入 tile 恒为 312×312（越界 replicate 填满，页小于 312 也填满），输出 256 写回业务网格。
    if (modelType == "waifu2x_cunet") {
        const int BIZ = 256, PADW = 28, TIN = 312;
        // ★ 必须先分配输出（此前漏掉导致写空指针闪退）
        outimage.create(out_w, out_h, 3);
        if (outimage.empty()) {
            LOGE("waifu2x: outimage alloc failed (%dx%d)", out_w, out_h);
            active_processes.fetch_sub(1, std::memory_order_acq_rel);
            { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
            return false;
        }
        // 预填最近邻仅作极端兜底（网格应全铺满）
        for (int c = 0; c < 3; c++) {
            const float* src_ch = inimage.channel(c);
            float* dst_ch = outimage.channel(c);
            for (int y = 0; y < out_h; y++) {
                int sy = y / scale; if (sy >= h) sy = h - 1;
                for (int x = 0; x < out_w; x++) {
                    int sx = x / scale; if (sx >= w) sx = w - 1;
                    dst_ch[(size_t)y * out_w + x] = src_ch[(size_t)sy * w + sx];
                }
            }
        }
        const int gx = (w + BIZ - 1) / BIZ;
        const int gy = (h + BIZ - 1) / BIZ;
        for (int yi = 0; yi < gy; yi++) {
            for (int xi = 0; xi < gx; xi++) {
                if (!is_valid.load(std::memory_order_acquire) || abortFlag.exchange(false)) {
                    LOGE("waifu2x invalidated during tiling");
                    active_processes.fetch_sub(1, std::memory_order_acq_rel);
                    { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                    return false;
                }
                ncnn::Mat in_tile(TIN, TIN, 3);
                if (in_tile.empty()) {
                    LOGE("waifu2x: in_tile alloc failed (%d,%d)", xi, yi);
                    active_processes.fetch_sub(1, std::memory_order_acq_rel);
                    { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                    return false;
                }
                const int sx0 = xi * BIZ - PADW, sy0 = yi * BIZ - PADW;
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < TIN; y++) {
                        int sy = sy0 + y; if (sy < 0) sy = 0; else if (sy >= h) sy = h - 1;
                        const float* src_row = static_cast<const float*>(inimage.channel(c).row(sy));
                        float* dst_row = static_cast<float*>(in_tile.channel(c).row(y));
                        for (int x = 0; x < TIN; x++) {
                            int sx = sx0 + x; if (sx < 0) sx = 0; else if (sx >= w) sx = w - 1;
                            dst_row[x] = src_row[sx];
                        }
                    }
                }
                ncnn::Mat out_tile;
                {
                    ncnn::Extractor ex = net.create_extractor();
                    ex.input("Input1", in_tile);
                    int ret = ex.extract("Eltwise4", out_tile);
                    if (ret != 0 || out_tile.empty() || out_tile.c < 3 || out_tile.w != BIZ || out_tile.h != BIZ) {
                        LOGE("waifu2x tile (%d,%d) failed: ret=%d out=%dx%d", xi, yi, ret,
                             out_tile.w, out_tile.h);
                        active_processes.fetch_sub(1, std::memory_order_acq_rel);
                        { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                        return false;
                    }
                }
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < BIZ; y++) {
                        const int dst_y = yi * BIZ + y;
                        if (dst_y >= out_h) break;
                        const float* src_row = static_cast<const float*>(out_tile.channel(c).row(y));
                        float* dst_row = static_cast<float*>(outimage.channel(c).row(dst_y));
                        for (int x = 0; x < BIZ; x++) {
                            const int dst_x = xi * BIZ + x;
                            if (dst_x >= out_w) break;
                            dst_row[dst_x] = src_row[x];
                        }
                    }
                }
            }
        }
        LOGI("waifu2x process complete: %dx%d (grid %dx%d)", w, h, gx, gy);
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
        return true;
    }

    outimage.create(out_w, out_h, 3);
    if (!outimage.empty()) {
        // 填充保底：先放最近邻放大的原图内容，面板上边界/瑕疵时看不到"未填像素"
        // （后续 tile 会回填正确元组；只能解决个别 tile 错就是由于 tile 全卡的致命性）
        for (int c = 0; c < 3; c++) {
            const float* src_ch = inimage.channel(c);
            float* dst_ch = outimage.channel(c);
            for (int y = 0; y < out_h; y++) {
                int sy = y / scale;
                if (sy < 0) sy = 0; if (sy >= h) sy = h - 1;
                for (int x = 0; x < out_w; x++) {
                    int sx = x / scale;
                    if (sx < 0) sx = 0; if (sx >= w) sx = w - 1;
                    dst_ch[y * out_w + x] = src_ch[sy * w + sx];
                }
            }
        }
    }
    if (outimage.empty()) {
        LOGE("Failed to create output Mat");
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
        return false;
    }

    int xtiles = (w + tile_size - 1) / tile_size;
    int ytiles = (h + tile_size - 1) / tile_size;

    if (modelType == "realcugan") {
        // ===== Real-CUGAN path: compensate CROP with edge-replicate padding =====
        const char* input_blob = "in0";
        const char* output_blob = "out0";

        if (xtiles == 1 && ytiles == 1) {
            // Full image fits in one tile — pad globally then infer
            ncnn::Mat padded(w + 2 * prepadding, h + 2 * prepadding, 3);
            if (padded.empty()) {
                LOGE("Failed to create padded Mat");
                active_processes.fetch_sub(1, std::memory_order_acq_rel);
                { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                return false;
            }
            for (int c = 0; c < 3; c++) {
                const float* src_ch = inimage.channel(c);
                float* dst_ch = padded.channel(c);
                int pw = padded.w, ph = padded.h;
                for (int y = 0; y < h; y++) {
                    memcpy(dst_ch + (y + prepadding) * pw + prepadding,
                           src_ch + y * w, w * sizeof(float));
                }
                for (int y = 0; y < h; y++) {
                    float lv = dst_ch[(y + prepadding) * pw + prepadding];
                    float rv = dst_ch[(y + prepadding) * pw + pw - 1 - prepadding];
                    for (int p = 0; p < prepadding; p++) {
                        dst_ch[(y + prepadding) * pw + p] = lv;
                        dst_ch[(y + prepadding) * pw + pw - 1 - p] = rv;
                    }
                }
                for (int p = 0; p < prepadding; p++) {
                    memcpy(dst_ch + p * pw, dst_ch + prepadding * pw, pw * sizeof(float));
                }
                for (int p = 0; p < prepadding; p++) {
                    memcpy(dst_ch + (ph - 1 - p) * pw,
                           dst_ch + (ph - 1 - prepadding) * pw, pw * sizeof(float));
                }
            }

            ncnn::Mat out_tile;
            ncnn::Extractor ex = net.create_extractor();
            ex.input(input_blob, padded);
            ex.extract(output_blob, out_tile);

            // With prepadding: (w+2pp)*scale - CROP = w*scale = out_w
            if (out_tile.empty() || out_tile.w < out_w || out_tile.h < out_h) {
                LOGE("RealCUGAN inference failed or output too small: %dx%d vs expected %dx%d",
                     out_tile.w, out_tile.h, out_w, out_h);
                active_processes.fetch_sub(1, std::memory_order_acq_rel);
                { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                return false;
            }

            // 若输出比预期大（模型不裁剪），有效区域居中，跳过边缘；
            // Real-CUGAN 裁剪模型输出正好等于 out_w×out_h，skip 为 0。
            int skip_x = (out_tile.w - out_w) / 2;
            int skip_y = (out_tile.h - out_h) / 2;
            if (skip_x < 0) skip_x = 0;
            if (skip_y < 0) skip_y = 0;
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < out_h; y++) {
                    const float* src_row = static_cast<const float*>(out_tile.channel(c).row(skip_y + y));
                    float* dst_row = static_cast<float*>(outimage.channel(c).row(y));
                    memcpy(dst_row, src_row + skip_x, out_w * sizeof(float));
                }
            }
            LOGD("RealCUGAN (no tiling): %dx%d -> %dx%d", w, h, out_w, out_h);
            active_processes.fetch_sub(1, std::memory_order_acq_rel);
            { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
            return true;
        }

        // Tiling: extract overlapping tiles with edge-replicate out-of-bounds
        int total_tiles = xtiles * ytiles;
        int processed = 0;

        for (int yi = 0; yi < ytiles; yi++) {
            for (int xi = 0; xi < xtiles; xi++) {
                if (!is_valid.load(std::memory_order_acquire) || abortFlag.exchange(false)) {
                    LOGE("Wrapper invalidated during tiling, aborting");
                    active_processes.fetch_sub(1, std::memory_order_acq_rel);
                    { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                    return false;
                }

                int sx = xi * tile_size - prepadding;
                int sy = yi * tile_size - prepadding;
                int tw = tile_size + 2 * prepadding;
                int th = tile_size + 2 * prepadding;

                ncnn::Mat in_tile(tw, th, 3);
                if (in_tile.empty()) {
                    LOGE("RealCUGAN: failed to create in_tile for tile (%d,%d)", xi, yi);
                    active_processes.fetch_sub(1, std::memory_order_acq_rel);
                    { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                    return false;
                }
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < th; y++) {
                        int src_y = std::max(0, std::min(sy + y, h - 1));
                        const float* src_row = static_cast<const float*>(inimage.channel(c).row(src_y));
                        float* dst_row = static_cast<float*>(in_tile.channel(c).row(y));
                        for (int x = 0; x < tw; x++) {
                            int src_x = std::max(0, std::min(sx + x, w - 1));
                            dst_row[x] = src_row[src_x];
                        }
                    }
                }

                LOGD("RealCUGAN Tile (%d/%d,%d/%d): extract sx=%d sy=%d tw=%d th=%d",
                     xi, xtiles, yi, ytiles, sx, sy, tw, th);

                ncnn::Mat out_tile;
                {
                    ncnn::Extractor ex = net.create_extractor();
                    ex.input(input_blob, in_tile);
                    int ret = ex.extract(output_blob, out_tile);
                    LOGD("RealCUGAN Tile (%d,%d): ret=%d out=%dx%dx%d",
                         xi, yi, ret, out_tile.w, out_tile.h, out_tile.c);
                    if (ret != 0 || out_tile.empty()) {
                        LOGE("Inference failed for tile (%d,%d), ret=%d", xi, yi, ret);
                        active_processes.fetch_sub(1, std::memory_order_acq_rel);
                        { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                        return false;
                    }
                }

                // Real-CUGAN 是无填充卷积模型：输出即输入 [xi*ts, xi*ts+ts) 区域的放大结果，
                // 必须整块写到 (xi*ts*scale, yi*ts*scale)。若起始点减去 prepadding，
                // 整图会向左上错 36px，右/下边缘露出未覆盖区域（颜色异常带）。
                // src_off 自适应：不裁剪模型（输出含 prepadding 圈）时跳过边缘即可。
                int src_off_x = (out_tile.w - tile_size * scale) / 2;
                int src_off_y = (out_tile.h - tile_size * scale) / 2;
                if (src_off_x < 0) src_off_x = 0;
                if (src_off_y < 0) src_off_y = 0;
                int copy_w = out_tile.w - 2 * src_off_x;
                int copy_h = out_tile.h - 2 * src_off_y;
                int out_x0 = xi * tile_size * scale;
                int out_y0 = yi * tile_size * scale;

                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < copy_h; y++) {
                        int dst_y = out_y0 + y;
                        if (dst_y < 0 || dst_y >= out_h) continue;
                        const float* src_row = static_cast<const float*>(out_tile.channel(c).row(src_off_y + y));
                        float* dst_row = static_cast<float*>(outimage.channel(c).row(dst_y));
                        for (int x = 0; x < copy_w; x++) {
                            int dst_x = out_x0 + x;
                            if (dst_x < 0 || dst_x >= out_w) continue;
                            dst_row[dst_x] = src_row[src_off_x + x];
                        }
                    }
                }

                processed++;
                if (processed % 10 == 0 || processed == total_tiles) {
                    LOGI("RealCUGAN tiling progress: %d/%d", processed, total_tiles);
                }
            }
        }

        LOGI("RealCUGAN process complete: %dx%d -> %dx%d (tiles: %dx%d, tilesize=%d)",
             w, h, out_w, out_h, xtiles, ytiles, tile_size);
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
        return true;
    }

    // ===== RealESRGAN path (unchanged) =====
    if (xtiles == 1 && ytiles == 1) {
        ncnn::Mat out_tile;
        ncnn::Extractor ex = net.create_extractor();
        const char* input_blob = "data";
        const char* output_blob = "output";
        ex.input(input_blob, inimage);
        ex.extract(output_blob, out_tile);

        if (out_tile.empty() || out_tile.w < out_w || out_tile.h < out_h) {
            LOGE("Inference failed or output too small: %dx%dx%d vs expected %dx%d",
                 out_tile.w, out_tile.h, out_tile.c, out_w, out_h);
            active_processes.fetch_sub(1, std::memory_order_acq_rel);
            { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
            return false;
        }

        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < out_h; y++) {
                const float* src_row = static_cast<const float*>(out_tile.channel(c).row(y));
                float* dst_row = static_cast<float*>(outimage.channel(c).row(y));
                memcpy(dst_row, src_row, out_w * sizeof(float));
            }
        }
        LOGI("Process complete (no tiling): %dx%d -> %dx%d", w, h, out_w, out_h);
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
        return true;
    }

    const char* input_blob = "data";
    const char* output_blob = "output";

    int total_tiles = xtiles * ytiles;
    int processed = 0;

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            if (!is_valid.load(std::memory_order_acquire) || abortFlag.exchange(false)) {
                LOGE("Wrapper invalidated during tiling, aborting");
                active_processes.fetch_sub(1, std::memory_order_acq_rel);
                { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                return false;
            }

            int tile_x0 = std::max(xi * tile_size - prepadding, 0);
            int tile_y0 = std::max(yi * tile_size - prepadding, 0);
            int tile_x1 = std::min((xi + 1) * tile_size + prepadding, w);
            int tile_y1 = std::min((yi + 1) * tile_size + prepadding, h);
            int tile_w = tile_x1 - tile_x0;
            int tile_h = tile_y1 - tile_y0;

            int out_x0 = xi * tile_size * scale;
            int out_y0 = yi * tile_size * scale;
            int out_x1 = std::min((xi + 1) * tile_size, w) * scale;
            int out_y1 = std::min((yi + 1) * tile_size, h) * scale;
            int out_tile_w = out_x1 - out_x0;
            int out_tile_h = out_y1 - out_y0;

            LOGD("Tile (%d/%d,%d/%d): in_tile=[%d×%d] prepad=[%d,%d] out_region=[%d,%d,%d,%d]",
                 xi, xtiles, yi, ytiles, tile_w, tile_h, prepadding, prepadding,
                 out_x0, out_y0, out_x1, out_y1);

            ncnn::Mat in_tile(tile_w, tile_h, 3);
            if (in_tile.empty()) {
                LOGE("Failed to create in_tile for tile (%d,%d)", xi, yi);
                active_processes.fetch_sub(1, std::memory_order_acq_rel);
                { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                return false;
            }
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < tile_h; y++) {
                    const float* src_row = static_cast<const float*>(inimage.channel(c).row(tile_y0 + y));
                    float* dst_row = static_cast<float*>(in_tile.channel(c).row(y));
                    memcpy(dst_row, src_row + tile_x0, tile_w * sizeof(float));
                }
            }

            ncnn::Mat out_tile;
            {
                ncnn::Extractor ex = net.create_extractor();
                ex.input(input_blob, in_tile);
                LOGD("Tile (%d,%d): ex.extract input=%dx%d", xi, yi, tile_w, tile_h);
                int ret = ex.extract(output_blob, out_tile);
                LOGD("Tile (%d,%d): ex.extract done ret=%d out=%dx%dx%d",
                     xi, yi, ret, out_tile.w, out_tile.h, out_tile.c);
                if (ret != 0 || out_tile.empty()) {
                    LOGE("Inference failed for tile (%d,%d), ret=%d", xi, yi, ret);
                    active_processes.fetch_sub(1, std::memory_order_acq_rel);
                    { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                    return false;
                }
            }

            int prepad_x = (xi * tile_size - tile_x0) * scale;
            int prepad_y = (yi * tile_size - tile_y0) * scale;

            if (prepad_y + out_tile_h > out_tile.h || prepad_x + out_tile_w > out_tile.w) {
                LOGE("Tile (%d,%d): out_tile too small %dx%d, need at least %dx%d",
                     xi, yi, out_tile.w, out_tile.h,
                     prepad_x + out_tile_w, prepad_y + out_tile_h);
                active_processes.fetch_sub(1, std::memory_order_acq_rel);
                { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                return false;
            }

            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < out_tile_h; y++) {
                    int dst_y = out_y0 + y;
                    if (dst_y >= out_h) continue;
                    const float* src_row = static_cast<const float*>(out_tile.channel(c).row(prepad_y + y));
                    float* dst_row = static_cast<float*>(outimage.channel(c).row(dst_y));
                    for (int x = 0; x < out_tile_w; x++) {
                        int dst_x = out_x0 + x;
                        if (dst_x >= out_w) break;
                        dst_row[dst_x] = src_row[prepad_x + x];
                    }
                }
            }

            processed++;
            if (processed % 10 == 0 || processed == total_tiles) {
                LOGI("Tiling progress: %d/%d tiles processed", processed, total_tiles);
            }
        }
    }

    LOGI("Process complete: %dx%d -> %dx%d (tiles: %dx%d, tilesize=%d)",
         w, h, out_w, out_h, xtiles, ytiles, tile_size);
    active_processes.fetch_sub(1, std::memory_order_acq_rel);
    { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
    return true;
}
