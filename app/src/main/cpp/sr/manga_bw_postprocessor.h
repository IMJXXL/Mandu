#pragma once

#include <cstdint>

void quantizeGrayscale(unsigned char* rgba_data, int width, int height, int grayLevels);
void binarizeEnhance(unsigned char* rgba_data, int width, int height, int threshold);
void densityCorrection(unsigned char* rgba_data, int width, int height, bool enable);
void processMangaBW(unsigned char* rgba_data, int width, int height, int grayLevels, bool densityCorrection);
