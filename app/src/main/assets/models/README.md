# Super Resolution Models

This directory should contain model files for the super resolution engine.
Due to their size, models are not bundled with the APK and are downloaded on first use.

## Model Sources

- **Anime4K**: No model files required (algorithm-based)
- **Waifu2x**: https://github.com/nihui/waifu2x-ncnn-vulkan/tree/master/models
- **Real-ESRGAN Anime**: https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan/tree/master/models

## File Format

Models use NCNN format:
- `.param` - Network structure (text)
- `.bin` - Network weights (binary)
