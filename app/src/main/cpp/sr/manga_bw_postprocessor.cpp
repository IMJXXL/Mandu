#include "manga_bw_postprocessor.h"
#include <algorithm>
#include <cstring>
#include <vector>

static inline unsigned char toGrayInt(unsigned char r, unsigned char g, unsigned char b) {
    return static_cast<unsigned char>((299 * static_cast<int>(r) + 587 * static_cast<int>(g) + 114 * static_cast<int>(b)) / 1000);
}

void quantizeGrayscale(unsigned char* rgba_data, int width, int height, int grayLevels) {
    if (grayLevels < 2) return;

    int pixel_count = width * height;
    int step = 255 / (grayLevels - 1);

    for (int i = 0; i < pixel_count; i++) {
        unsigned char gray = toGrayInt(rgba_data[i * 4 + 0], rgba_data[i * 4 + 1], rgba_data[i * 4 + 2]);
        int level = (gray + step / 2) / step;
        level = std::max(0, std::min(grayLevels - 1, level));
        unsigned char quantized = static_cast<unsigned char>(level * step);
        rgba_data[i * 4 + 0] = quantized;
        rgba_data[i * 4 + 1] = quantized;
        rgba_data[i * 4 + 2] = quantized;
    }
}

static int computeOtsuThreshold(const unsigned char* gray_data, int width, int height) {
    int histogram[256] = {0};
    int pixel_count = width * height;

    for (int i = 0; i < pixel_count; i++) {
        histogram[gray_data[i]]++;
    }

    long long total = static_cast<long long>(pixel_count);
    long long sum = 0;
    for (int i = 0; i < 256; i++) {
        sum += i * histogram[i];
    }

    long long sum_b = 0;
    int w_b = 0;
    double max_variance = 0.0;
    int threshold = 0;

    for (int t = 0; t < 256; t++) {
        w_b += histogram[t];
        if (w_b == 0) continue;

        int w_f = pixel_count - w_b;
        if (w_f == 0) break;

        sum_b += t * histogram[t];

        double m_b = static_cast<double>(sum_b) / w_b;
        double m_f = static_cast<double>(sum - sum_b) / w_f;

        double diff = m_b - m_f;
        double variance = static_cast<double>(w_b) * static_cast<double>(w_f) * diff * diff;

        if (variance > max_variance) {
            max_variance = variance;
            threshold = t;
        }
    }

    return threshold;
}

static void applyThreshold(unsigned char* rgba_data, int pixel_count, int thresh) {
    for (int i = 0; i < pixel_count; i++) {
        unsigned char gray = toGrayInt(rgba_data[i * 4 + 0], rgba_data[i * 4 + 1], rgba_data[i * 4 + 2]);
        unsigned char val = (gray > thresh) ? 255 : 0;
        rgba_data[i * 4 + 0] = val;
        rgba_data[i * 4 + 1] = val;
        rgba_data[i * 4 + 2] = val;
    }
}

void binarizeEnhance(unsigned char* rgba_data, int width, int height, int threshold) {
    int pixel_count = width * height;
    int thresh = threshold;

    if (thresh <= 0) {
        std::vector<unsigned char> gray(pixel_count);
        for (int i = 0; i < pixel_count; i++) {
            gray[i] = toGrayInt(rgba_data[i * 4 + 0], rgba_data[i * 4 + 1], rgba_data[i * 4 + 2]);
        }
        thresh = computeOtsuThreshold(gray.data(), width, height);
        applyThreshold(rgba_data, pixel_count, thresh);
    } else {
        applyThreshold(rgba_data, pixel_count, thresh);
    }
}

static void morphErode(unsigned char* data, int width, int height, int radius) {
    if (radius <= 0) return;
    std::vector<unsigned char> temp(width * height);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            unsigned char min_val = 255;
            int start_x = std::max(0, x - radius);
            int end_x = std::min(width - 1, x + radius);
            for (int dx = start_x; dx <= end_x; dx++) {
                min_val = std::min(min_val, data[y * width + dx]);
            }
            temp[y * width + x] = min_val;
        }
    }

    for (int x = 0; x < width; x++) {
        for (int y = 0; y < height; y++) {
            unsigned char min_val = 255;
            int start_y = std::max(0, y - radius);
            int end_y = std::min(height - 1, y + radius);
            for (int dy = start_y; dy <= end_y; dy++) {
                min_val = std::min(min_val, temp[dy * width + x]);
            }
            data[y * width + x] = min_val;
        }
    }
}

static void morphDilate(unsigned char* data, int width, int height, int radius) {
    if (radius <= 0) return;
    std::vector<unsigned char> temp(width * height);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            unsigned char max_val = 0;
            int start_x = std::max(0, x - radius);
            int end_x = std::min(width - 1, x + radius);
            for (int dx = start_x; dx <= end_x; dx++) {
                max_val = std::max(max_val, data[y * width + dx]);
            }
            temp[y * width + x] = max_val;
        }
    }

    for (int x = 0; x < width; x++) {
        for (int y = 0; y < height; y++) {
            unsigned char max_val = 0;
            int start_y = std::max(0, y - radius);
            int end_y = std::min(height - 1, y + radius);
            for (int dy = start_y; dy <= end_y; dy++) {
                max_val = std::max(max_val, temp[dy * width + x]);
            }
            data[y * width + x] = max_val;
        }
    }
}

void densityCorrection(unsigned char* rgba_data, int width, int height, bool enable) {
    if (!enable) return;

    int pixel_count = width * height;
    std::vector<unsigned char> gray(pixel_count);

    for (int i = 0; i < pixel_count; i++) {
        gray[i] = toGrayInt(rgba_data[i * 4 + 0], rgba_data[i * 4 + 1], rgba_data[i * 4 + 2]);
    }

    morphErode(gray.data(), width, height, 1);
    morphDilate(gray.data(), width, height, 1);

    for (int i = 0; i < pixel_count; i++) {
        rgba_data[i * 4 + 0] = gray[i];
        rgba_data[i * 4 + 1] = gray[i];
        rgba_data[i * 4 + 2] = gray[i];
    }
}

void processMangaBW(unsigned char* rgba_data, int width, int height, int grayLevels, bool enableDensityCorrection) {
    quantizeGrayscale(rgba_data, width, height, grayLevels);

    if (grayLevels <= 2) {
        binarizeEnhance(rgba_data, width, height, 0);
    }

    densityCorrection(rgba_data, width, height, enableDensityCorrection);
}
