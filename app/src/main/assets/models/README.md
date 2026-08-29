# 内置超分/优化档位

本目录的模型随 APK 打包，首次使用时由 ModelManager 复制到内部存储。

当前内置（2026-08-29 起，5 个档位）：

| 目录 | 档位 | 倍率 | 说明 |
|---|---|---|---|
| （无文件） | 快速优化 1x（算法，默认） | 1x | 纯 CPU 双边滤波+锐化，毫秒级去噪拉线条；无权重文件 |
| realcugan-2x-conservative | Real-CUGAN 保守档（se 紧凑版） | 2x | 漫画特化，线条最稳 |
| animevideov3-2x | realesr-animevideov3 x2 分支 | 2x | 极速档。与 4x 档同权重（SRVGG 紧凑网），走 Interp 2x 分支；实算 0.62 vs 1.42 MMACs/px |
| realesrgan-anime-fast | realesr-animevideov3 x4 分支 | 4x | 大屏细看。输出为 4 倍尺寸，适合平板 |
| waifu2x-cunet-1x | waifu2x cunet noise2 | 1x | 神经网络降噪不放大；固定 312×312 输入/256 业务区（引擎自校验探针） |

已移除：realesrgan-general-fast（通用快速 4x）——实测在部分设备上推理必崩，不再内置。
曾选过它的用户会自动回落到默认档。

## 文件格式

NCNN 格式：`.param` 网络结构（文本）+ `.bin` 权重（二进制）。

来源：
- Real-CUGAN: https://github.com/bilibili/ailab（ncnn 版）
- Real-ESRGAN / realesr-animevideov3: https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan
- waifu2x (nagadomi，MIT) 与 ncnn 移植 (nihui，MIT): https://github.com/nihui/waifu2x-ncnn-vulkan
